import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { configApi, draftsApi, qk } from '@/api';
import type { DeAiRewriteConfig, LlmAssignments, LlmProfile } from '@/api/types';
import { ApiError } from '@/lib/http';

function reason(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function useLlmProfiles() {
  return useQuery({
    queryKey: qk.llmProfiles,
    queryFn: () => configApi.profiles(),
  });
}

export function useLlmProviders() {
  return useQuery({
    queryKey: qk.llmProviders,
    queryFn: () => configApi.providers(),
    staleTime: Infinity,
  });
}

export function useSaveLlmProfile() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (profile: LlmProfile) => configApi.saveProfile(profile),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.llmProfiles });
      toast.success('档案已保存');
    },
    onError: (error) => toast.error(reason(error, '保存失败')),
  });
}

export function useDeleteLlmProfile() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (profileId: string) => configApi.deleteProfile(profileId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.llmProfiles });
      void client.invalidateQueries({ queryKey: qk.llmAssignments });
      toast.success('档案已删除');
    },
    onError: (error) => toast.error(reason(error, '删除失败')),
  });
}

/** 连通性测试：结果含 success/elapsed_ms/preview 或 error，直接交给调用方展示。 */
export function useTestLlmProfile() {
  return useMutation({
    mutationFn: (profile: LlmProfile) => configApi.testProfile(profile),
    onError: (error) => toast.error(reason(error, '测试请求失败')),
  });
}

export function useLlmAssignments() {
  return useQuery({
    queryKey: qk.llmAssignments,
    queryFn: () => configApi.assignments(),
  });
}

export function useSaveLlmAssignments() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (assignments: LlmAssignments) => configApi.saveAssignments(assignments),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.llmAssignments });
      toast.success('分工已保存');
    },
    onError: (error) => toast.error(reason(error, '保存分工失败')),
  });
}

export function useRewriteConfig(projectId: string) {
  return useQuery({
    queryKey: qk.rewriteConfig(projectId),
    queryFn: () => draftsApi.rewriteConfig(projectId),
    enabled: projectId !== '',
  });
}

export function useSaveRewriteConfig(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (config: DeAiRewriteConfig) => draftsApi.saveRewriteConfig(projectId, config),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.rewriteConfig(projectId) });
      toast.success('改写配置已保存');
    },
    onError: (error) => toast.error(reason(error, '保存改写配置失败')),
  });
}
