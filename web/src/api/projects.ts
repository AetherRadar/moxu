import { http, seg, Timeout } from '@/lib/http';
import type { Project, ProjectCreateRequest, ProjectStats } from './types';

const base = '/projects';

export const projectsApi = {
  list: () => http.get<Project[]>(base),

  get: (projectId: string) => http.get<Project>(`${base}/${seg(projectId)}`),

  stats: (projectId: string) => http.get<ProjectStats>(`${base}/${seg(projectId)}/stats`),

  create: (body: ProjectCreateRequest) => http.post<Project>(base, { body }),

  rename: (projectId: string, name: string) =>
    http.patch<{ success: boolean }>(`${base}/${seg(projectId)}`, { body: { name } }),

  // 删除会连带清掉草稿、卡片和正典目录，磁盘 IO 比普通请求重
  remove: (projectId: string) =>
    http.delete<{ success: boolean }>(`${base}/${seg(projectId)}`, { timeout: Timeout.llm }),
};
