import { http, seg, Timeout } from '@/lib/http';
import type { EvidenceItem, JsonRecord } from './types';

export interface EvidenceSearchRequest {
  /** 检索词列表，后端合并去重；seed_entities 也会并入 */
  queries: string[];
  types?: string[];
  limit?: number;
  include_text_chunks?: boolean;
  seed_entities?: string[];
  text_chunk_chapters?: string[];
  text_chunk_exclude_chapters?: string[];
  rebuild?: boolean;
  semantic_rerank?: boolean;
  rerank_query?: string;
  rerank_top_k?: number;
  round?: number;
  note?: string;
}

export interface EvidenceSearchResponse extends JsonRecord {
  items?: EvidenceItem[];
  results?: EvidenceItem[];
}

const projectBase = (projectId: string): string => `/projects/${seg(projectId)}`;

/** 证据检索、文本分块与绑定关系。 */
export const retrievalApi = {
  searchEvidence: (projectId: string, body: EvidenceSearchRequest, signal?: AbortSignal) =>
    http.post<EvidenceSearchResponse>(`${projectBase(projectId)}/evidence/search`, {
      body,
      timeout: Timeout.llm,
      signal,
    }),

  /** 重建证据索引：全书扫描，很慢。 */
  rebuildEvidence: (projectId: string, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${projectBase(projectId)}/evidence/rebuild`, {
      timeout: Timeout.batch,
      signal,
    }),

  searchChunks: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${projectBase(projectId)}/text-chunks/search`, {
      body,
      timeout: Timeout.llm,
      signal,
    }),

  rebuildChunks: (projectId: string, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${projectBase(projectId)}/text-chunks/rebuild`, {
      timeout: Timeout.batch,
      signal,
    }),

  bindings: (projectId: string, chapter: string) =>
    http.get<JsonRecord>(`${projectBase(projectId)}/bindings/${seg(chapter)}`),

  rebuildBindings: (projectId: string, chapter: string) =>
    http.post<JsonRecord>(`${projectBase(projectId)}/bindings/${seg(chapter)}/rebuild`, {
      timeout: Timeout.llm,
    }),

  rebuildBindingsBatch: (projectId: string, body?: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${projectBase(projectId)}/bindings/rebuild-batch`, {
      body: body ?? {},
      timeout: Timeout.batch,
      signal,
    }),

  relationships: (projectId: string) =>
    http.get<JsonRecord>(`${projectBase(projectId)}/graph-indexes/relationships`),

  memoryPack: (projectId: string, chapter: string) =>
    http.get<JsonRecord>(`${projectBase(projectId)}/memory-pack/${seg(chapter)}`),
};
