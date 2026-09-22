import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { outlineApi, projectScope, qk } from '@/api';
import type { PlannedChapter, ProjectOutline } from '@/api/types';
import { ApiError } from '@/lib/http';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function useProjectOutline(projectId: string) {
  return useQuery({
    queryKey: qk.outline(projectId),
    queryFn: () => outlineApi.get(projectId),
    enabled: projectId !== '',
  });
}

/** 从全量大纲里抽某一级的内容；没有条目时回空串而不是 undefined。 */
export function outlineContent(
  outline: ProjectOutline | undefined,
  level: 'master' | { volumeId: string } | { chapterId: string },
): string {
  if (outline === undefined) return '';
  if (level === 'master') return outline.master.content ?? '';
  if ('volumeId' in level) {
    return outline.volumes.find((item) => item.volume_id === level.volumeId)?.content ?? '';
  }
  return outline.chapters.find((item) => item.chapter_id === level.chapterId)?.content ?? '';
}

function useSaveOutline(
  projectId: string,
  save: (content: string) => Promise<unknown>,
  label: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: save,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.outline(projectId) });
      toast.success(`${label}已保存`);
    },
    onError: (error) => toast.error(reason(error, `保存${label}失败`)),
  });
}

export function useSaveMasterOutline(projectId: string) {
  return useSaveOutline(projectId, (content) => outlineApi.saveMaster(projectId, content), '总纲');
}

export function useSaveVolumeOutline(projectId: string, volumeId: string) {
  return useSaveOutline(
    projectId,
    (content) => outlineApi.saveVolume(projectId, volumeId, content),
    '分卷纲',
  );
}

export function useSaveChapterOutline(projectId: string, chapterId: string) {
  return useSaveOutline(
    projectId,
    (content) => outlineApi.saveChapter(projectId, chapterId, content),
    '章纲',
  );
}

/** 应用章节规划会新建章节、写摘要——整个项目缓存作废。 */
export function useApplyChapterPlan(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ volumeId, chapters }: { volumeId: string; chapters: PlannedChapter[] }) =>
      outlineApi.applyChapterPlan(projectId, volumeId, chapters),
    onSuccess: (result) => {
      void client.invalidateQueries({ queryKey: projectScope(projectId) });
      toast.success(
        `已应用规划：新建 ${result.created.length} 章，更新 ${result.updated.length} 章`,
      );
    },
    onError: (error) => toast.error(reason(error, '应用章节规划失败')),
  });
}
