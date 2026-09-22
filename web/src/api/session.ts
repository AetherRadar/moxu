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

export const sessionApi = {
  archiveStatus: (projectId: string, chapter: string) =>
    http.get<ArchiveReport>(`${base(projectId)}/archive/${seg(chapter)}`),
  retryArchive: (projectId: string, chapter: string) =>
    http.post<SessionFlowResponse>(`${base(projectId)}/archive/${seg(chapter)}/retry`, { timeout: Timeout.batch }),

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
    http.post<JsonRecord>(`${base(projectId)}/analyze-batch`, { body, timeout: Timeout.batch, signal }),

  analyzeSync: (projectId: string, body: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/analyze-sync`, { body, timeout: Timeout.batch, signal }),

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
