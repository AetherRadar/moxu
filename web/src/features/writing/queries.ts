import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { draftsApi, qk, type DraftSaveRequest, type UpdateContentRequest } from '@/api';
import type { Draft } from '@/api/types';
import { ApiError } from '@/lib/http';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

/**
 * 当前工作草稿（current.yaml）。
 * 404 意味着章节壳在但还没写过——编辑器当空文档处理。
 */
export function useDraft(projectId: string, chapter: string | null) {
  return useQuery({
    queryKey: qk.draft(projectId, chapter ?? ''),
    queryFn: () => draftsApi.latest(projectId, chapter ?? ''),
    enabled: chapter !== null && chapter !== '',
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });
}

/** 手动保存（Ctrl+S）：全量落盘 + 后端重建 review/memory pack。 */
export function useSaveDraft(projectId: string, chapter: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: DraftSaveRequest) => draftsApi.save(projectId, chapter, body),
    onSuccess: (draft: Draft) => {
      client.setQueryData(qk.draft(projectId, chapter), draft);
      void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
      toast.success('已保存');
    },
    onError: (error) => toast.error(reason(error, '保存失败')),
  });
}

/**
 * 防抖自动保存：轻量落盘，不触发重建。
 * 调用方持有 AbortController，下一次保存取消上一次。
 */
export function useAutosaveDraft(projectId: string, chapter: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, signal }: { body: UpdateContentRequest; signal: AbortSignal }) =>
      draftsApi.autosave(projectId, chapter, body, signal),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.chapterSummaries(projectId) });
    },
    // 静默：自动保存失败不打断写作，状态栏自己显示「保存失败」
  });
}

export function useDraftVersions(projectId: string, chapter: string | null, enabled: boolean) {
  return useQuery({
    queryKey: qk.draftVersions(projectId, chapter ?? ''),
    queryFn: () => draftsApi.versions(projectId, chapter ?? ''),
    enabled: enabled && chapter !== null && chapter !== '',
  });
}

export function useDraftVersion(
  projectId: string,
  chapter: string | null,
  version: string | null,
) {
  return useQuery({
    queryKey: qk.draftVersion(projectId, chapter ?? '', version ?? ''),
    queryFn: () => draftsApi.version(projectId, chapter ?? '', version ?? ''),
    enabled: chapter !== null && version !== null && version !== '',
  });
}
