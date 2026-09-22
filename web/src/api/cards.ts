import { http, seg, Timeout } from '@/lib/http';
import type { CharacterCard, JsonRecord, StyleCard, WorldCard } from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/cards`;

/**
 * 卡片域。
 *
 * 后端对人物/世界观卡各提供两套读写入口：路径参数版和 `?name=` 查询参数版。
 * 卡名可能含 `/`、`.` 或纯空白，这些在路径里即使 encode 过也会被 Spring 的
 * 路径匹配吃掉，所以这里统一只用查询参数版，避免一类难查的 404。
 */
export const cardsApi = {
  characters: (projectId: string) => http.get<CharacterCard[]>(`${base(projectId)}/characters/index`),

  createCharacter: (projectId: string, body: CharacterCard) =>
    http.post<JsonRecord>(`${base(projectId)}/characters`, { body }),

  updateCharacter: (projectId: string, name: string, body: CharacterCard) =>
    http.put<JsonRecord>(`${base(projectId)}/characters/by-name`, { query: { name }, body }),

  deleteCharacter: (projectId: string, name: string) =>
    http.delete<JsonRecord>(`${base(projectId)}/characters/by-name`, { query: { name } }),

  world: (projectId: string) => http.get<WorldCard[]>(`${base(projectId)}/world/index`),

  createWorldCard: (projectId: string, body: WorldCard) =>
    http.post<JsonRecord>(`${base(projectId)}/world`, { body }),

  updateWorldCard: (projectId: string, name: string, body: WorldCard) =>
    http.put<JsonRecord>(`${base(projectId)}/world/by-name`, { query: { name }, body }),

  deleteWorldCard: (projectId: string, name: string) =>
    http.delete<JsonRecord>(`${base(projectId)}/world/by-name`, { query: { name } }),

  style: (projectId: string) => http.get<StyleCard>(`${base(projectId)}/style`),

  saveStyle: (projectId: string, body: StyleCard) =>
    http.put<JsonRecord>(`${base(projectId)}/style`, { body }),

  extractStyle: (projectId: string, body?: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/style/extract`, {
      body: body ?? {},
      timeout: Timeout.batch,
      signal,
    }),

  /** 让模型补写卡片描述。 */
  generateDescription: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/generate`, { body, timeout: Timeout.llm, signal }),

  /**
   * 从大纲文本抽卡（POST /cards/extract-from-outline）。
   *
   * @deprecated 与 extract-candidates 语义重复：ExtractCardsDialog 一直把总纲和
   * 分卷纲自动并入材料，capabilities 也只声明 character/world/style 三个模块，
   * 没有独立的大纲模块。走 extractCandidates({module}) 即可，不再接线此端点。
   */
  extractFromOutline: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/extract-from-outline`, {
      body,
      timeout: Timeout.batch,
      signal,
    }),

  extractCapabilities: (projectId: string) => http.get<JsonRecord>(`${base(projectId)}/extract-candidates`),

  extractCandidates: (projectId: string, module: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/extract-candidates/${seg(module)}`, {
      body,
      timeout: Timeout.batch,
      signal,
    }),
};
