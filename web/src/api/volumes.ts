import { http, seg, Timeout } from '@/lib/http';
import type { JsonRecord, Volume, VolumeCreateRequest, VolumeSummary } from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/volumes`;

export const volumesApi = {
  list: (projectId: string) => http.get<Volume[]>(base(projectId)),

  get: (projectId: string, volumeId: string) => http.get<Volume>(`${base(projectId)}/${seg(volumeId)}`),

  create: (projectId: string, body: VolumeCreateRequest) => http.post<Volume>(base(projectId), { body }),

  update: (projectId: string, volumeId: string, body: VolumeCreateRequest) =>
    http.put<Volume>(`${base(projectId)}/${seg(volumeId)}`, { body }),

  // 删卷要搬动其下所有章节文件
  remove: (projectId: string, volumeId: string) =>
    http.delete<{ success: boolean }>(`${base(projectId)}/${seg(volumeId)}`, { timeout: Timeout.llm }),

  summary: (projectId: string, volumeId: string) =>
    http.get<VolumeSummary>(`${base(projectId)}/${seg(volumeId)}/summary`),

  saveSummary: (projectId: string, volumeId: string, body: VolumeSummary) =>
    http.put<VolumeSummary>(`${base(projectId)}/${seg(volumeId)}/summary`, { body }),

  stats: (projectId: string, volumeId: string) =>
    http.get<JsonRecord>(`${base(projectId)}/${seg(volumeId)}/stats`),
};
