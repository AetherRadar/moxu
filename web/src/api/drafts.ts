import { http, seg, Timeout } from '@/lib/http';
import { queueDraftWrite } from '@/lib/draft-write-barrier';
import type {
  ChapterSummary,
  DeAiRewriteConfig,
  Draft,
  JsonRecord,
  SceneBrief,
} from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/drafts`;

export interface DraftSaveRequest {
  content: string;
  word_count?: number;
  pending_confirmations?: string[];
}

export interface DraftContinuityReport {
  revision: string;
  source_chapter: string;
  source_hash: string;
  draft_hash: string;
  coverage: 'local_rules_only' | 'local_and_semantic';
  semantic_status?: 'not_requested' | 'unchecked' | 'partial' | 'complete';
  truncated: boolean;
  reference_excerpts?: string;
  issues: Array<{ type: string; severity: string; evidence: string; offset: number; message: string }>;
}

/** `/content` 与 `/autosave` 都接受顺带改标题，省一次请求。 */
export interface UpdateContentRequest {
  content: string;
  title?: string;
}

/** 改写只吃正文；强度等参数取项目级 rewrite-config。 */
export interface RewriteTextRequest {
  text: string;
}

export interface GenerateTitleRequest {
  content?: string;
  language?: string;
}

export interface GenerateTitlesBatchRequest {
  volume_id?: string;
  chapters?: string[];
  language?: string;
}

export interface ReorderChaptersRequest {
  volume_id: string;
  chapter_order: string[];
}

export const draftsApi = {
  checkSemantics: (projectId: string, chapter: string, content: string, revision: string, maxTokens: number) =>
    http.post<DraftContinuityReport>(`${base(projectId)}/${seg(chapter)}/semantic-check`, {
      body: { content, revision, max_tokens: maxTokens }, timeout: Timeout.llm,
    }),

  checkContinuity: (projectId: string, chapter: string, content: string) =>
    http.post<DraftContinuityReport>(`${base(projectId)}/${seg(chapter)}/continuity-check`, {
      body: { content },
    }),

  listChapters: (projectId: string) => http.get<string[]>(base(projectId)),

  latest: (projectId: string, chapter: string) =>
    http.get<Draft>(`${base(projectId)}/${seg(chapter)}`),

  version: (projectId: string, chapter: string, version: string) =>
    http.get<Draft>(`${base(projectId)}/${seg(chapter)}/${seg(version)}`),

  versions: (projectId: string, chapter: string) =>
    http.get<string[]>(`${base(projectId)}/${seg(chapter)}/versions`),

  final: (projectId: string, chapter: string) =>
    http.get<JsonRecord>(`${base(projectId)}/${seg(chapter)}/final`),

  // 后端保存后会串行重建绑定和记忆包，所以给长超时
  save: (projectId: string, chapter: string, body: DraftSaveRequest) =>
    queueDraftWrite(projectId, chapter, () =>
      http.put<Draft>(`${base(projectId)}/${seg(chapter)}`, { body, timeout: Timeout.draftSave })),

  updateContent: (projectId: string, chapter: string, body: UpdateContentRequest) =>
    queueDraftWrite(projectId, chapter, () => http.put<JsonRecord>(`${base(projectId)}/${seg(chapter)}/content`, {
      body,
      timeout: Timeout.draftSave,
    })),

  /** Writes are ordered and awaited by generation; aborting a fetch cannot undo a server write. */
  autosave: (
    projectId: string,
    chapter: string,
    body: UpdateContentRequest,
    options?: { signal?: AbortSignal; keepalive?: boolean },
  ) =>
    queueDraftWrite(projectId, chapter, () => http.put<JsonRecord>(`${base(projectId)}/${seg(chapter)}/autosave`, {
      body,
      timeout: Timeout.quick,
      keepalive: options?.keepalive,
    })),

  removeChapter: (projectId: string, chapter: string) =>
    http.delete<JsonRecord>(`${base(projectId)}/${seg(chapter)}`, { timeout: Timeout.llm }),

  /** 从指定章起全部回滚，破坏性操作。 */
  resetFrom: (projectId: string, chapter: string) =>
    http.post<JsonRecord>(`${base(projectId)}/reset-from/${seg(chapter)}`, { timeout: Timeout.llm }),

  reorder: (projectId: string, body: ReorderChaptersRequest) =>
    http.post<{ success: boolean; updated: ChapterSummary[] }>(`${base(projectId)}/reorder`, {
      body,
      timeout: Timeout.llm,
    }),

  sceneBrief: (projectId: string, chapter: string) =>
    http.get<SceneBrief>(`${base(projectId)}/${seg(chapter)}/scene-brief`),

  saveSceneBrief: (projectId: string, chapter: string, body: SceneBrief) =>
    http.put<JsonRecord>(`${base(projectId)}/${seg(chapter)}/scene-brief`, { body }),

  summaries: (projectId: string, volumeId?: string) =>
    http.get<ChapterSummary[]>(`${base(projectId)}/summaries`, {
      query: volumeId ? { volume_id: volumeId } : undefined,
    }),

  summary: (projectId: string, chapter: string) =>
    http.get<ChapterSummary>(`${base(projectId)}/${seg(chapter)}/summary`),

  saveSummary: (projectId: string, chapter: string, body: ChapterSummary) =>
    http.put<JsonRecord>(`${base(projectId)}/${seg(chapter)}/summary`, { body }),

  generateTitle: (projectId: string, chapter: string, body?: GenerateTitleRequest) =>
    http.post<JsonRecord>(`${base(projectId)}/${seg(chapter)}/generate-title`, {
      body: body ?? {},
      timeout: Timeout.llm,
    }),

  generateTitlesBatch: (projectId: string, body: GenerateTitlesBatchRequest, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/generate-titles/batch`, {
      body,
      timeout: Timeout.batch,
      signal,
    }),

  rewriteConfig: (projectId: string) => http.get<DeAiRewriteConfig>(`${base(projectId)}/rewrite-config`),

  saveRewriteConfig: (projectId: string, body: DeAiRewriteConfig) =>
    http.put<JsonRecord>(`${base(projectId)}/rewrite-config`, { body }),

  rewrite: (projectId: string, body: RewriteTextRequest, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/rewrite`, {
      body,
      timeout: Timeout.rewrite,
      signal,
    }),
};
