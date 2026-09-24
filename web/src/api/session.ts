import { http, seg, Timeout } from '@/lib/http';
import { openTextStream } from '@/lib/sse';
import type {
  AnswerQuestionsRequest,
  ArchiveReport,
  EditSuggestRequest,
  EditSuggestResponse,
  FeedbackRequest,
  JsonRecord,
  SessionFlowResponse,
  SessionStatusResponse,
  StartSessionRequest,
} from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/session`;

export interface ContinuityPlan {
  revision: string;
  status:
    | 'ready'
    | 'needs_sync'
    | 'needs_review'
    | 'needs_causal'
    | 'blocked'
    | 'running'
    | 'paused'
    | 'partial';
  changed_chapters: string[];
  unknown_chapters: string[];
  removed_chapters: string[];
  affected_chapters: string[];
  message?: string;
  job_id?: string;
  job?: AnalysisJob;
  causal?: CausalReport;
  deltas: {
    chapter: string;
    before: JsonRecord;
    after: JsonRecord;
    related_chapters: string[];
    coverage: string;
    conflict_check?: JsonRecord;
  }[];
}

export interface CausalEvidence {
  chapter: string;
  sourceHash: string;
  start: number;
  end: number;
  quote: string;
}

export interface CausalFinding {
  id: string;
  chapter: string;
  classification: 'supported_conflict' | 'explained' | 'insufficient_evidence';
  explanation: string;
  prior?: CausalEvidence;
  current?: CausalEvidence;
  uncertainties?: string[];
  dependency_path?: { id: string; chapter: string; label: string; quote: string }[];
}

export interface CausalReport {
  active?: boolean;
  revision: string;
  status: 'not_started' | 'stale' | 'running' | 'paused' | 'partial' | 'complete';
  fresh: boolean;
  ready_for_generation: boolean;
  job_id?: string;
  total_chapters: number;
  completed_chapters?: number;
  event_count?: number;
  dependency_count?: number;
  impacted_events?: number;
  reused_events?: number;
  cached_segments?: number;
  processed_segments?: number;
  checked_candidates?: number;
  total_candidates?: number;
  stage?: 'extract' | 'verify';
  current_chapter?: string;
  max_tokens?: number;
  max_minutes?: number;
  metrics?: { budget_tokens: number; calls_this_run: number };
  pause_reason?: string;
  error?: string;
  unresolved: number;
  findings: CausalFinding[];
  decisions?: Record<string, { resolution: 'accepted_risk' | 'false_positive'; note: string }>;
}

export interface AnalysisJob {
  job_id: string;
  status: 'running' | 'complete' | 'partial' | 'paused';
  mode: 'sync' | 'preview';
  success?: boolean;
  pause_reason?: string;
  reduce_error?: string;
  skipped_count?: number;
  synopsis?: { brief_summary?: string };
  metrics?: { budget_tokens: number; calls_this_run: number; unknown_usage_calls_this_run: number };
  results: { chapter: string; success: boolean; skipped?: boolean; error?: string; analysis?: JsonRecord }[];
}

export const sessionApi = {
  causalStatus: (projectId: string, chapter: string) =>
    http.get<CausalReport>(`${base(projectId)}/continuity/${seg(chapter)}/causal`),
  runCausal: (projectId: string, chapter: string, body: JsonRecord) =>
    http.post<CausalReport>(`${base(projectId)}/continuity/${seg(chapter)}/causal/run`, {
      body,
      timeout: Timeout.batch,
    }),
  cancelCausal: (projectId: string, chapter: string) =>
    http.post<CausalReport>(`${base(projectId)}/continuity/${seg(chapter)}/causal/cancel`),
  decideCausal: (projectId: string, chapter: string, body: JsonRecord) =>
    http.post<CausalReport>(`${base(projectId)}/continuity/${seg(chapter)}/causal/decisions`, { body }),
  continuity: (projectId: string, chapter: string) =>
    http.get<ContinuityPlan>(`${base(projectId)}/continuity/${seg(chapter)}`),
  syncContinuity: (projectId: string, chapter: string, body: JsonRecord) =>
    http.post<ContinuityPlan>(`${base(projectId)}/continuity/${seg(chapter)}/sync`, {
      body,
      timeout: Timeout.batch,
    }),
  acknowledgeContinuity: (projectId: string, chapter: string, revision: string) =>
    http.post<ContinuityPlan>(`${base(projectId)}/continuity/${seg(chapter)}/acknowledge`, {
      body: { revision },
    }),
  cleanRemovedContinuity: (projectId: string, chapter: string, revision: string) =>
    http.post<ContinuityPlan>(`${base(projectId)}/continuity/${seg(chapter)}/clean-removed`, {
      body: { revision },
    }),
  analysisJob: (projectId: string, jobId: string) =>
    http.get<AnalysisJob>(`${base(projectId)}/analysis-jobs/${seg(jobId)}`),
  cancelAnalysisJob: (projectId: string, jobId: string) =>
    http.post<AnalysisJob>(`${base(projectId)}/analysis-jobs/${seg(jobId)}/cancel`),
  bookSummary: (projectId: string) =>
    http.get<{ brief_summary?: string }>(`${base(projectId)}/analysis-book-summary`),
  archiveStatus: (projectId: string, chapter: string) =>
    http.get<ArchiveReport>(`${base(projectId)}/archive/${seg(chapter)}`),
  retryArchive: (projectId: string, chapter: string) =>
    http.post<SessionFlowResponse>(`${base(projectId)}/archive/${seg(chapter)}/retry`, {
      timeout: Timeout.batch,
    }),

  /** 启动会话：生成场景简报并跑研究循环，可能反问。 */
  start: (projectId: string, body: StartSessionRequest, signal?: AbortSignal) =>
    http.post<SessionFlowResponse>(`${base(projectId)}/start`, { body, timeout: Timeout.batch, signal }),

  status: (projectId: string) => http.get<SessionStatusResponse>(`${base(projectId)}/status`),

  cancel: (projectId: string) => http.post<JsonRecord>(`${base(projectId)}/cancel`),

  answerQuestions: (projectId: string, body: AnswerQuestionsRequest, signal?: AbortSignal) =>
    http.post<SessionFlowResponse>(`${base(projectId)}/answer-questions`, {
      body,
      timeout: Timeout.batch,
      signal,
    }),

  feedback: (projectId: string, body: FeedbackRequest, signal?: AbortSignal) =>
    http.post<SessionFlowResponse>(`${base(projectId)}/feedback`, { body, timeout: Timeout.batch, signal }),

  /** 选区局部改写建议：返回整章修订稿 + 差异预览，不落盘。 */
  editSuggest: (projectId: string, body: EditSuggestRequest, signal?: AbortSignal) =>
    http.post<EditSuggestResponse>(`${base(projectId)}/edit-suggest`, { body, timeout: Timeout.llm, signal }),

  analyze: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/analyze`, { body, timeout: Timeout.llm, signal }),

  saveAnalysis: (projectId: string, body: JsonRecord) =>
    http.post<JsonRecord>(`${base(projectId)}/save-analysis`, { body, timeout: Timeout.llm }),

  /** 批量分析：整卷或全书，走 30 分钟超时。 */
  analyzeBatch: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<AnalysisJob>(`${base(projectId)}/analyze-batch`, { body, timeout: Timeout.batch, signal }),

  analyzeSync: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<AnalysisJob>(`${base(projectId)}/analyze-sync`, { body, timeout: Timeout.batch, signal }),

  saveAnalysisBatch: (projectId: string, body: JsonRecord) =>
    http.post<JsonRecord>(`${base(projectId)}/save-analysis-batch`, { body, timeout: Timeout.batch }),

  /**
   * 流式生成草稿（不落盘，收完后调用方自己保存）。
   * 片段按动画帧合批后回调；后端把换行转义成字面 \n 塞进单行 data:，
   * 所以这里先反转义再回调。
   */
  streamDraft: (
    projectId: string,
    chapter: string,
    onChunk: (text: string) => void,
    signal?: AbortSignal,
  ): Promise<void> =>
    openTextStream({
      url: `/api${base(projectId)}/stream-draft?chapter=${encodeURIComponent(chapter)}`,
      onChunk: (text) => onChunk(text.replace(/\\n/g, '\n')),
      signal,
    }),
};
