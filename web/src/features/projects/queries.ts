import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { toast } from 'sonner';
import { projectsApi, projectScope, qk } from '@/api';
import type { Project, ProjectCreateRequest } from '@/api/types';
import { ApiError } from '@/lib/http';

export function useProjects(): UseQueryResult<Project[]> {
  return useQuery({ queryKey: qk.projects, queryFn: () => projectsApi.list() });
}

export function useProject(projectId: string): UseQueryResult<Project> {
  return useQuery({
    queryKey: qk.project(projectId),
    queryFn: () => projectsApi.get(projectId),
    enabled: projectId !== '',
  });
}

function message(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

export function useCreateProject() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ProjectCreateRequest) => projectsApi.create(body),
    onSuccess: (project) => {
      // 直接把新项目塞进列表缓存，省一次往返，点完立刻能看到
      client.setQueryData<Project[]>(qk.projects, (previous) =>
        previous ? [...previous, project] : [project],
      );
      client.setQueryData(qk.project(project.id), project);
    },
    onError: (error) => toast.error(message(error, '创建项目失败')),
  });
}

export function useRenameProject(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => projectsApi.rename(projectId, name),
    onMutate: async (name) => {
      // 乐观更新：重命名几乎不会失败，等一个往返才改名字很别扭
      await client.cancelQueries({ queryKey: qk.projects });
      const previous = client.getQueryData<Project[]>(qk.projects);
      client.setQueryData<Project[]>(qk.projects, (list) =>
        list?.map((item) => (item.id === projectId ? { ...item, name } : item)),
      );
      client.setQueryData<Project>(qk.project(projectId), (item) => (item ? { ...item, name } : item));
      return { previous };
    },
    onError: (error, _name, context) => {
      if (context?.previous) client.setQueryData(qk.projects, context.previous);
      toast.error(message(error, '重命名失败'));
    },
    onSettled: () => void client.invalidateQueries({ queryKey: qk.projects }),
  });
}

export function useDeleteProject() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (projectId: string) => projectsApi.remove(projectId),
    onSuccess: (_result, projectId) => {
      client.setQueryData<Project[]>(qk.projects, (list) => list?.filter((item) => item.id !== projectId));
      // 该项目下所有派生缓存（章节、卡片、事实…）一并清掉
      client.removeQueries({ queryKey: projectScope(projectId) });
      toast.success('项目已删除');
    },
    onError: (error) => toast.error(message(error, '删除项目失败')),
  });
}
