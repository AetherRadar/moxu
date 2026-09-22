/**
 * 线格式类型，逐字段对应 backend-java 的实体与 DTO。
 *
 * 命名约定要当心：后端没有配置全局的 Jackson 命名策略，所以
 *   - 带 @JsonProperty 的字段是 snake_case（多数实体）
 *   - 不带注解的类整体是 camelCase（DeAiRewriteConfig、书籍导入请求）
 * 这里如实照抄，不做统一转换——省掉每个响应一次深拷贝，也免得两边对不上。
 *
 * 时间统一是 ISO-8601 字符串（Spring Boot 默认关闭 WRITE_DATES_AS_TIMESTAMPS），
 * 但不带时区后缀，形如 `2026-09-22T10:00:00`。
 */

/** 后端大量接口返回裸 Map，这是它的安全落点。 */
export type JsonRecord = Record<string, unknown>;

// ── 项目 ────────────────────────────────────────────────────────────

export interface Project {
  id: string;
  name: string;
  description: string | null;
  language: string;
  created_at: string | null;
  updated_at: string | null;
  chapter_count: number;
  total_word_count: number;
}

export interface ProjectStats {
  character_count: number;
  world_count: number;
  chapter_count: number;
  total_word_count: number;
  fact_count: number;
}

export interface ProjectCreateRequest {
  name: string;
  description?: string;
  language?: string;
}

// ── 分卷 ────────────────────────────────────────────────────────────

export interface Volume {
  id: string;
  project_id: string;
  title: string;
  summary: string | null;
  order: number;
  created_at: string | null;
  updated_at: string | null;
}

export interface VolumeCreateRequest {
  title: string;
  summary?: string;
  order?: number;
}

export interface VolumeSummary {
  volume_id: string;
  brief_summary: string;
  key_themes: string[];
  major_events: string[];
  chapter_count: number;
  created_at: string | null;
  updated_at: string | null;
}

// ── 草稿与章节 ──────────────────────────────────────────────────────

export interface Draft {
  chapter: string;
  version: string;
  content: string;
  word_count: number;
  pending_confirmations: string[];
  created_at: string | null;
}

export interface ChapterSummary {
  chapter: string;
  volume_id: string | null;
  order_index: number | null;
  title: string;
  word_count: number;
  key_events: string[];
  new_facts: string[];
  character_state_changes: string[];
  open_loops: string[];
  brief_summary: string;
}

export interface SceneBrief {
  chapter: string | null;
  title: string | null;
  goal: string | null;
  conflict: string | null;
  key_beats: string[];
  characters: Record<string, string>[];
  timeline_context: Record<string, string>;
  world_constraints: string[];
  facts: string[];
  style_reminder: string | null;
  forbidden: string[];
}

export type IssueSeverity = 'high' | 'medium' | 'low' | (string & {});

export interface Issue {
  severity: IssueSeverity;
  category: string | null;
  location: string | null;
  problem: string | null;
  suggestion: string | null;
}

export interface ReviewResult {
  chapter: string;
  draft_version: string | null;
  issues: Issue[];
  overall_assessment: string | null;
  can_proceed: boolean;
}

// ── 卡片 ────────────────────────────────────────────────────────────

export interface CharacterCard {
  name: string;
  aliases: string[];
  description: string | null;
  stars: number;
}

export interface WorldCard {
  name: string;
  description: string | null;
  aliases: string[];
  category: string | null;
  rules: string[];
  immutable: boolean | null;
  stars: number;
}

export interface StyleCard {
  style: string | null;
  representative_excerpts: string[];
  source_chapters: string[];
}

// ── 正典（事实 / 时间线 / 人物状态）────────────────────────────────

export interface Fact {
  id: string;
  title: string | null;
  statement: string | null;
  content: string | null;
  source: string | null;
  introduced_in: string | null;
  confidence: number;
  origin: string | null;
}

/** 时间线与人物状态在后端是裸 Map，字段随项目数据浮动。 */
export type TimelineEvent = JsonRecord;
export type CharacterState = JsonRecord;

// ── 会话 ────────────────────────────────────────────────────────────

export type SessionStatusValue =
  | 'idle'
  | 'generating_brief'
  | 'waiting_user_input'
  | 'writing_draft'
  | 'editing'
  | 'waiting_feedback'
  | 'analyzing'
  | 'completed'
  | 'error';

export interface StartSessionRequest {
  language?: string;
  chapter: string;
  chapter_title?: string;
  chapter_goal?: string;
  target_word_count?: number;
  character_names?: string[];
}

export interface SessionQuestion {
  type?: string;
  /** 模板问题用 text，模型生成的问题可能用 question */
  text?: string;
  question?: string;
  key?: string;
  reason?: string;
}

