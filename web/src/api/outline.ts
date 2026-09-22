import { http, seg, Timeout } from '@/lib/http';
import type {
  ApplyPlanResult,
  CandidateResponse,
  ChapterPlanResult,
  ExplorationResponse,
  ExplorationScope,
  JsonRecord,
  PlannedChapter,
  ProjectOutline,
  SplitPlanResult,
} from './types';

const base = (projectId: string): string => `/projects/${seg(projectId)}/outline`;

export interface OutlineGenerateRequest {
  chapter_id: string;
  instruction?: string;
  language?: string;
}

export interface OutlineCandidateRequest {
  instruction?: string;
  /** 已有内容作为生成参考；不传则后端读文件里的内容 */
  content?: string;
  language?: string;
}

export interface ExplorationStartRequest {
  scope?: ExplorationScope;
  chapter_id?: string;
  volume_id?: string;
  language?: string;
}

export interface ExplorationAnswer {
  question_id: string;
  option_ids?: string[];
  custom_note?: string;
}

/**
 * 大纲域。
 * 生成类调用全部走长超时；会话型探索接口有服务端状态（ConcurrentHashMap），
 * 页面刷新后会话会丢，属预期行为——前端把 session 当一次性流程对待。
 */
export const outlineApi = {
  get: (projectId: string) => http.get<ProjectOutline>(base(projectId)),

  saveMaster: (projectId: string, content: string) =>
    http.put<JsonRecord>(`${base(projectId)}/master`, { body: { content } }),

  saveVolume: (projectId: string, volumeId: string, content: string) =>
    http.put<JsonRecord>(`${base(projectId)}/volumes/${seg(volumeId)}`, { body: { content } }),

  saveChapter: (projectId: string, chapterId: string, content: string) =>
    http.put<JsonRecord>(`${base(projectId)}/chapters/${seg(chapterId)}`, { body: { content } }),

  // ── 以下均为 LLM 调用 ───────────────────────────────────────────

  /** 从已有正文反推章纲。 */
  reverseEngineerChapter: (projectId: string, chapterId: string, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/chapters/${seg(chapterId)}/reverse-engineer`, {
      body: {},
      timeout: Timeout.llm,
      signal,
    }),

  rewriteDraft: (projectId: string, chapterId: string, body?: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/chapters/${seg(chapterId)}/rewrite-draft`, {
      body: body ?? {},
      timeout: Timeout.llm,
      signal,
    }),

  rewriteScene: (projectId: string, chapterId: string, body?: JsonRecord, signal?: AbortSignal) =>
    http.post<JsonRecord>(`${base(projectId)}/chapters/${seg(chapterId)}/rewrite-scene-draft`, {
      body: body ?? {},
      timeout: Timeout.llm,
      signal,
    }),

  /** 按章生成大纲，instruction 可空。 */
  generate: (projectId: string, body: OutlineGenerateRequest, signal?: AbortSignal) =>
    http.post<{ chapter_id: string; content: string; success: boolean }>(`${base(projectId)}/generate`, {
      body,
      timeout: Timeout.llm,
      signal,
    }),

  generateMasterCandidates: (projectId: string, body?: OutlineCandidateRequest, signal?: AbortSignal) =>
    http.post<CandidateResponse>(`${base(projectId)}/master/generate-candidates`, {
      body: body ?? {},
      timeout: Timeout.batch,
      signal,
    }),

  generateVolumeCandidates: (
    projectId: string,
    volumeId: string,
    body?: OutlineCandidateRequest,
    signal?: AbortSignal,
  ) =>
    http.post<CandidateResponse>(`${base(projectId)}/volumes/${seg(volumeId)}/generate-candidates`, {
      body: body ?? {},
      timeout: Timeout.batch,
      signal,
    }),

  /** 把分卷纲拆成章节规划。 */
  planChapters: (
    projectId: string,
    volumeId: string,
    content?: string,
    signal?: AbortSignal,
    range?: { start_chapter: number; chapter_count: number },
  ) =>
    http.post<ChapterPlanResult>(`${base(projectId)}/volumes/${seg(volumeId)}/plan-chapters`, {
      body: { ...(content === undefined ? {} : { content }), ...range },
      timeout: Timeout.batch,
      signal,
    }),

  /** 对规划中的某一章做二次拆分预览。instruction 必填（告诉模型怎么拆）。 */
  splitPlannedChapter: (
    projectId: string,
    volumeId: string,
    body: { instruction: string; chapter_id?: string; split_count?: number; chapter?: PlannedChapter },
    signal?: AbortSignal,
  ) =>
    http.post<SplitPlanResult>(`${base(projectId)}/volumes/${seg(volumeId)}/split-planned-chapter`, {
      body,
      timeout: Timeout.llm,
      signal,
    }),

  /** 应用章节规划：建章节、写章纲、登记摘要。 */
  applyChapterPlan: (projectId: string, volumeId: string, chapters: PlannedChapter[]) =>
    http.post<ApplyPlanResult>(`${base(projectId)}/volumes/${seg(volumeId)}/apply-chapter-plan`, {
      body: { chapters },
      timeout: Timeout.llm,
    }),

  // ── 续写探索（服务端有 session 状态）─────────────────────────────

  startExploration: (projectId: string, body?: ExplorationStartRequest, signal?: AbortSignal) =>
    http.post<ExplorationResponse>(`${base(projectId)}/exploration/start`, {
      body: body ?? {},
      timeout: Timeout.llm,
      signal,
    }),

  answerExploration: (
    projectId: string,
    sessionId: string,
    answers: ExplorationAnswer[],
    signal?: AbortSignal,
  ) =>
    http.post<ExplorationResponse>(`${base(projectId)}/exploration/answer`, {
      body: { session_id: sessionId, answers },
      timeout: Timeout.llm,
      signal,
    }),

  selectExplorationRoute: (projectId: string, sessionId: string, routeId: string, signal?: AbortSignal) =>
    http.post<ExplorationResponse>(`${base(projectId)}/exploration/select-route`, {
      body: { session_id: sessionId, route_id: routeId },
      timeout: Timeout.llm,
      signal,
    }),
};
