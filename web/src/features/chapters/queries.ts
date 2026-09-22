import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { draftsApi, projectScope, qk, volumesApi } from '@/api';
import type { ChapterSummary, VolumeCreateRequest } from '@/api/types';
import { ApiError } from '@/lib/http';
import { nextChapterId, type ChapterKind } from '@/lib/chapter-id';
import { buildChapterTree, flatChapterIds, type VolumeNode } from './tree';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export interface ChapterTreeResult {
  volumes: VolumeNode[];
  chapterIds: string[];
  isPending: boolean;
  isError: boolean;
  refetch: () => void;
}

/**
 * 章节树。
 *
 * 三个请求并行发；合并结果走 useMemo，只在任一数据真的变化时重算——
 * TanStack Query 的结构共享保证内容没变时引用也不变，所以这个 memo 命中率很高。
 */
export function useChapterTree(projectId: string): ChapterTreeResult {
  const enabled = projectId !== '';

  const volumesQuery = useQuery({
    queryKey: qk.volumes(projectId),
    queryFn: () => volumesApi.list(projectId),
    enabled,
  });
  const chaptersQuery = useQuery({
    queryKey: qk.drafts(projectId),
    queryFn: () => draftsApi.listChapters(projectId),
    enabled,
  });
  const summariesQuery = useQuery({
    queryKey: qk.chapterSummaries(projectId),
    queryFn: () => draftsApi.summaries(projectId),
    enabled,
  });

  const volumes = useMemo(
    () =>
      buildChapterTree(volumesQuery.data ?? [], chaptersQuery.data ?? [], summariesQuery.data ?? []),
    [volumesQuery.data, chaptersQuery.data, summariesQuery.data],
  );

  const chapterIds = useMemo(() => flatChapterIds(volumes), [volumes]);

  return {
    volumes,
    chapterIds,
    isPending: volumesQuery.isPending || chaptersQuery.isPending || summariesQuery.isPending,
    isError: volumesQuery.isError || chaptersQuery.isError || summariesQuery.isError,
    refetch: () => {
      void volumesQuery.refetch();
      void chaptersQuery.refetch();
      void summariesQuery.refetch();
    },
  };
}

/** 章节结构变动后要刷新的几个键。 */
function invalidateStructure(client: ReturnType<typeof useQueryClient>, projectId: string): void {
  void client.invalidateQueries({ queryKey: qk.drafts(projectId) });
  void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
  void client.invalidateQueries({ queryKey: qk.volumes(projectId) });
  void client.invalidateQueries({ queryKey: qk.project(projectId) });
  void client.invalidateQueries({ queryKey: qk.projectStats(projectId) });
}

export interface CreateChapterInput {
  volumeId: string;
  kind?: ChapterKind;
  /** 番外/幕间的父章节 */
  insertAfter?: string;
  title?: string;
}

/**
 * 新建章节。
 *
 * 后端没有"创建章节"这个动作：写一次空草稿会建出 drafts 目录，
 * 再写一次摘要把标题和卷归属登记上。两步都做才能在树里正常显示。
 */
export function useCreateChapter(projectId: string, existingIds: readonly string[]) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async ({ volumeId, kind = 'normal', insertAfter = '', title = '' }: CreateChapterInput) => {
      const id = nextChapterId(existingIds, volumeId, kind, insertAfter);
      if (id === '') throw new ApiError('无法分配章节编号', 400, '', null);

      await draftsApi.save(projectId, id, { content: '', word_count: 0 });
      await draftsApi.saveSummary(projectId, id, {
        chapter: id,
        volume_id: volumeId,
        order_index: null,
        title: title.trim(),
        word_count: 0,
        key_events: [],
        new_facts: [],
        character_state_changes: [],
        open_loops: [],
        brief_summary: '',
      });
      return id;
    },
    onSuccess: () => invalidateStructure(client, projectId),
    onError: (error) => toast.error(reason(error, '新建章节失败')),
  });
}

export function useRenameChapter(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async ({ chapter, title }: { chapter: string; title: string }) => {
      const current = await draftsApi.summary(projectId, chapter).catch((error: unknown) => {
        // 还没有摘要的新章：现造一份，别让重命名失败
        if (error instanceof ApiError && error.isNotFound) return null;
        throw error;
      });
      await draftsApi.saveSummary(projectId, chapter, {
        ...(current ?? {
          chapter,
          volume_id: null,
          order_index: null,
          word_count: 0,
          key_events: [],
          new_facts: [],
          character_state_changes: [],
          open_loops: [],
          brief_summary: '',
        }),
        chapter,
        title: title.trim(),
      });
    },
    onSuccess: () => void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) }),
    onError: (error) => toast.error(reason(error, '重命名章节失败')),
  });
}

export function useDeleteChapter(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (chapter: string) => draftsApi.removeChapter(projectId, chapter),
    onSuccess: (_result, chapter) => {
      client.removeQueries({ queryKey: qk.draft(projectId, chapter) });
      invalidateStructure(client, projectId);
      toast.success('章节已删除');
    },
    onError: (error) => toast.error(reason(error, '删除章节失败')),
  });
}

export function useReorderChapters(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ volumeId, order }: { volumeId: string; order: string[] }) =>
      draftsApi.reorder(projectId, { volume_id: volumeId, chapter_order: order }),
    onSuccess: (result) => {
      // 后端把重排后的全量摘要回传了，直接写进缓存，省一次列表请求
      if (Array.isArray(result.updated)) {
        client.setQueryData<ChapterSummary[]>(qk.chapterSummaries(projectId), (previous) => {
          if (!previous) return result.updated;
          const updated = new Map(result.updated.map((item) => [item.chapter, item]));
          return previous.map((item) => updated.get(item.chapter) ?? item);
        });
      } else {
        void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
      }
    },
    onError: (error) => toast.error(reason(error, '调整顺序失败')),
  });
}

export function useCreateVolume(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: VolumeCreateRequest) => volumesApi.create(projectId, body),
    onSuccess: () => invalidateStructure(client, projectId),
    onError: (error) => toast.error(reason(error, '新建分卷失败')),
  });
}

export function useUpdateVolume(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ volumeId, body }: { volumeId: string; body: VolumeCreateRequest }) =>
      volumesApi.update(projectId, volumeId, body),
    onSuccess: () => void client.invalidateQueries({ queryKey: qk.volumes(projectId) }),
    onError: (error) => toast.error(reason(error, '保存分卷失败')),
  });
}

export function useDeleteVolume(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (volumeId: string) => volumesApi.remove(projectId, volumeId),
    onSuccess: () => {
      // 删卷会搬动其下所有章节，派生缓存全部作废
      client.removeQueries({ queryKey: projectScope(projectId) });
      invalidateStructure(client, projectId);
      toast.success('分卷已删除');
    },
    onError: (error) => toast.error(reason(error, '删除分卷失败')),
  });
}
