import { http, seg, Timeout } from '@/lib/http';
import type { JsonRecord, LlmAssignments, LlmProfile, LlmProviderMeta } from './types';

/** LLM 配置是全局的，不属于某个项目。 */
export const configApi = {
  profiles: () => http.get<LlmProfile[]>('/config/llm/profiles'),

  saveProfile: (body: LlmProfile) => http.post<JsonRecord>('/config/llm/profiles', { body }),

  /** 连通性测试会真的打一次模型接口，耐心等。 */
  testProfile: (body: LlmProfile, signal?: AbortSignal) =>
    http.post<JsonRecord>('/config/llm/profiles/test', { body, timeout: Timeout.llm, signal }),

  deleteProfile: (profileId: string) => http.delete<JsonRecord>(`/config/llm/profiles/${seg(profileId)}`),

  assignments: () => http.get<LlmAssignments>('/config/llm/assignments'),

  saveAssignments: (body: LlmAssignments) => http.post<JsonRecord>('/config/llm/assignments', { body }),

  providers: () => http.get<LlmProviderMeta[]>('/config/llm/providers_meta'),

  /**
   * 拉取模型列表。浏览器直连第三方接口会被 CORS 挡住，所以走后端代理。
   */
  fetchModels: (body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>('/proxy/fetch-models', { body, timeout: Timeout.llm, signal }),
};