export interface SessionStatusResponse {
  status: SessionStatusValue | string;
  project_id?: string;
  chapter?: string;
  iteration?: number;
  message?: string;
}

/**
 * start / answer-questions / feedback 的统一响应形状：
 * 到哪一步有哪些字段取决于 status——waiting_user_input 给 questions，
 * waiting_feedback 给 draft，completed 给 final_draft。
 */
export interface SessionFlowResponse {
  success?: boolean;
  status?: SessionStatusValue | string;
  scene_brief?: SceneBrief;
  questions?: SessionQuestion[];
  question_round?: number;
  target_word_count?: number;
  draft_v1?: Draft;
  draft?: Draft;
  version?: string;
  iteration?: number;
  proposals?: JsonRecord[];
  final_draft?: Draft;
  message?: string;
  error?: string;
  context_debug?: JsonRecord;
}

export interface EditSuggestDiffLine {
  line: number;
  before: string;
  after: string;
  type: 'insert' | 'delete' | 'replace' | (string & {});
}

export interface EditSuggestResponse {
  success: boolean;
  revised_content?: string;
  word_count?: number;
  chapter?: string;
  error?: string;
  diff_preview?: EditSuggestDiffLine[];
  change_stats?: {
    original_lines: number;
    revised_lines: number;
    changed_lines: number;
  };
}

export interface AnswerQuestionsRequest {
  language?: string;
  chapter: string;
  chapter_title?: string;
  chapter_goal?: string;
  target_word_count?: number;
  character_names?: string[];
  answers: { type: string; question: string; key?: string; answer: string }[];
}

export interface FeedbackRequest {
  chapter: string;
  feedback?: string;
  action?: 'revise' | 'confirm';
  rejected_entities?: string[];
}

export interface EditSuggestRequest {
  chapter: string;
  content?: string;
  instruction: string;
  rejected_entities?: string[];
  context_mode?: 'quick' | 'full' | (string & {});
  selection_text?: string;
  selection_occurrence?: number;
  selection_start?: number;
  selection_end?: number;
}

// ── 去味改写配置（注意：这个类没有 @JsonProperty，线上是 camelCase）──

export type RewriteEngine = 'local' | 'llm';

export interface DeAiRewriteConfig {
  engine: RewriteEngine;
  intensity: number;
  beamWidth: number;
  maxPasses: number;
  colloquiality: number;
  sentenceVariety: number;
  connectiveCleanup: number;
  paragraphReshape: number;
  useProjectCorpus: boolean;
  preserveParagraphs: boolean;
  hotspotLocalEnabled: boolean;
  extraModelPassEnabled: boolean;
  extraModelPassPrompt: string;
  returnDebug: boolean;
}

// ── 书籍导入（同样是 camelCase）──────────────────────────────────────

export interface BookImportOptions {
  quickInit?: boolean;
  overwriteStyle?: boolean;
  overwriteCards?: boolean;
  autoExtractTitles?: boolean;
  maxCharacterCards?: number;
  maxWorldCards?: number;
}

export interface BookImportTextRequest extends BookImportOptions {
  sourceName?: string;
  text: string;
}

export interface BookImportChapterDraft {
  selected?: boolean;
  title?: string;
  content?: string;
  syntheticTitle?: boolean;
}

export interface BookImportVolumeDraft {
  title?: string;
  explicit?: boolean;
  chapters?: BookImportChapterDraft[];
}

export interface BookImportConfirmRequest extends BookImportOptions {
  sourceName?: string;
  format?: string;
  volumes: BookImportVolumeDraft[];
}

// preview/confirm 返回的是 Map 结构（snake_case），不是 DTO

export interface ImportPreviewChapter {
  index: number;
  title: string;
  /** 标题是解析器合成的（如「第3章」），非原文标题 */
  synthetic_title: boolean;
  selected: boolean;
  word_count: number;
  /** 全文——confirm 时要原样回传 */
  content: string;
  content_preview: string;
}

export interface ImportPreviewVolume {
  index: number;
  title: string;
  /** 原书里确实分了卷；false 表示所有章都进同一卷 */
  explicit: boolean;
  chapter_count: number;
  sample_titles: string[];
  chapters: ImportPreviewChapter[];
}

export interface BookImportPreview {
  success: boolean;
  filename: string;
  format: string;
  volume_count: number;
  chapter_count: number;
  volumes: ImportPreviewVolume[];
  warnings: string[];
}

export interface ImportedChapter {
  chapter: string;
  title: string;
  word_count: number;
}

export interface ImportedVolume {
  volume_id: string;
  title: string;
  chapter_count: number;
  chapters: ImportedChapter[];
}

