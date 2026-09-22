import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { retrievalApi, sessionApi, qk } from '@/api';
import { ApiError } from '@/lib/http';

export function useSessionStatus(projectId: string) {
  return useQuery({
    queryKey: qk.sessionStatus(projectId),
    queryFn: () => sessionApi.status(projectId),
    enabled: projectId !== '',
    refetchInterval: 5000,
  });
}

export function useCancelSession(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => sessionApi.cancel(projectId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.sessionStatus(projectId) });
      toast.success('会话已取消');
    },
    onError: (error) => toast.error(error instanceof ApiError ? error.message : '取消失败'),
  });
}

export function useBindings(projectId: string, chapter: string | null) {
  return useQuery({
    queryKey: qk.bindings(projectId, chapter ?? ''),
    queryFn: () => retrievalApi.bindings(projectId, chapter ?? ''),
    enabled: chapter !== null && chapter !== '',
    refetchInterval: (query) =>
      query.state.data?.rebuilding === true ? 2000 : false,
  });
}

export function useMemoryPack(projectId: string, chapter: string | null) {
  return useQuery({
    queryKey: qk.memoryPack(projectId, chapter ?? ''),
    queryFn: () => retrievalApi.memoryPack(projectId, chapter ?? ''),
    enabled: chapter !== null && chapter !== '',
  });
}

export function useRelationships(projectId: string) {
  return useQuery({
    queryKey: qk.relationships(projectId),
    queryFn: () => retrievalApi.relationships(projectId),
    enabled: projectId !== '',
  });
}

export function useRebuildBindings(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (chapter: string) => retrievalApi.rebuildBindings(projectId, chapter),
    onSuccess: (_data, chapter) => {
      void client.invalidateQueries({ queryKey: qk.bindings(projectId, chapter) });
      toast.success('绑定已重建');
    },
    onError: (error) => toast.error(error instanceof ApiError ? error.message : '重建失败'),
  });
}