export interface BookImportResult {
  success: boolean;
  filename: string;
  format: string;
  imported_volume_count: number;
  imported_chapter_count: number;
  volumes: ImportedVolume[];
  warnings: string[];
  title_extraction: {
    enabled: boolean;
    candidate_count: number;
    replaced: number;
  };
  quick_init: JsonRecord;
}

// ── 大纲 ────────────────────────────────────────────────────────────

/** master.yaml / volumes/*.yaml / chapters/*.yaml 的统一载荷。 */
export interface OutlineEntry {
  content: string | null;
  updated_at?: string | null;
}

export interface VolumeOutline extends OutlineEntry {
  volume_id: string;
  title: string;
}

export interface ChapterOutline extends OutlineEntry {
  chapter_id: string;
  volume_id: string;
  title: string;
}

export interface ProjectOutline {
  master: OutlineEntry;
  volumes: VolumeOutline[];
  chapters: ChapterOutline[];
}

/** 总纲/分卷纲候选共用形状，提示字段名不同。 */
export interface OutlineCandidate {
  route_id: string;
  label: string;
  summary: string;
  why_it_fits: string;
  focus_points: string[];
  /** 总纲是 planned_volume_count_hint，分卷纲是 chapter_count_hint */
  planned_volume_count_hint?: number;
  chapter_count_hint?: number;
  outline_content: string;
}

export interface CandidateResponse {
  success: boolean;
  recommended_route_id: string;
  candidates: OutlineCandidate[];
}

export interface PlannedChapter {
  chapter_id: string;
  chapter_no: number;
  title: string;
  summary: string;
  goal: string;
  key_beats: string[];
  ending_hook: string;
  must_not_include: string[];
  outline_content: string;
}

export interface ChapterPlanResult {
  success: boolean;
  volume_id: string;
  volume_title: string;
  chapters: PlannedChapter[];
}

export interface SplitPlanResult {
  target_chapter_id: string;
  split_required: boolean;
  recommended_split_count: number;
  split_reasons: string[];
  split_strategy_summary: string;
  chapters: PlannedChapter[];
}

export interface ApplyPlanResult {
  success: boolean;
  volume_id: string;
  created: string[];
  updated: string[];
  count: number;
  chapters: PlannedChapter[];
}

// ── 续写探索 ────────────────────────────────────────────────────────

export type ExplorationScope = 'next_chapter' | 'next_multi';
export type ExplorationStage = 'questions' | 'routes' | 'generated' | (string & {});

export interface ExplorationOption {
  id: string;
  label: string;
  description: string;
}

export interface ExplorationQuestion {
  id: string;
  title: string;
  prompt: string;
  allow_custom: boolean;
  options: ExplorationOption[];
}

export interface ExplorationDecision {
  question_id: string;
  question_title: string;
  selected_option_ids: string[];
  answer_summary: string;
  custom_note: string;
}

export interface ExplorationChapterTarget {
  chapter_id: string;
  title: string;
  summary: string;
  hook: string;
}

export interface ExplorationRoute {
  route_id: string;
  label: string;
  summary: string;
  why_it_fits: string;
  beats: string[];
  risks: string[];
  chapter_targets: ExplorationChapterTarget[];
}

export interface ExplorationGeneratedPlan {
  volume_id: string;
  volume_title: string;
  route_id: string;
  route_label: string;
  chapters: PlannedChapter[];
}

export interface ExplorationSession {
  session_id: string;
  stage: ExplorationStage;
  scope: ExplorationScope;
  volume_id: string;
  volume_title: string;
  anchor_chapter_id: string;
  anchor_chapter_title: string;
  anchor_summary: string;
  target_chapter_ids: string[];
  questions: ExplorationQuestion[];
  decisions: ExplorationDecision[];
  route_candidates: ExplorationRoute[];
  selected_route_id: string;
  generated_plan: ExplorationGeneratedPlan | null;
  updated_at: string;
}

export interface ExplorationResponse {
  success: boolean;
  session: ExplorationSession;
}

// ── LLM 配置 ────────────────────────────────────────────────────────

export interface LlmProfile extends JsonRecord {
  id?: string;
  name?: string;
  provider?: string;
  model?: string;
  base_url?: string;
  api_key?: string;
}

export type LlmAssignments = Record<string, unknown>;

export interface LlmProviderMeta extends JsonRecord {
  id?: string;
  name?: string;
  label?: string;
}

// ── 检索 ────────────────────────────────────────────────────────────

export type EvidenceItemType = 'text_chunk' | 'summary' | 'style' | 'memory' | 'world_rule' | (string & {});

export interface EvidenceItem extends JsonRecord {
  type?: EvidenceItemType;
  id?: string;
  chapter?: string;
  text?: string;
  score?: number;
}
