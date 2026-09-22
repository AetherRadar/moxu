package com.wenshape.orchestrator;

import com.wenshape.agent.ArchivistAgent;
import com.wenshape.agent.EditorAgent;
import com.wenshape.agent.WriterAgent;
import com.wenshape.controller.BindingsController;
import com.wenshape.context.BudgetManager;
import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.nlp.AdaptiveHintProfile;
import com.wenshape.nlp.AdaptiveHintResolver;
import com.wenshape.nlp.HanlpLexiconSupport;
import com.wenshape.proposal.ProposalPostProcessor;
import com.wenshape.search.TextSearchUtils;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import com.wenshape.websocket.SessionWebSocketHandler;
import com.wenshape.websocket.TraceWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排器 - 协调多智能体写作工作流
 */
@Slf4j
public class Orchestrator {
    
    private final LlmGateway llmGateway;
    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    private final CanonStorage canonStorage;
    private final MemoryPackStorage memoryPackStorage;
    private final WriterAgent writerAgent;
    private final ArchivistAgent archivistAgent;
    private final EditorAgent editorAgent;
    private final ContextSelectEngine selectEngine;
    private final SessionWebSocketHandler webSocketHandler;
    private final TraceWebSocketHandler traceWebSocketHandler;
    private final BindingsController bindingsController;
    private final AdaptiveHintResolver adaptiveHintResolver;
    private final ChapterAnalysisExecutor analysisExecutor;
    
    private SessionStatus currentStatus = SessionStatus.IDLE;
    private String currentProjectId;
    private String currentChapter;
    private int iterationCount = 0;
    private int questionRound = 0;
    
    private static final int MAX_ITERATIONS = 5;
    private static final int MAX_QUESTION_ROUNDS = 2;
    private static final int MAX_RESEARCH_ROUNDS = 5;
    private static final int MAX_DETECTED_PROPOSALS = 8;
    private static final double MIN_GAP_SUPPORT_SCORE = 3.0;
    private static final int GOAL_BEAT_LIMIT = 5;
    private static final int GOAL_QUERY_LIMIT = 4;
    private static final int RERANK_MAX_TOKENS = 256;
    private static final Set<String> ANSWER_SKIP_RETRIEVAL_KINDS = Set.of("character_change");

    private static final Set<String> GOAL_META_PREFIXES = Set.of(
            "总纲", "分卷纲", "章节细纲", "章节结构", "章节结构大纲", "写作任务", "硬约束", "可用素材",
            "未解决缺口", "核心设定", "核心设定沉淀", "剧情推演", "分卷", "世界背景", "当前阶段",
            "事件推进", "收束与钩子", "冷开场", "当前=", "before=", "after=", "time=", "current=",
            "核心出场人物", "真实生理反馈", "能力", "身份", "外在形象", "真实逻辑", "行为逻辑"
    );
    private static final Set<String> META_ENTITY_STOPWORDS = Set.of(
            "总纲", "分卷纲", "章节细纲", "章节结构", "写作任务", "硬约束", "可用素材", "未解决缺口",
            "核心设定", "世界观", "章节目标", "角色", "设定", "事实", "摘要", "正文", "文风", "编辑",
            "主笔", "当前", "上一章", "时间线", "current", "before", "after"
    );
    private static final Set<String> GOAL_BEAT_HINTS = Set.of(
            "展示", "建立", "引出", "铺垫", "收束", "推进", "暴露", "埋下", "点出", "交代",
            "呈现", "触发", "钩子", "出场", "异动", "传闻", "代价", "风险", "压迫", "日常",
            "冲突", "悬念", "风向", "埋线", "揭示"
    );
    private static final List<String> SUMMARY_NOVEL_FACT_HINTS = List.of(
            "入口", "遗迹", "线索", "证据", "记录", "异常", "反应", "纹路", "图谱", "传承", "资源"
    );
    private static final List<String> SUMMARY_CARRYOVER_FACT_HINTS = List.of(
            "遗失", "见底", "匮乏", "受困", "补给", "伤势", "资源"
    );
    private static final Set<String> GENERIC_QUERY_FALLBACK_TERMS = Set.of(
            "来源", "代价", "机制", "影响", "联系", "入口", "线索", "证据", "异常", "反应",
            "异响", "异常光源", "异常气机", "伤势变化", "关键物遗失", "关键物反应",
            "未知危险", "阵法禁制", "壁刻图谱", "传承线索"
    );
    private static final List<String> QUERY_COMPOUND_ANCHORS = List.of(
            "异响", "拖擦声", "脚步声", "石面", "石缝", "石门", "入口",
            "微光", "青光", "白光", "阵纹", "阵法", "禁制", "壁刻", "修炼图", "人形图", "图谱",
            "传承", "功法", "气机", "灵气", "气息", "雾气", "法器",
            "木牌", "玉佩", "令牌", "钥匙", "异动", "轻响", "发热", "内伤", "经脉"
    );
    private static final Set<String> QUERY_COMPOUND_TOKEN_STOPWORDS = Set.of(
            "的", "了", "着", "地", "得", "和", "与", "及", "或", "而", "但",
            "于", "把", "被", "从", "向", "对", "给", "将", "让",
            "里", "中", "上", "下", "前", "后", "来", "去", "内", "外",
            "是", "有", "又", "再", "仍", "还", "更", "都", "也"
    );
    private static final Pattern SYNTHETIC_CHAPTER_TITLE_PATTERN = Pattern.compile("^V\\d+C\\d+$", Pattern.CASE_INSENSITIVE);
    
    // 缓存最近的流式结果
    private final Map<String, Map<String, Object>> lastStreamResults = new ConcurrentHashMap<>();
    
    public Orchestrator(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                        CanonStorage canonStorage, MemoryPackStorage memoryPackStorage,
                        WriterAgent writerAgent, ArchivistAgent archivistAgent, EditorAgent editorAgent,
                        ContextSelectEngine selectEngine, SessionWebSocketHandler webSocketHandler,
                        TraceWebSocketHandler traceWebSocketHandler,
                        BindingsController bindingsController) {
        this(llmGateway, cardStorage, draftStorage, canonStorage, memoryPackStorage,
                writerAgent, archivistAgent, editorAgent, selectEngine, webSocketHandler,
                traceWebSocketHandler, bindingsController, null);
    }

    public Orchestrator(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                        CanonStorage canonStorage, MemoryPackStorage memoryPackStorage,
                        WriterAgent writerAgent, ArchivistAgent archivistAgent, EditorAgent editorAgent,
                        ContextSelectEngine selectEngine, SessionWebSocketHandler webSocketHandler,
                        TraceWebSocketHandler traceWebSocketHandler,
                        BindingsController bindingsController, ChapterAnalysisExecutor analysisExecutor) {
        this.llmGateway = llmGateway;
        this.cardStorage = cardStorage;
        this.draftStorage = draftStorage;
        this.canonStorage = canonStorage;
        this.memoryPackStorage = memoryPackStorage;
        this.writerAgent = writerAgent;
        this.archivistAgent = archivistAgent;
        this.editorAgent = editorAgent;
        this.selectEngine = selectEngine;
        this.webSocketHandler = webSocketHandler;
        this.traceWebSocketHandler = traceWebSocketHandler;
        this.bindingsController = bindingsController;
        this.adaptiveHintResolver = new AdaptiveHintResolver(cardStorage, draftStorage, canonStorage);
        this.analysisExecutor = analysisExecutor;
    }

    public void setLanguage(String language) {
        writerAgent.setLanguage(language);
        archivistAgent.setLanguage(language);
        editorAgent.setLanguage(language);
    }

    private String buildTimingTraceId(String prefix, String chapter) {
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "trace" : prefix.trim();
        String normalizedChapter = chapter == null || chapter.isBlank() ? "unknown" : chapter.trim();
        return normalizedPrefix + ":" + normalizedChapter + ":" + Long.toHexString(System.nanoTime());
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private String formatTimingMap(Map<String, Long> timings) {
        if (timings == null || timings.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue() + "ms");
        }
        return joiner.toString();
    }

    private String truncateForLog(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        if (maxLen <= 3) {
            return cleaned.substring(0, Math.max(0, maxLen));
        }
        return cleaned.substring(0, maxLen - 3) + "...";
    }
    
    /**
     * 开始写作会话
     * 流程：档案员生成场景简报 → 返回 waiting_for_input，等待用户确认后再写作
     */
    public Map<String, Object> startSession(String projectId, String chapter, String chapterTitle,
                                             String chapterGoal, int targetWordCount, List<String> characterNames) {
        this.currentProjectId = projectId;
        this.currentChapter = chapter;
        this.iterationCount = 0;
        this.questionRound = 0;
        String normalizedChapterGoal = normalizeChapterGoalForCurrentChapter(chapter, chapterGoal);

        try {
            // 步骤1: 档案员生成场景简报
            updateStatus(SessionStatus.GENERATING_BRIEF, "Archivist is preparing the scene brief...");

            Map<String, Object> archivistContext = new HashMap<>();
            archivistContext.put("chapter_title", chapterTitle);
            archivistContext.put("chapter_goal", normalizedChapterGoal);
            archivistContext.put("characters", characterNames != null ? characterNames : List.of());

            Map<String, Object> archivistResult = archivistAgent.execute(projectId, chapter, archivistContext);
            broadcastAgentTrace("archivist", Map.of(
                    "status", Boolean.TRUE.equals(archivistResult.get("success")) ? "completed" : "failed",
                    "stage", "scene_brief",
                    "has_scene_brief", archivistResult.get("scene_brief") != null
            ));

            SceneBrief sceneBrief;
            if (Boolean.TRUE.equals(archivistResult.get("success"))) {
                sceneBrief = (SceneBrief) archivistResult.get("scene_brief");
            } else {
                sceneBrief = buildFallbackSceneBrief(chapter, chapterTitle, normalizedChapterGoal, characterNames);
            }

            // 保存场景简报（供后续 answerQuestions 使用）
            draftStorage.saveSceneBrief(projectId, chapter, sceneBrief);

            // 广播 scene_brief 完成事件（对齐 Python _emit_progress）
            broadcastProgress(projectId, Map.of(
                    "type", "scene_brief",
                    "status", "research",
                    "message", "场景简报已生成",
                    "project_id", projectId,
                    "chapter", chapter
            ));

            // 研究循环进度广播（对齐 Python _run_research_loop 的 _emit_progress 调用点）
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "read_previous",
                    "message", "正在阅读前文...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 0
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "read_facts",
                    "message", "正在阅读相关事实摘要...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 0
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "prepare_retrieval",
                    "message", "正在思考...（第1轮）",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "lookup_cards",
                    "message", "正在查询相关设定...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "execute_retrieval",
                    "message", "正在检索...（第1轮）",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));

            String effectiveGoal = (normalizedChapterGoal != null && !normalizedChapterGoal.isBlank())
                    ? normalizedChapterGoal : sceneBrief.getGoal();
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, effectiveGoal, sceneBrief, characterNames, List.of());
            persistMemoryPack(projectId, chapter, writerContext, "writer_research");
            Map<String, Object> contextDebug = buildContextDebug(projectId, chapter);

            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "memory_pack",
                    "message", "记忆包已更新",
                    "project_id", projectId,
                    "chapter", chapter
            ));

            Object plannedQueries = writerContext.get("planned_queries");
            if (plannedQueries instanceof List<?> queries && !queries.isEmpty()) {
                broadcastProgress(projectId, Map.of(
                        "status", "research",
                        "stage", "generate_plan",
                        "message", "研究计划已生成",
                        "project_id", projectId,
                        "chapter", chapter,
                        "round", 1,
                        "queries", queries
                ));
            }

            String stopReason = String.valueOf(writerContext.getOrDefault("research_stop_reason", ""));
            if (!stopReason.isBlank()) {
                String note = switch (stopReason) {
                    case "sufficient" -> "证据充分，准备结束研究";
                    case "no_queries" -> "研究计划为空，停止检索";
                    default -> "达到最大轮次，进入反问或待确认";
                };
                broadcastProgress(projectId, Map.of(
                        "status", "research",
                        "stage", "self_check",
                        "message", note,
                        "project_id", projectId,
                        "chapter", chapter,
                        "round", 1,
                        "stop_reason", stopReason
                ));
            }

            List<Map<String, String>> questions = resolvePreWritingQuestions(
                    projectId,
                    chapter,
                    effectiveGoal,
                    sceneBrief,
                    writerContext,
                    List.of(),
                    shouldAskFallbackQuestions(writerContext)
            );

            // 对齐 Python 版：questions 非空才等待用户输入，否则直接写作
            if (questions != null && !questions.isEmpty() && questionRound < MAX_QUESTION_ROUNDS) {
                questionRound += 1;
                updateStatus(SessionStatus.WAITING_USER_INPUT, "Waiting for user input...");
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("status", SessionStatus.WAITING_USER_INPUT.getValue());
                response.put("scene_brief", sceneBrief);
                response.put("questions", questions);
                response.put("question_round", questionRound);
                response.put("target_word_count", targetWordCount);
                response.put("context_debug", contextDebug);
                return response;
            }

            // 证据充足，直接进入写作
            log.info("No questions needed, proceeding to write directly");
            Map<String, Object> result = runWritingFlow(projectId, chapter, writerContext, targetWordCount, sceneBrief);
            result.put("context_debug", contextDebug);
            return result;

        } catch (Exception e) {
            log.error("Session error", e);
            return handleError("Session error: " + e.getMessage());
        }
    }

    /**
     * 用户确认场景简报后，触发写作
     * 对应 Python 版 answer_questions（无问题时直接写作）
     */
    public Map<String, Object> proceedToWrite(String projectId, String chapter, String chapterGoal,
                                               int targetWordCount, List<String> characterNames,
                                               List<Map<String, String>> answers) {
        this.currentProjectId = projectId;
        this.currentChapter = chapter;
        String normalizedChapterGoal = normalizeChapterGoalForCurrentChapter(chapter, chapterGoal);

        try {
            // 从存储加载已有场景简报
            Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
            SceneBrief sceneBrief = sceneBriefOpt.orElse(null);

            if (sceneBrief == null) {
                return handleError("Scene brief not found. Please start a new session first.");
            }

            // 准备写作上下文
            String effectiveGoal = (normalizedChapterGoal != null && !normalizedChapterGoal.isBlank())
                    ? normalizedChapterGoal : sceneBrief.getGoal();
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, effectiveGoal, sceneBrief, characterNames, answers);
            persistMemoryPack(projectId, chapter, writerContext, "writer_answers");
            Map<String, Object> contextDebug = buildContextDebug(projectId, chapter);

            List<Map<String, String>> followupQuestions = resolvePreWritingQuestions(
                    projectId,
                    chapter,
                    effectiveGoal,
                    sceneBrief,
                    writerContext,
                    answers,
                    shouldAskFollowupQuestions(writerContext)
            );
            Set<String> answeredKeys = extractAnsweredQuestionKeys(answers);
            Set<String> answeredSignatures = extractAnsweredQuestionSignatures(answers);
            if ((!answeredKeys.isEmpty() || !answeredSignatures.isEmpty())
                    && followupQuestions != null
                    && !followupQuestions.isEmpty()) {
                List<Map<String, String>> filteredQuestions = new ArrayList<>();
                for (Map<String, String> question : followupQuestions) {
                    if (question == null) {
                        continue;
                    }
                    String key = question.getOrDefault("key", "");
                    String signature = questionSignature(
                            question.getOrDefault("type", ""),
                            question.getOrDefault("text", "")
                    );
                    if (!answeredKeys.contains(key) && !answeredSignatures.contains(signature)) {
                        filteredQuestions.add(question);
                    }
                }
                followupQuestions = filteredQuestions;
            }
            if (followupQuestions != null
                    && !followupQuestions.isEmpty()
                    && answers != null
                    && !answers.isEmpty()
                    && shouldAskFollowupQuestions(writerContext)
                    && questionRound < MAX_QUESTION_ROUNDS) {
                questionRound += 1;
                updateStatus(SessionStatus.WAITING_USER_INPUT, "Waiting for user input...");
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("status", SessionStatus.WAITING_USER_INPUT.getValue());
                response.put("scene_brief", sceneBrief);
                response.put("questions", followupQuestions);
                response.put("question_round", questionRound);
                response.put("target_word_count", targetWordCount);
                response.put("context_debug", contextDebug);
                return response;
            }

            // 执行写作流程
            Map<String, Object> result = runWritingFlow(projectId, chapter, writerContext, targetWordCount, sceneBrief);
            result.put("context_debug", contextDebug);
            return result;

        } catch (Exception e) {
            log.error("proceedToWrite error", e);
            return handleError("Write error: " + e.getMessage());
        }
    }
    
    /**
     * 流式生成草稿
     */
    public Flux<String> streamDraft(String projectId, String chapter, Map<String, Object> context) {
        updateStatus(SessionStatus.WRITING_DRAFT, "Writer is drafting...");
        persistMemoryPack(projectId, chapter, context, "writer_stream");
        
        return writerAgent.executeStreamDraft(projectId, chapter, context)
                .doOnNext(chunk -> {
                    // 通过 WebSocket 推送
                    broadcastProgress(projectId, Map.of(
                            "type", "draft_chunk",
                            "chunk", chunk,
                            "chapter", chapter
                    ));
                })
                .doOnComplete(() -> {
                    updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");
                })
                .doOnError(e -> {
                    log.error("Stream draft error", e);
                    updateStatus(SessionStatus.ERROR, "Draft generation failed: " + e.getMessage());
                });
    }

    /**
     * 准备完整上下文并流式生成草稿（供 SSE 接口使用）
     */
    public Flux<String> prepareAndStreamDraft(String projectId, String chapter) {
        try {
            Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
            if (sceneBriefOpt.isEmpty()) {
                return Flux.just("[Error: Scene brief not found. Please start a session first.]");
            }
            SceneBrief sceneBrief = sceneBriefOpt.get();
            String goal = sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            Map<String, Object> context = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
            return streamDraft(projectId, chapter, context);
        } catch (Exception e) {
            log.error("prepareAndStreamDraft error", e);
            return Flux.just("[Error: " + e.getMessage() + "]");
        }
    }
    
    /**
     * 处理用户反馈
     */
    public Map<String, Object> processFeedback(String projectId, String chapter, String feedback,
                                                String action, List<String> rejectedEntities) {
        if ("confirm".equals(action)) {
            return finalizeChapter(projectId, chapter);
        }
        
        iterationCount++;
        if (iterationCount >= MAX_ITERATIONS) {
            return Map.of(
                    "success", false,
                    "error", "Maximum iterations reached",
                    "message", "Maximum revision iterations reached."
            );
        }
        
        try {
            // 获取最新草稿
            List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
            String latestVersion = versions.isEmpty() ? "v1" : versions.get(versions.size() - 1);
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
            
            if (draftOpt.isEmpty()) {
                return handleError("No draft found");
            }
            
            Draft latestDraft = draftOpt.get();
            int draftLength = latestDraft.getContent() != null ? latestDraft.getContent().length() : 0;
            
            if (draftLength <= 500) {
                // 短草稿：Writer 重写（对齐 Python process_feedback 短草稿分支）
                updateStatus(SessionStatus.WRITING_DRAFT, "Writer is refining based on feedback...");

                Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
                if (sceneBriefOpt.isEmpty()) {
                    return handleError("Scene brief not found for rewrite");
                }
                SceneBrief sceneBrief = sceneBriefOpt.get();
                String goal = sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
                Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
                writerContext.put("userFeedback", feedback);
                persistMemoryPack(projectId, chapter, writerContext, "writer_feedback");

                Map<String, Object> result = writerAgent.execute(projectId, chapter, writerContext);
                broadcastAgentTrace("writer", Map.of(
                        "status", Boolean.TRUE.equals(result.get("success")) ? "completed" : "failed",
                        "stage", "rewrite",
                        "iteration", iterationCount,
                        "context_stats", buildContextStats(writerContext)
                ));
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    return handleError("Rewrite failed");
                }

                updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");
                Draft rewriteDraft = (Draft) result.get("draft");
                String rewriteContent = rewriteDraft != null && rewriteDraft.getContent() != null ? rewriteDraft.getContent() : "";
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
                response.put("draft", rewriteDraft);
                response.put("version", rewriteDraft != null ? rewriteDraft.getVersion() : "v1");
                response.put("iteration", iterationCount);
                response.put("proposals", detectProposals(projectId, rewriteContent));
                saveDraftReview(projectId, chapter, rewriteDraft);
                return response;
            }
            
            // 长草稿：调用 EditorAgent 精准修订（对齐 Python process_feedback）
            updateStatus(SessionStatus.EDITING, "Revising based on feedback...");

            Map<String, Object> editorContext = new HashMap<>();
            editorContext.put("draft_version", latestVersion);
            editorContext.put("user_feedback", feedback);
            editorContext.put("rejected_entities", rejectedEntities != null ? rejectedEntities : List.of());
            Map<String, Object> cachedPack = memoryPackStorage.readPack(projectId, chapter);
            editorContext.put("memory_pack", cachedPack != null ? cachedPack : buildMemoryPackPayload(projectId, chapter));

            Map<String, Object> result = editorAgent.execute(projectId, chapter, editorContext);
            broadcastAgentTrace("editor", Map.of(
                    "status", Boolean.TRUE.equals(result.get("success")) ? "completed" : "failed",
                    "stage", "revise",
                    "iteration", iterationCount,
                    "version", latestVersion != null ? latestVersion : "",
                    "context_stats", buildContextStats(editorContext)
            ));
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return handleError("Revision failed: " + result.getOrDefault("error", "unknown"));
            }

            updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");

            Draft editedDraft = (Draft) result.get("draft");
            String editedContent = editedDraft != null && editedDraft.getContent() != null ? editedDraft.getContent() : "";
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
            response.put("draft", editedDraft);
            response.put("version", result.getOrDefault("version", latestVersion));
            response.put("iteration", iterationCount);
            response.put("proposals", detectProposals(projectId, editedContent));
            saveDraftReview(projectId, chapter, editedDraft);
            return response;
            
        } catch (Exception e) {
            log.error("Feedback processing error", e);
            return handleError("Feedback processing error: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前状态
     */
    public Map<String, Object> getStatus() {
        return Map.of(
                "status", currentStatus.getValue(),
                "project_id", currentProjectId != null ? currentProjectId : "",
                "chapter", currentChapter != null ? currentChapter : "",
                "iteration", iterationCount
        );
    }

    public void refreshDraftArtifacts(String projectId, String chapter) {
        try {
            Optional<Draft> draftOpt = draftStorage.getLatestDraft(projectId, chapter);
            if (draftOpt.isEmpty()) {
                return;
            }
            Draft draft = draftOpt.get();
            saveDraftReview(projectId, chapter, draft);
            SceneBrief sceneBrief = draftStorage.getSceneBrief(projectId, chapter).orElse(null);
            String goal = sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
            persistMemoryPack(projectId, chapter, writerContext, "draft_save");
        } catch (Exception e) {
            log.warn("refreshDraftArtifacts 失败: {}", e.getMessage());
        }
    }
    
    /**
     * 取消会话
     */
    public Map<String, Object> cancelSession(String projectId) {
        currentStatus = SessionStatus.IDLE;
        currentProjectId = null;
        currentChapter = null;
        
        broadcastProgress(projectId, Map.of(
                "status", SessionStatus.IDLE.getValue(),
                "message", "Session cancelled",
                "project_id", projectId
        ));
        
        return Map.of("success", true, "message", "Session cancelled");
    }
    
    // ========== 私有方法 ==========
    
    private SceneBrief buildFallbackSceneBrief(String chapter, String chapterTitle, 
                                                String chapterGoal, List<String> characterNames) {
        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(chapterTitle);
        brief.setGoal(chapterGoal);
        
        if (characterNames != null && !characterNames.isEmpty()) {
            List<Map<String, String>> characters = new ArrayList<>();
            for (String name : characterNames) {
                characters.add(Map.of("name", name));
            }
            brief.setCharacters(characters);
        }
        
        return brief;
    }
    
    private Map<String, Object> prepareWriterContext(String projectId, String chapter, String chapterGoal,
                                                      SceneBrief sceneBrief, List<String> characterNames,
                                                      List<Map<String, String>> userAnswers) {
        return prepareWriterContext(projectId, chapter, chapterGoal, sceneBrief, characterNames, userAnswers, "");
    }

    private Map<String, Object> prepareWriterContext(String projectId, String chapter, String chapterGoal,
                                                     SceneBrief sceneBrief, List<String> characterNames,
                                                     List<Map<String, String>> userAnswers,
                                                     String traceId) {
        Map<String, Object> context = new HashMap<>();
        boolean traceTiming = traceId != null && !traceId.isBlank();
        long totalStartedAt = System.nanoTime();
        Map<String, Long> stageTimings = traceTiming ? new LinkedHashMap<>() : null;
        String compactGoal = compactChapterGoal(chapterGoal, sceneBrief);
        SceneBrief compactSceneBrief = compactSceneBrief(sceneBrief, compactGoal);
        context.put("sceneBrief", compactSceneBrief);
        context.put("chapterGoal", compactGoal);
        context.put("project_id", projectId);
        if (userAnswers != null && !userAnswers.isEmpty()) {
            context.put("user_answers", userAnswers);
        }

        // 加载文风卡
        long stageStartedAt = System.nanoTime();
        cardStorage.getStyleCard(projectId).ifPresent(style -> context.put("styleCard", style));
        if (traceTiming) {
            stageTimings.put("style_card", elapsedMillis(stageStartedAt));
        }

        stageStartedAt = System.nanoTime();
        context.putAll(buildWriterResearchPayload(projectId, chapter, compactGoal, compactSceneBrief, characterNames, userAnswers, traceId));
        if (traceTiming) {
            stageTimings.put("writer_research_payload", elapsedMillis(stageStartedAt));
        }

        // 加载前文摘要（最近3章）
        stageStartedAt = System.nanoTime();
        try {
            List<String> previousSummaries = draftStorage.listChapterSummaries(projectId, null)
                    .stream()
                    .filter(s -> compareChapterIds(s.getChapter(), chapter) < 0)
                    .sorted((a, b) -> compareChapterIds(a.getChapter(), b.getChapter()))
                    .limit(3)
                    .map(s -> {
                        StringBuilder sb = new StringBuilder(s.getChapter());
                        if (s.getTitle() != null && !s.getTitle().isBlank())
                            sb.append(": ").append(s.getTitle());
                        if (s.getBriefSummary() != null && !s.getBriefSummary().isBlank())
                            sb.append("\n摘要：").append(s.getBriefSummary());
                        return sb.toString();
                    })
                    .collect(java.util.stream.Collectors.toList());
            context.put("previousSummaries", previousSummaries);
        } catch (Exception e) {
            log.warn("加载前文摘要失败: {}", e.getMessage());
        } finally {
            if (traceTiming) {
                stageTimings.put("previous_summaries", elapsedMillis(stageStartedAt));
            }
        }

        if (Objects.equals(currentProjectId, projectId) && Objects.equals(currentChapter, chapter)) {
            broadcastContextStats(context);
        }

        if (traceTiming) {
            stageTimings.put("total", elapsedMillis(totalStartedAt));
            log.info("prepareWriterContext timing: trace={}, project={}, chapter={}, timings={}",
                    traceId,
                    projectId,
                    chapter,
                    formatTimingMap(stageTimings));
        }

        return context;
    }
    
    private Map<String, Object> runWritingFlow(String projectId, String chapter,
                                                Map<String, Object> writerContext, int targetWordCount,
                                                SceneBrief sceneBrief) {
        updateStatus(SessionStatus.WRITING_DRAFT, "Writer is drafting...");
        persistMemoryPack(projectId, chapter, writerContext, "writer");

        // 对齐 Python _stream_writer_output 里的 _emit_progress("正在撰写...")
        broadcastProgress(projectId, Map.of(
                "status", "writing",
                "stage", "writing",
                "message", "正在撰写...",
                "project_id", projectId,
                "chapter", chapter
        ));
        
        writerContext.put("targetWordCount", targetWordCount);
        
        // 同步生成草稿
        Map<String, Object> result = writerAgent.execute(projectId, chapter, writerContext);
        broadcastAgentTrace("writer", Map.of(
                "status", Boolean.TRUE.equals(result.get("success")) ? "completed" : "failed",
                "stage", "draft",
                "iteration", iterationCount,
                "target_word_count", targetWordCount,
                "context_stats", buildContextStats(writerContext)
        ));
        
        if (!Boolean.TRUE.equals(result.get("success"))) {
            return handleError("Draft generation failed");
        }
        
        updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");

        Draft draft = (Draft) result.get("draft");
        String draftContent = draft != null && draft.getContent() != null ? draft.getContent() : "";
        List<Map<String, Object>> proposals = detectProposals(projectId, draftContent);
        saveDraftReview(projectId, chapter, draft);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
        response.put("draft_v1", draft);
        response.put("scene_brief", sceneBrief);
        response.put("iteration", iterationCount);
        response.put("proposals", proposals);
        
        return response;
    }
    
    private Map<String, Object> finalizeChapter(String projectId, String chapter) {
        try {
            List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
            if (versions.isEmpty()) {
                return handleError("No draft found to finalize");
            }
            
            String latestVersion = versions.get(versions.size() - 1);
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
            
            if (draftOpt.isEmpty()) {
                return handleError("No draft content found to finalize");
            }
            
            Draft draft = draftOpt.get();
            draftStorage.saveFinalDraft(projectId, chapter, draft.getContent());

            // 对齐 Python _finalize_chapter：确认后自动触发分析（摘要 + 事实表更新）
            analyzeContent(projectId, chapter, draft.getContent());
            
            updateStatus(SessionStatus.COMPLETED, "Chapter completed.");
            
            return Map.of(
                    "success", true,
                    "status", SessionStatus.COMPLETED.getValue(),
                    "message", "Chapter finalized successfully",
                    "final_draft", draft
            );
            
        } catch (Exception e) {
            log.error("Finalization error", e);
            return handleError("Finalization error: " + e.getMessage());
        }
    }

    /**
     * 确认草稿后自动分析（对齐 Python _analyze_content）
     * 生成章节摘要 + 分卷摘要 + 提取事实表更新 + 冲突检测，静默执行（失败不影响主流程）
     */
    private void analyzeContent(String projectId, String chapter, String content) {
        // 对齐 Python _normalize_chapter_id
        String normalizedChapter = normalizeChapterId(chapter);
        ChapterSummary summary = null;
        String chapterTitle = resolvePreferredChapterTitle(projectId, normalizedChapter, null);
        String volumeId = extractVolumeId(normalizedChapter);

        try {
            // 生成章节摘要
            summary = archivistAgent.generateChapterSummary(projectId, normalizedChapter, chapterTitle, content);
            summary.setChapter(normalizedChapter);
            summary.setWordCount(content.length());
            if (summary.getVolumeId() == null || summary.getVolumeId().isBlank()) {
                summary.setVolumeId(volumeId);
            }
            normalizeChapterSummaryTitle(projectId, normalizedChapter, summary, chapterTitle);
        } catch (Exception e) {
            log.warn("analyzeContent: 生成摘要失败: {}", e.getMessage());
            summary = ChapterSummary.builder()
                    .chapter(normalizedChapter)
                    .title(chapterTitle)
                    .briefSummary("")
                    .wordCount(content != null ? content.length() : 0)
                    .volumeId(volumeId)
                    .keyEvents(new ArrayList<>())
                    .newFacts(new ArrayList<>())
                    .characterStateChanges(new ArrayList<>())
                    .openLoops(new ArrayList<>())
                    .build();
            normalizeChapterSummaryTitle(projectId, normalizedChapter, summary, chapterTitle);
        }

        List<Map<String, Object>> savedFacts = new ArrayList<>();
        List<Map<String, Object>> savedTimeline = new ArrayList<>();
        List<Map<String, Object>> savedStates = new ArrayList<>();
        Map<String, Object> canonUpdates = Map.of(
                "facts", List.of(),
                "timeline_events", List.of(),
                "character_states", List.of()
        );

        try {
            // 提取事实表更新
            canonUpdates = archivistAgent.extractCanonUpdates(projectId, normalizedChapter, content);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) canonUpdates.getOrDefault("facts", List.of());
            for (Map<String, Object> factData : facts) {
                String stmt = (String) factData.getOrDefault("statement", "");
                if (stmt.isBlank()) continue;
                try {
                    com.wenshape.model.entity.Fact fact = com.wenshape.model.entity.Fact.builder()
                            .statement(stmt).content(stmt).source(normalizedChapter).introducedIn(normalizedChapter)
                            .confidence(((Number) factData.getOrDefault("confidence", 0.9)).doubleValue())
                            .build();
                    canonStorage.saveFact(projectId, fact);
                    savedFacts.add(factData);
                } catch (Exception ex) {
                    log.warn("analyzeContent: 保存 fact 失败: {}", ex.getMessage());
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) canonUpdates.getOrDefault("timeline_events", List.of());
            for (Map<String, Object> event : timelineEvents) {
                try {
                    canonStorage.saveTimelineEvent(projectId, new java.util.LinkedHashMap<>(event));
                    savedTimeline.add(event);
                } catch (Exception ex) { log.warn("analyzeContent: 保存 timeline_event 失败: {}", ex.getMessage()); }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> characterStates = (List<Map<String, Object>>) canonUpdates.getOrDefault("character_states", List.of());
            for (Map<String, Object> state : characterStates) {
                try {
                    canonStorage.saveCharacterState(projectId, new java.util.LinkedHashMap<>(state));
                    savedStates.add(state);
                } catch (Exception ex) { log.warn("analyzeContent: 保存 character_state 失败: {}", ex.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("analyzeContent: 提取事实表失败: {}", e.getMessage());
        }

        try {
            enrichChapterSummary(projectId, summary, content, canonUpdates);
            draftStorage.saveChapterSummary(projectId, summary);

            // 生成分卷摘要（对齐 Python _analyze_content 里的 generate_volume_summary）
            try {
                List<ChapterSummary> volumeSummaries = draftStorage.listChapterSummaries(projectId, volumeId);
                Map<String, Object> volumeSummary = archivistAgent.generateVolumeSummary(projectId, volumeId, volumeSummaries);
                draftStorage.saveVolumeSummary(projectId, volumeSummary);
            } catch (Exception ex) {
                log.warn("analyzeContent: 生成分卷摘要失败: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("analyzeContent: 保存增强摘要失败: {}", e.getMessage());
        }

        // 冲突检测（对齐 Python _analyze_content 里的 detect_conflicts）
        try {
            Map<String, Object> conflictReport = canonStorage.detectConflicts(projectId, normalizedChapter, savedFacts, savedTimeline, savedStates);
            draftStorage.saveConflictReport(projectId, normalizedChapter, conflictReport);
        } catch (Exception e) {
            log.warn("analyzeContent: 冲突检测失败: {}", e.getMessage());
        }

        // 保存 memory pack（对齐 Python _prepare_memory_pack_payload 的持久化）
        try {
            memoryPackStorage.writePack(projectId, normalizedChapter, buildMemoryPackPayload(projectId, normalizedChapter));
        } catch (Exception e) {
            log.warn("analyzeContent: 保存 memory pack 失败: {}", e.getMessage());
        }
    }

    /**
     * 规范化章节 ID（对齐 Python _normalize_chapter_id）
     * V1C1 → V1C1, v1c001 → V1C1 等
     */
    private String normalizeChapterId(String chapter) {
        if (chapter == null || chapter.isBlank()) return chapter;
        String upper = chapter.trim().toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
                int ch = Integer.parseInt(upper.substring(cIdx + 1));
                return String.format("V%dC%d", vol, ch);
            } catch (NumberFormatException ignored) {}
        }
        return chapter.trim();
    }

    /**
     * 从草稿内容检测设定建议（对齐 Python _detect_proposals）
     * 纯启发式，无 LLM 调用，失败返回空列表
     */
    private List<Map<String, Object>> detectProposals(String projectId, String content) {
        try {
            List<String> existingCharacterNames = cardStorage.listCharacterCards(projectId);
            List<String> existingWorldNames = cardStorage.listWorldCards(projectId);
            List<Map<String, Object>> proposals =
                    archivistAgent.detectSettingChanges(content, existingCharacterNames, existingWorldNames);
            return ProposalPostProcessor.finalizeProposals(proposals, MAX_DETECTED_PROPOSALS);
        } catch (Exception e) {
            log.warn("detectProposals 失败: {}", e.getMessage());
            return List.of();
        }
    }
    
    private void updateStatus(SessionStatus status, String message) {
        this.currentStatus = status;

        broadcastTraceEvent(new LinkedHashMap<>(Map.of(
                "event", "session_status",
                "status", status.getValue(),
                "message", message,
                "project_id", currentProjectId != null ? currentProjectId : "",
                "chapter", currentChapter != null ? currentChapter : "",
                "iteration", iterationCount
        )));
        
        if (currentProjectId != null) {
            broadcastProgress(currentProjectId, Map.of(
                    "status", status.getValue(),
                    "message", message,
                    "project_id", currentProjectId,
                    "chapter", currentChapter != null ? currentChapter : "",
                    "iteration", iterationCount
            ));
        }
    }
    
    private void broadcastProgress(String projectId, Map<String, Object> payload) {
        if (webSocketHandler != null) {
            webSocketHandler.broadcast(projectId, payload);
        }
    }

    private void broadcastTraceEvent(Map<String, Object> payload) {
        if (traceWebSocketHandler != null) {
            traceWebSocketHandler.publish("trace_event", payload);
        }
    }

    private void broadcastContextStats(Map<String, Object> context) {
        if (traceWebSocketHandler == null) {
            return;
        }
        traceWebSocketHandler.publish("context_stats_update", buildContextStats(context));
    }

    private void broadcastAgentTrace(String agentName, Map<String, Object> payload) {
        if (traceWebSocketHandler == null) {
            return;
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("agent_name", agentName);
        if (currentProjectId != null) {
            trace.put("project_id", currentProjectId);
        }
        if (currentChapter != null) {
            trace.put("chapter", currentChapter);
        }
        trace.putAll(payload != null ? payload : Map.of());
        traceWebSocketHandler.publish("agent_trace_update", trace);
    }

    private Map<String, Object> buildContextStats(Map<String, Object> context) {
        int maxTokens = 16000;

        int guiding = estimateTokenCount(
                stringValue(context, "chapterGoal", "") + "\n" + extractSceneBriefText(context.get("sceneBrief"))
        );
        int informational = estimateTokenCount(
                joinTexts(context.get("previousSummaries")) + "\n"
                        + stringValue(context, "working_memory", "") + "\n"
                        + summarizeEvidencePack(context.get("evidence_pack"))
        );
        int actionable = estimateTokenCount(
                joinTexts(context.get("questions")) + "\n"
                        + joinTexts(context.get("planned_queries")) + "\n"
                        + joinTexts(context.get("planned_queries_all"))
        );

        int total = guiding + informational + actionable;
        List<String> issues = new ArrayList<>();
        if (total > (int) (maxTokens * 0.9)) {
            issues.add("Context usage is close to limit");
        }
        Object unresolved = context.get("unresolved_gaps");
        if (unresolved instanceof List<?> list && !list.isEmpty()) {
            issues.add("There are unresolved context gaps");
        }

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("guiding", guiding);
        breakdown.put("informational", informational);
        breakdown.put("actionable", actionable);

        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("total", total);
        tokenUsage.put("max", maxTokens);
        tokenUsage.put("breakdown", breakdown);

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("healthy", issues.isEmpty());
        health.put("issues", issues);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("token_usage", tokenUsage);
        stats.put("health", health);
        return stats;
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.trim();
        return Math.max(1, normalized.length() / 2);
    }

    private String extractSceneBriefText(Object value) {
        if (!(value instanceof SceneBrief brief)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (brief.getGoal() != null) {
            sb.append(brief.getGoal()).append('\n');
        }
        if (brief.getConflict() != null) {
            sb.append(brief.getConflict()).append('\n');
        }
        if (brief.getKeyBeats() != null) {
            sb.append(String.join("\n", brief.getKeyBeats()));
        }
        return sb.toString();
    }

    private String joinTexts(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object text = map.get("text");
                if (text == null) {
                    text = map.get("question");
                }
                if (text == null) {
                    text = map.get("query");
                }
                if (text != null) {
                    sb.append(text).append('\n');
                }
            } else if (item != null) {
                sb.append(item).append('\n');
            }
        }
        return sb.toString();
    }

    private String summarizeEvidencePack(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return "";
        }
        Object itemsObj = map.get("items");
        if (!(itemsObj instanceof List<?> items) || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> evidence)) {
                continue;
            }
            Object text = evidence.get("text");
            if (text != null) {
                sb.append(text).append('\n');
                count++;
            }
            if (count >= 8) {
                break;
            }
        }
        return sb.toString();
    }
    
    private Map<String, Object> handleError(String message) {
        currentStatus = SessionStatus.ERROR;
        log.error("Session error: {}", message);
        
        if (currentProjectId != null) {
            broadcastProgress(currentProjectId, Map.of(
                    "status", SessionStatus.ERROR.getValue(),
                    "message", message,
                    "project_id", currentProjectId
            ));
        }
        
        return Map.of(
                "success", false,
                "status", SessionStatus.ERROR.getValue(),
                "error", message
        );
    }
    
    // ========== 分析功能 ==========
    
    /**
     * 分析章节内容
     */
    public Map<String, Object> analyzeChapter(String projectId, String chapter, 
                                               String content, String chapterTitle) {
        try {
            // 如果没有提供内容，从草稿加载
            String draftContent = content;
            if (draftContent == null || draftContent.isBlank()) {
                List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
                if (versions.isEmpty()) {
                    return Map.of("success", false, "error", "No draft found");
                }
                
                String latestVersion = versions.get(versions.size() - 1);
                Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
                if (draftOpt.isEmpty()) {
                    return Map.of("success", false, "error", "Draft content missing");
                }
                draftContent = draftOpt.get().getContent();
            }
            
            updateStatus(SessionStatus.ANALYZING, "Analyzing content...");
            
            // 构建分析结果
            Map<String, Object> analysis = buildAnalysis(projectId, chapter, draftContent, chapterTitle);
            
            updateStatus(SessionStatus.IDLE, "Analysis completed.");
            
            return Map.of(
                    "success", true,
                    "analysis", analysis
            );
            
        } catch (Exception e) {
            log.error("Analysis failed", e);
            return handleError("Analysis failed: " + e.getMessage());
        }
    }
    
    /**
     * 保存分析结果（对齐 Python save_analysis）
     */
    public Map<String, Object> saveAnalysis(String projectId, String chapter,
                                             Map<String, Object> analysis, boolean overwrite) {
        return saveAnalysis(projectId, chapter, analysis, overwrite, true);
    }

    public Map<String, Object> saveAnalysis(String projectId, String chapter,
                                             Map<String, Object> analysis, boolean overwrite,
                                             boolean rebuildVolumeSummary) {
        String normalizedChapter = normalizeChapterId(chapter);
        String traceId = buildTimingTraceId("saveAnalysis", normalizedChapter);
        long totalStartedAt = System.nanoTime();
        Map<String, Long> stageTimings = new LinkedHashMap<>();
        int factsSaved = 0;
        int timelineSaved = 0;
        int statesSaved = 0;
        int cardsCreated = 0;
        boolean summarySaved = false;
        boolean success = false;
        String failure = "";
        try {
            long stageStartedAt = System.nanoTime();
            String analysisContent = draftStorage.getFinalDraft(projectId, normalizedChapter)
                    .orElseGet(() -> draftStorage.getLatestDraft(projectId, normalizedChapter)
                            .map(draft -> draft.getContent() != null ? draft.getContent() : "")
                            .orElse(""));
            stageTimings.put("load_analysis_content", elapsedMillis(stageStartedAt));

            // 保存摘要
            @SuppressWarnings("unchecked")
            Map<String, Object> summaryData = (Map<String, Object>) analysis.get("summary");
            stageStartedAt = System.nanoTime();
            long volumeSummaryStartedAt = -1L;
            if (summaryData != null) {
                String requestedTitle = stringValue(summaryData, "title", "");
                com.wenshape.model.entity.ChapterSummary summary =
                        com.wenshape.model.entity.ChapterSummary.builder()
                        .chapter(normalizedChapter)
                        .title(requestedTitle)
                        .briefSummary((String) summaryData.getOrDefault("brief_summary", ""))
                        .volumeId(firstNonBlank(
                                String.valueOf(summaryData.getOrDefault("volume_id", "")).trim(),
                                extractVolumeId(normalizedChapter)
                        ))
                        .wordCount(intValue(summaryData.get("word_count"), 0))
                        .build();
                summary.setKeyEvents(stringList(summaryData.get("key_events")));
                summary.setNewFacts(stringList(summaryData.get("new_facts")));
                summary.setCharacterStateChanges(stringList(summaryData.get("character_state_changes")));
                summary.setOpenLoops(stringList(summaryData.get("open_loops")));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> analysisFacts = (List<Map<String, Object>>) analysis.getOrDefault("facts", List.of());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> analysisTimeline = (List<Map<String, Object>>) analysis.getOrDefault("timeline_events", List.of());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> analysisStates = (List<Map<String, Object>>) analysis.getOrDefault("character_states", List.of());
                enrichChapterSummary(projectId, summary, null, Map.of(
                        "facts", analysisFacts,
                        "timeline_events", analysisTimeline,
                        "character_states", analysisStates
                ));
                if (summary.getOpenLoops() == null || summary.getOpenLoops().isEmpty()) {
                    ChapterSummary refreshed = archivistAgent.generateChapterSummary(
                            projectId,
                            normalizedChapter,
                            requestedTitle,
                            analysisContent
                    );
                    if (refreshed != null && refreshed.getOpenLoops() != null && !refreshed.getOpenLoops().isEmpty()) {
                        summary.setOpenLoops(refreshed.getOpenLoops());
                    }
                }
                normalizeChapterSummaryTitle(projectId, normalizedChapter, summary, requestedTitle);
                draftStorage.saveChapterSummary(projectId, summary);
                summarySaved = true;
                long summaryElapsedBeforeVolume = elapsedMillis(stageStartedAt);

                // 分卷摘要（对齐 Python save_analysis 里的 rebuild_volume_summary）
                if (rebuildVolumeSummary) {
                    volumeSummaryStartedAt = System.nanoTime();
                    try {
                        String volumeId = summary.getVolumeId();
                        List<ChapterSummary> volumeSummaries = draftStorage.listChapterSummaries(projectId, volumeId);
                        Map<String, Object> volumeSummary = archivistAgent.generateVolumeSummary(projectId, volumeId, volumeSummaries);
                        draftStorage.saveVolumeSummary(projectId, volumeSummary);
                    } catch (Exception ex) {
                        log.warn("saveAnalysis: 生成分卷摘要失败: {}", ex.getMessage());
                    } finally {
                        stageTimings.put("rebuild_volume_summary", elapsedMillis(volumeSummaryStartedAt));
                    }
                }
                stageTimings.put("save_summary", summaryElapsedBeforeVolume);
            } else {
                stageTimings.put("save_summary", elapsedMillis(stageStartedAt));
            }
            if (!stageTimings.containsKey("rebuild_volume_summary")) {
                stageTimings.put("rebuild_volume_summary", 0L);
            }

            // overwrite 时先删除该章节的旧 facts（对齐 Python delete_facts_by_chapter）
            stageStartedAt = System.nanoTime();
            if (overwrite) {
                canonStorage.deleteFactsByChapter(projectId, normalizedChapter);
                canonStorage.deleteTimelineEventsByChapter(projectId, normalizedChapter);
                canonStorage.deleteCharacterStatesByChapter(projectId, normalizedChapter);
            }
            stageTimings.put("overwrite_cleanup", elapsedMillis(stageStartedAt));

            // 保存 facts 到 CanonStorage
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) analysis.getOrDefault("facts", List.of());
            stageStartedAt = System.nanoTime();
            for (Map<String, Object> factData : facts) {
                try {
                    String stmt = (String) factData.getOrDefault("statement", "");
                    if (stmt.isBlank()) continue;
                    com.wenshape.model.entity.Fact fact = com.wenshape.model.entity.Fact.builder()
                            .statement(stmt)
                            .content(stmt)
                            .source(normalizedChapter)
                            .introducedIn(normalizedChapter)
                            .confidence(((Number) factData.getOrDefault("confidence", 0.9)).doubleValue())
                            .build();
                    canonStorage.saveFact(projectId, fact);
                    factsSaved++;
                } catch (Exception e) {
                    log.warn("保存 fact 失败: {}", e.getMessage());
                }
            }
            stageTimings.put("save_facts", elapsedMillis(stageStartedAt));

            // 保存 timeline_events
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) analysis.getOrDefault("timeline_events", List.of());
            stageStartedAt = System.nanoTime();
            for (Map<String, Object> event : timelineEvents) {
                try {
                    Map<String, Object> normalizedEvent = new java.util.LinkedHashMap<>(event);
                    normalizedEvent.putIfAbsent("source", normalizedChapter);
                    canonStorage.saveTimelineEvent(projectId, normalizedEvent);
                    timelineSaved++;
                } catch (Exception e) {
                    log.warn("保存 timeline_event 失败: {}", e.getMessage());
                }
            }
            stageTimings.put("save_timeline", elapsedMillis(stageStartedAt));

            // 保存 character_states
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> characterStates = (List<Map<String, Object>>) analysis.getOrDefault("character_states", List.of());
            stageStartedAt = System.nanoTime();
            for (Map<String, Object> state : characterStates) {
                try {
                    Map<String, Object> normalizedState = new java.util.LinkedHashMap<>(state);
                    normalizedState.putIfAbsent("last_seen", normalizedChapter);
                    canonStorage.saveCharacterState(projectId, normalizedState);
                    statesSaved++;
                } catch (Exception e) {
                    log.warn("保存 character_state 失败: {}", e.getMessage());
                }
            }
            stageTimings.put("save_character_states", elapsedMillis(stageStartedAt));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> proposals = (List<Map<String, Object>>) analysis.getOrDefault("proposals", List.of());
            stageStartedAt = System.nanoTime();
            cardsCreated = createCardsFromProposals(projectId, proposals, overwrite);
            stageTimings.put("create_cards_from_proposals", elapsedMillis(stageStartedAt));

            stageStartedAt = System.nanoTime();
            if (cardsCreated > 0 && bindingsController != null) {
                try {
                    bindingsController.markAllBindingsStale(
                            projectId,
                            "analysis_cards_changed:" + normalizedChapter,
                            true
                    );
                } catch (Exception e) {
                    log.warn("saveAnalysis: 标记 bindings stale 失败: {}", e.getMessage());
                }
            }
            stageTimings.put("mark_bindings_stale", elapsedMillis(stageStartedAt));

            stageStartedAt = System.nanoTime();
            try {
                memoryPackStorage.writePack(projectId, normalizedChapter, buildMemoryPackPayload(projectId, normalizedChapter, traceId));
            } catch (Exception e) {
                log.warn("saveAnalysis: 保存 memory pack 失败: {}", e.getMessage());
            }
            stageTimings.put("memory_pack", elapsedMillis(stageStartedAt));

            success = true;

            return Map.of(
                    "success", true,
                    "stats", Map.of(
                            "summary_saved", summarySaved,
                            "facts_saved", factsSaved,
                            "timeline_saved", timelineSaved,
                            "states_saved", statesSaved,
                            "cards_created", cardsCreated
                    )
            );

        } catch (Exception e) {
            failure = e.getMessage();
            log.error("Save analysis failed", e);
            return handleError("Save analysis failed: " + e.getMessage());
        } finally {
            stageTimings.put("total", elapsedMillis(totalStartedAt));
            log.info("saveAnalysis timing: trace={}, project={}, chapter={}, overwrite={}, rebuildVolumeSummary={}, success={}, failure={}, stats={summary_saved={}, facts_saved={}, timeline_saved={}, states_saved={}, cards_created={}}, timings={}",
                    traceId,
                    projectId,
                    normalizedChapter,
                    overwrite,
                    rebuildVolumeSummary,
                    success,
                    failure,
                    summarySaved,
                    factsSaved,
                    timelineSaved,
                    statesSaved,
                    cardsCreated,
                    formatTimingMap(stageTimings));
        }
    }
    
    /**
     * 批量分析章节
     */
    public Map<String, Object> analyzeBatch(String projectId, List<String> chapters) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (String chapter : chapters) {
            try {
                Map<String, Object> result = analyzeChapter(projectId, chapter, null, null);
                if (Boolean.TRUE.equals(result.get("success"))) {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", true,
                            "analysis", result.get("analysis")
                    ));
                } else {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", false,
                            "error", result.getOrDefault("error", "Unknown error")
                    ));
                }
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }
        
        return Map.of("success", true, "results", results);
    }
    
    /**
     * 同步分析（分析并覆盖保存）
     */
    public Map<String, Object> analyzeSync(String projectId, List<String> chapters) {
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> volumeIdsToRefresh = new LinkedHashSet<>();
        
        for (String chapter : chapters) {
            try {
                // 分析
                Map<String, Object> analyzeResult = analyzeChapter(projectId, chapter, null, null);
                if (!Boolean.TRUE.equals(analyzeResult.get("success"))) {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", false,
                            "error", analyzeResult.getOrDefault("error", "Analysis failed")
                    ));
                    continue;
                }
                
                // 保存（覆盖）
                @SuppressWarnings("unchecked")
                Map<String, Object> analysis = (Map<String, Object>) analyzeResult.get("analysis");
                volumeIdsToRefresh.add(resolveVolumeIdFromAnalysis(chapter, analysis));
                Map<String, Object> saveResult = saveAnalysis(projectId, chapter, analysis, true, false);
                
                results.add(Map.of(
                        "chapter", chapter,
                        "success", Boolean.TRUE.equals(saveResult.get("success")),
                        "stats", saveResult.getOrDefault("stats", Map.of())
                ));
                
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }

        refreshVolumeSummaries(projectId, volumeIdsToRefresh);
        
        return Map.of("success", true, "results", results);
    }
    
    /**
     * 批量保存分析结果
     */
    public Map<String, Object> saveAnalysisBatch(String projectId, List<Map<String, Object>> items, boolean overwrite) {
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> volumeIdsToRefresh = new LinkedHashSet<>();
        
        for (Map<String, Object> item : items) {
            String chapter = (String) item.get("chapter");
            @SuppressWarnings("unchecked")
            Map<String, Object> analysis = (Map<String, Object>) item.get("analysis");
            
            try {
                volumeIdsToRefresh.add(resolveVolumeIdFromAnalysis(chapter, analysis));
                Map<String, Object> saveResult = saveAnalysis(projectId, chapter, analysis, overwrite, false);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", Boolean.TRUE.equals(saveResult.get("success")),
                        "stats", saveResult.getOrDefault("stats", Map.of())
                ));
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }

        refreshVolumeSummaries(projectId, volumeIdsToRefresh);
        
        return Map.of("success", true, "results", results);
    }
    
    private Map<String, Object> buildAnalysis(String projectId, String chapter,
                                               String content, String chapterTitle) {
        String title = resolvePreferredChapterTitle(projectId, chapter, chapterTitle);

        // These stages read the same draft and do not depend on each other's output.
        ChapterSummary summary;
        Map<String, Object> canonUpdates;
        if (analysisExecutor == null) {
            summary = archivistAgent.generateChapterSummary(projectId, chapter, title, content);
            canonUpdates = archivistAgent.extractCanonUpdates(projectId, chapter, content);
        } else {
            ChapterAnalysisExecutor.Result result = analysisExecutor.analyze(
                    archivistAgent, projectId, chapter, title, content);
            summary = result.summary();
            canonUpdates = result.canonUpdates();
            log.info("Chapter analysis timing: project={}, chapter={}, timings={}",
                    projectId, chapter, result.timings());
        }
        summary.setWordCount(content.length());
        if (summary.getVolumeId() == null || summary.getVolumeId().isBlank()) {
            summary.setVolumeId(extractVolumeId(chapter));
        }
        normalizeChapterSummaryTitle(projectId, chapter, summary, title);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) canonUpdates.getOrDefault("facts", List.of());
        if (facts.size() > 5) facts = facts.subList(0, 5);

        Map<String, Object> normalizedCanonUpdates = new LinkedHashMap<>(canonUpdates);
        normalizedCanonUpdates.put("facts", facts);
        enrichChapterSummary(projectId, summary, content, normalizedCanonUpdates);

        List<Map<String, Object>> proposals = detectProposals(projectId, content);

        return Map.of(
                "summary", summaryToMap(summary),
                "facts", facts,
                "timeline_events", canonUpdates.getOrDefault("timeline_events", List.of()),
                "character_states", canonUpdates.getOrDefault("character_states", List.of()),
                "proposals", proposals
        );
    }

    private void enrichChapterSummary(String projectId,
                                      ChapterSummary summary,
                                      String content,
                                      Map<String, Object> canonUpdates) {
        if (summary == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = canonUpdates == null
                ? List.of()
                : (List<Map<String, Object>>) canonUpdates.getOrDefault("facts", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timelineEvents = canonUpdates == null
                ? List.of()
                : (List<Map<String, Object>>) canonUpdates.getOrDefault("timeline_events", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characterStates = canonUpdates == null
                ? List.of()
                : (List<Map<String, Object>>) canonUpdates.getOrDefault("character_states", List.of());
        AdaptiveHintProfile hintProfile = resolveAdaptiveHintProfile(
                projectId,
                summary.getChapter(),
                collectAdaptiveOverlayTexts(summary, facts, characterStates)
        );

        List<String> keyEvents = dedupeStrings(summary.getKeyEvents());
        if (keyEvents.isEmpty()) {
            keyEvents = deriveKeyEventsFromSummary(summary.getBriefSummary());
        }
        summary.setKeyEvents(keyEvents);

        if (summary.getBriefSummary() == null
                || summary.getBriefSummary().isBlank()
                || isFallbackSummary(summary.getBriefSummary(), content)
                || summary.getBriefSummary().length() > 160) {
            String derivedBrief = deriveBriefSummary(summary, facts, content);
            if (!derivedBrief.isBlank()) {
                summary.setBriefSummary(derivedBrief);
            }
        }

        List<String> derivedFacts = deriveSummaryNewFacts(facts, keyEvents, hintProfile);
        if (summary.getNewFacts() == null || summary.getNewFacts().isEmpty()) {
            if (!derivedFacts.isEmpty()) {
                summary.setNewFacts(derivedFacts);
            }
        } else {
            List<String> existingFacts = dedupeStrings(summary.getNewFacts());
            if (shouldReplaceSummaryNewFacts(existingFacts, derivedFacts, hintProfile)) {
                summary.setNewFacts(derivedFacts);
            } else {
                summary.setNewFacts(existingFacts);
            }
        }

        List<String> derivedStates = deriveCharacterStateChanges(characterStates, hintProfile);
        if (summary.getCharacterStateChanges() == null || summary.getCharacterStateChanges().isEmpty()) {
            if (!derivedStates.isEmpty()) {
                summary.setCharacterStateChanges(derivedStates);
            }
        } else {
            List<String> existingStates = dedupeStrings(summary.getCharacterStateChanges());
            if (shouldReplaceCharacterStateChanges(existingStates, derivedStates, hintProfile)) {
                summary.setCharacterStateChanges(derivedStates);
            } else {
                summary.setCharacterStateChanges(existingStates);
            }
        }

        if (summary.getOpenLoops() == null) {
            summary.setOpenLoops(new ArrayList<>());
        } else {
            summary.setOpenLoops(dedupeStrings(summary.getOpenLoops()));
        }
    }

    private String resolvePreferredChapterTitle(String projectId, String chapter, String explicitTitle) {
        String normalizedChapter = normalizeChapterId(chapter);
        List<String> candidates = new ArrayList<>();
        candidates.add(explicitTitle);
        draftStorage.getChapterSummary(projectId, normalizedChapter)
                .map(ChapterSummary::getTitle)
                .ifPresent(candidates::add);
        draftStorage.getSceneBrief(projectId, normalizedChapter)
                .map(SceneBrief::getTitle)
                .ifPresent(candidates::add);

        for (String candidate : candidates) {
            String sanitized = sanitizeChapterTitle(candidate);
            if (isMeaningfulChapterTitle(sanitized, normalizedChapter)) {
                return sanitized;
            }
        }
        return "";
    }

    private void normalizeChapterSummaryTitle(String projectId,
                                              String chapter,
                                              ChapterSummary summary,
                                              String explicitTitle) {
        if (summary == null) {
            return;
        }
        String normalizedChapter = normalizeChapterId(chapter);
        String current = sanitizeChapterTitle(summary.getTitle());
        if (isMeaningfulChapterTitle(current, normalizedChapter)) {
            summary.setTitle(current);
            return;
        }
        summary.setTitle(resolvePreferredChapterTitle(projectId, normalizedChapter, explicitTitle));
    }

    private boolean isMeaningfulChapterTitle(String title, String chapter) {
        String sanitized = sanitizeChapterTitle(title);
        return !sanitized.isBlank() && !looksLikeSyntheticChapterTitle(sanitized, chapter);
    }

    private boolean looksLikeSyntheticChapterTitle(String title, String chapter) {
        String sanitized = sanitizeChapterTitle(title);
        if (sanitized.isBlank()) {
            return false;
        }
        String upperTitle = sanitized.toUpperCase(Locale.ROOT);
        String upperChapter = normalizeChapterId(chapter).toUpperCase(Locale.ROOT);
        if (upperTitle.equals(upperChapter) || SYNTHETIC_CHAPTER_TITLE_PATTERN.matcher(upperTitle).matches()) {
            return true;
        }
        return upperTitle.equals(("章节 " + upperChapter).toUpperCase(Locale.ROOT))
                || upperTitle.equals(("章节" + upperChapter).toUpperCase(Locale.ROOT))
                || upperTitle.equals("CHAPTER " + upperChapter)
                || upperTitle.equals("CHAPTER" + upperChapter)
                || upperTitle.equals("CHAPTER-" + upperChapter);
    }

    private String sanitizeChapterTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").trim();
    }

    private String deriveBriefSummary(ChapterSummary summary,
                                      List<Map<String, Object>> facts,
                                      String content) {
        List<String> candidates = new ArrayList<>();
        if (summary != null && summary.getKeyEvents() != null) {
            candidates.addAll(summary.getKeyEvents().stream()
                    .map(this::cleanMemoryText)
                    .filter(s -> !s.isBlank())
                    .limit(2)
                    .toList());
        }
        if (candidates.isEmpty() && facts != null) {
            candidates.addAll(facts.stream()
                    .map(item -> cleanMemoryText(String.valueOf(item.getOrDefault("statement", ""))))
                    .filter(s -> !s.isBlank())
                    .limit(3)
                    .toList());
        }
        String combined = joinSummaryParts(candidates);
        if (!combined.isBlank()) {
            return truncateToBoundary(combined, 110);
        }
        if (content == null || content.isBlank()) {
            return "";
        }
        String fallback = cleanMemoryText(content);
        return truncateToBoundary(fallback, 110);
    }

    private List<String> deriveKeyEventsFromSummary(String briefSummary) {
        List<String> result = new ArrayList<>();
        String cleaned = cleanMemoryText(briefSummary);
        if (cleaned.isBlank()) {
            return result;
        }
        String[] parts = cleaned.split("(?<=[。！？；;])");
        for (String part : parts) {
            String event = cleanMemoryText(part);
            if (event.isBlank()) {
                continue;
            }
            if (!result.contains(event)) {
                result.add(truncateToBoundary(event, 72));
            }
            if (result.size() >= 4) {
                break;
            }
        }
        return result;
    }

    private boolean isFallbackSummary(String briefSummary, String content) {
        if (briefSummary == null || briefSummary.isBlank()) {
            return true;
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        String fallback = buildFallbackSummaryFromContent(content);
        return normalizeText(briefSummary).equals(normalizeText(fallback));
    }

    private String buildFallbackSummaryFromContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.length() > 100 ? content.substring(0, 100) + "..." : content;
    }

    private List<String> deriveSummaryNewFacts(List<Map<String, Object>> facts,
                                               List<String> keyEvents,
                                               AdaptiveHintProfile hintProfile) {
        List<String> rawKeyEvents = keyEvents == null ? List.of() : keyEvents;
        List<String> eventTerms = new ArrayList<>();
        for (String event : rawKeyEvents) {
            eventTerms.addAll(extractFocusTerms(cleanMemoryText(event), 10));
        }

        List<String> candidates = new ArrayList<>();
        if (facts != null) {
            for (Map<String, Object> fact : facts) {
                String statement = cleanMemoryText(String.valueOf(fact.getOrDefault("statement", "")));
                if (!statement.isBlank() && !candidates.contains(statement)) {
                    candidates.add(statement);
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(rawKeyEvents.stream().map(this::cleanMemoryText).filter(text -> !text.isBlank()).toList());
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> ranked = new ArrayList<>(candidates);
        ranked.sort((a, b) -> Double.compare(
                scoreSummaryFactCandidate(b, eventTerms, hintProfile),
                scoreSummaryFactCandidate(a, eventTerms, hintProfile)
        ));

        List<String> result = new ArrayList<>();
        for (String statement : ranked) {
            if (statement.isBlank() || result.contains(statement) || looksLikeSurfaceDetail(statement)) {
                continue;
            }
            if (!looksLikeDurableFact(statement, hintProfile)
                    && scoreSummaryFactCandidate(statement, eventTerms, hintProfile) < 1.5d) {
                continue;
            }
            result.add(statement);
            if (result.size() >= 3) {
                return result;
            }
        }

        for (String event : rawKeyEvents) {
            String cleaned = cleanMemoryText(event);
            if (cleaned.isBlank() || result.contains(cleaned)) {
                continue;
            }
            if (looksLikeDurableFact(cleaned, hintProfile)) {
                result.add(cleaned);
            }
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    private double scoreSummaryFactCandidate(String statement,
                                             List<String> eventTerms,
                                             AdaptiveHintProfile hintProfile) {
        String cleaned = cleanMemoryText(statement);
        if (cleaned.isBlank()) {
            return 0.0d;
        }
        double score = looksLikeDurableFact(cleaned, hintProfile) ? 1.2d : 0.0d;
        score += Math.min(cleaned.length() / 24.0d, 1.4d);
        if (containsAnyToken(cleaned, SUMMARY_NOVEL_FACT_HINTS)
                || containsAdaptiveHint(cleaned, hintProfile == null ? List.of() : hintProfile.discoveryHints())) {
            score += 1.4d;
        }
        if (containsAnyToken(cleaned, SUMMARY_CARRYOVER_FACT_HINTS)
                || containsAdaptiveHint(cleaned, hintProfile == null ? List.of() : hintProfile.carryoverHints())) {
            score -= 0.7d;
        }
        if (termOverlap(cleaned, eventTerms) > 0) {
            score += 0.8d + Math.min(0.6d, termOverlap(cleaned, eventTerms) * 0.2d);
        }
        if (cleaned.contains("疑似") || cleaned.contains("似乎")) {
            score -= 0.3d;
        }
        return score;
    }

    private boolean shouldReplaceSummaryNewFacts(List<String> existingFacts,
                                                 List<String> derivedFacts,
                                                 AdaptiveHintProfile hintProfile) {
        if (derivedFacts == null || derivedFacts.isEmpty()) {
            return false;
        }
        if (existingFacts == null || existingFacts.isEmpty()) {
            return true;
        }
        double existingScore = existingFacts.stream().mapToDouble(item -> scoreSummaryFactCandidate(item, List.of(), hintProfile)).sum();
        double derivedScore = derivedFacts.stream().mapToDouble(item -> scoreSummaryFactCandidate(item, List.of(), hintProfile)).sum();
        boolean existingLooksCarryover = existingFacts.stream().allMatch(item ->
                        containsAnyToken(item, SUMMARY_CARRYOVER_FACT_HINTS)
                                || containsAdaptiveHint(item, hintProfile == null ? List.of() : hintProfile.carryoverHints()))
                && existingFacts.stream().noneMatch(item ->
                        containsAnyToken(item, SUMMARY_NOVEL_FACT_HINTS)
                                || containsAdaptiveHint(item, hintProfile == null ? List.of() : hintProfile.discoveryHints()));
        boolean derivedHasNovelty = derivedFacts.stream().anyMatch(item ->
                containsAnyToken(item, SUMMARY_NOVEL_FACT_HINTS)
                        || containsAdaptiveHint(item, hintProfile == null ? List.of() : hintProfile.discoveryHints()));
        return (existingLooksCarryover && derivedHasNovelty) || derivedScore > existingScore + 1.0d;
    }

    private boolean looksLikeDurableFact(String text, AdaptiveHintProfile hintProfile) {
        String normalized = text == null ? "" : text;
        return normalized.contains("意识到")
                || normalized.contains("确认")
                || normalized.contains("决定")
                || normalized.contains("成为")
                || normalized.contains("获得")
                || normalized.contains("失去")
                || normalized.contains("遗失")
                || normalized.contains("受困")
                || normalized.contains("隔绝")
                || normalized.contains("见底")
                || normalized.contains("匮乏")
                || normalized.contains("内伤")
                || normalized.contains("疗伤")
                || normalized.contains("人工痕迹")
                || normalized.contains("遗迹")
                || normalized.contains("入口")
                || normalized.contains("拖擦声")
                || normalized.contains("异响")
                || normalized.contains("青光")
                || normalized.contains("死亡")
                || normalized.contains("沦为")
                || normalized.contains("本质")
                || normalized.contains("命令")
                || normalized.contains("主宰")
                || normalized.contains("禁术")
                || normalized.contains("规则")
                || containsAdaptiveHint(normalized, hintProfile == null ? List.of() : hintProfile.durableHints());
    }

    private boolean looksLikeSurfaceDetail(String text) {
        String normalized = text == null ? "" : text;
        return normalized.contains("燃着")
                || normalized.contains("游走")
                || normalized.contains("踩在")
                || normalized.contains("挑起")
                || normalized.contains("色泽")
                || normalized.contains("香")
                || normalized.contains("绒毯");
    }

    private String joinSummaryParts(List<String> parts) {
        List<String> cleanedParts = new ArrayList<>();
        for (String part : parts == null ? List.<String>of() : parts) {
            String cleaned = cleanMemoryText(part).replaceAll("[。！？；;，、]+$", "").trim();
            if (!cleaned.isBlank()) {
                cleanedParts.add(cleaned);
            }
        }
        if (cleanedParts.isEmpty()) {
            return "";
        }
        return String.join("；", cleanedParts) + "。";
    }

    private List<String> deriveCharacterStateChanges(List<Map<String, Object>> characterStates,
                                                     AdaptiveHintProfile hintProfile) {
        List<String> result = new ArrayList<>();
        if (characterStates == null) {
            return result;
        }
        for (Map<String, Object> state : characterStates) {
            String character = cleanMemoryText(String.valueOf(state.getOrDefault("character", "")));
            String value = cleanMemoryText(String.valueOf(state.getOrDefault("state", "")));
            if (value.isBlank()) {
                value = buildCharacterStateSummaryFromStructuredFields(state, hintProfile);
            }
            if (character.isBlank() || value.isBlank()) {
                continue;
            }
            String combined = character + "：" + value;
            if (!result.contains(combined)) {
                result.add(combined);
            }
            if (result.size() >= 3) {
                break;
            }
        }
        return result;
    }

    private String buildCharacterStateSummaryFromStructuredFields(Map<String, Object> state,
                                                                  AdaptiveHintProfile hintProfile) {
        if (state == null || state.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String emotion = cleanMemoryText(String.valueOf(state.getOrDefault("emotional_state", state.getOrDefault("emotion", ""))));
        if (!emotion.isBlank()) {
            parts.add("情绪：" + emotion);
        }
        List<String> goals = stringList(state.get("goals"));
        if (!goals.isEmpty()) {
            parts.add("目标：" + String.join("、", goals.stream().limit(2).toList()));
        }
        List<String> injuries = prioritizeStateSummaryItems(
                stringList(state.get("injuries")),
                3,
                mergeHintLists(List.of("闷堵", "冲开", "变化", "内伤", "寒"),
                        hintProfile == null ? List.of() : hintProfile.stateHints()),
                List.of()
        );
        if (!injuries.isEmpty()) {
            parts.add("伤势：" + String.join("、", injuries));
        }
        List<String> inventory = prioritizeStateSummaryItems(
                stringList(state.get("inventory")),
                3,
                mergeHintLists(List.of("异动", "清水", "残片", "阴", "尖石"),
                        hintProfile == null ? List.of() : hintProfile.stateHints()),
                List.of("遗失", "见底")
        );
        if (!inventory.isEmpty()) {
            parts.add("持有：" + String.join("、", inventory));
        }
        String location = cleanMemoryText(String.valueOf(state.getOrDefault("location", "")));
        if (parts.isEmpty() && !location.isBlank()) {
            parts.add("位置：" + location);
        } else if (!location.isBlank() && containsAnyToken(location, List.of("古洞", "石缝", "洞室"))) {
            parts.add("位置：" + location);
        }
        return String.join("；", parts);
    }

    private List<String> prioritizeStateSummaryItems(List<String> values,
                                                     int limit,
                                                     List<String> highlightTokens,
                                                     List<String> penaltyTokens) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> normalized = dedupeStrings(values);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> sourceOrder = new LinkedHashMap<>();
        for (int i = 0; i < normalized.size(); i++) {
            sourceOrder.putIfAbsent(normalized.get(i), i);
        }
        return normalized.stream()
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(
                            scoreStateSummaryItem(right, highlightTokens, penaltyTokens),
                            scoreStateSummaryItem(left, highlightTokens, penaltyTokens)
                    );
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Integer.compare(
                            sourceOrder.getOrDefault(left, Integer.MAX_VALUE),
                            sourceOrder.getOrDefault(right, Integer.MAX_VALUE)
                    );
                })
                .limit(limit)
                .toList();
    }

    private int scoreStateSummaryItem(String text,
                                      List<String> highlightTokens,
                                      List<String> penaltyTokens) {
        String cleaned = cleanMemoryText(text);
        if (cleaned.isBlank()) {
            return Integer.MIN_VALUE;
        }
        int score = Math.min(cleaned.length(), 40);
        if (containsAnyToken(cleaned, highlightTokens)) {
            score += 80;
        }
        if (containsAnyToken(cleaned, penaltyTokens)) {
            score -= 20;
        }
        return score;
    }

    private boolean shouldReplaceCharacterStateChanges(List<String> existingStates,
                                                       List<String> derivedStates,
                                                       AdaptiveHintProfile hintProfile) {
        if (derivedStates == null || derivedStates.isEmpty()) {
            return false;
        }
        if (existingStates == null || existingStates.isEmpty()) {
            return true;
        }
        double existingScore = existingStates.stream().mapToDouble(line -> scoreCharacterStateLine(line, hintProfile)).sum();
        double derivedScore = derivedStates.stream().mapToDouble(line -> scoreCharacterStateLine(line, hintProfile)).sum();
        List<String> freshnessHints = mergeHintLists(
                prioritizeQueryHints(hintProfile),
                mergeHintLists(
                        hintProfile == null ? List.of() : hintProfile.stateHints(),
                        hintProfile == null ? List.of() : hintProfile.discoveryHints()
                )
        );
        int existingFreshHits = existingStates.stream().mapToInt(line -> termOverlapCount(line, freshnessHints)).sum();
        int derivedFreshHits = derivedStates.stream().mapToInt(line -> termOverlapCount(line, freshnessHints)).sum();
        int existingFacetCoverage = existingStates.stream().mapToInt(this::countStateFacetCoverage).max().orElse(0);
        int derivedFacetCoverage = derivedStates.stream().mapToInt(this::countStateFacetCoverage).max().orElse(0);
        boolean existingStale = existingStates.stream().allMatch(line -> !containsAnyToken(line, SUMMARY_NOVEL_FACT_HINTS))
                && existingStates.stream().noneMatch(line ->
                        containsAdaptiveHint(line, hintProfile == null ? List.of() : hintProfile.stateHints()));
        boolean derivedHasCurrentArc = derivedStates.stream().anyMatch(line ->
                containsAdaptiveHint(line, hintProfile == null ? List.of() : hintProfile.stateHints()));
        boolean derivedMoreCurrent = derivedFreshHits > existingFreshHits;
        boolean derivedMoreStructured = derivedFacetCoverage > existingFacetCoverage;
        return (existingStale && derivedHasCurrentArc)
                || (derivedMoreCurrent && derivedMoreStructured)
                || derivedFreshHits >= existingFreshHits + 2
                || derivedScore > existingScore + 0.5d;
    }

    private double scoreCharacterStateLine(String line, AdaptiveHintProfile hintProfile) {
        String cleaned = cleanMemoryText(line);
        if (cleaned.isBlank()) {
            return 0.0d;
        }
        double score = Math.min(cleaned.length() / 28.0d, 1.8d);
        if (containsAnyToken(cleaned, List.of("情绪：", "目标：", "伤势：", "持有："))) {
            score += 1.0d;
        }
        if (containsAdaptiveHint(cleaned, hintProfile == null ? List.of() : hintProfile.stateHints())) {
            score += 1.2d;
        }
        List<String> freshnessHints = mergeHintLists(
                prioritizeQueryHints(hintProfile),
                hintProfile == null ? List.of() : hintProfile.discoveryHints()
        );
        score += Math.min(1.0d, termOverlapCount(cleaned, freshnessHints) * 0.22d);
        score += countStateFacetCoverage(cleaned) * 0.18d;
        if (containsAnyToken(cleaned, SUMMARY_CARRYOVER_FACT_HINTS)) {
            score -= 0.4d;
        }
        return score;
    }

    private int countStateFacetCoverage(String line) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String token : List.of("情绪：", "目标：", "伤势：", "持有：", "位置：")) {
            if (line.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private List<String> dedupeStrings(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String cleaned = cleanMemoryText(value);
            if (!cleaned.isBlank() && !result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private Map<String, Object> summaryToMap(ChapterSummary summary) {
        Map<String, Object> m = new HashMap<>();
        m.put("chapter", summary.getChapter());
        m.put("title", summary.getTitle() != null ? summary.getTitle() : "");
        m.put("volume_id", summary.getVolumeId() != null ? summary.getVolumeId() : extractVolumeId(summary.getChapter()));
        m.put("word_count", summary.getWordCount());
        m.put("brief_summary", summary.getBriefSummary() != null ? summary.getBriefSummary() : "");
        m.put("key_events", summary.getKeyEvents() != null ? summary.getKeyEvents() : List.of());
        m.put("new_facts", summary.getNewFacts() != null ? summary.getNewFacts() : List.of());
        m.put("character_state_changes", summary.getCharacterStateChanges() != null ? summary.getCharacterStateChanges() : List.of());
        m.put("open_loops", summary.getOpenLoops() != null ? summary.getOpenLoops() : List.of());
        return m;
    }
    
    private String extractVolumeId(String chapter) {
        if (chapter == null) return "V1";
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                return "V" + upper.substring(vIdx + 1, cIdx);
            } catch (Exception e) {
                return "V1";
            }
        }
        return "V1";
    }

    private String resolveVolumeIdFromAnalysis(String chapter, Map<String, Object> analysis) {
        if (analysis != null && analysis.get("summary") instanceof Map<?, ?> summary) {
            String volumeId = stringValue(summary, "volume_id", "").trim();
            if (!volumeId.isBlank()) {
                return volumeId;
            }
            String summaryChapter = stringValue(summary, "chapter", "").trim();
            if (!summaryChapter.isBlank()) {
                return extractVolumeId(summaryChapter);
            }
        }
        return extractVolumeId(chapter);
    }

    private void refreshVolumeSummaries(String projectId, Collection<String> volumeIds) {
        if (volumeIds == null || volumeIds.isEmpty()) {
            return;
        }
        for (String volumeId : volumeIds) {
            if (volumeId == null || volumeId.isBlank()) {
                continue;
            }
            try {
                List<ChapterSummary> volumeSummaries = draftStorage.listChapterSummaries(projectId, volumeId);
                Map<String, Object> volumeSummary = archivistAgent.generateVolumeSummary(projectId, volumeId, volumeSummaries);
                draftStorage.saveVolumeSummary(projectId, volumeSummary);
            } catch (Exception ex) {
                log.warn("refreshVolumeSummaries: 生成分卷摘要失败({}): {}", volumeId, ex.getMessage());
            }
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = String.valueOf(item != null ? item : "").trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private List<?> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private Map<String, Object> buildWriterResearchPayload(String projectId, String chapter, String chapterGoal,
                                                           SceneBrief sceneBrief, List<String> characterNames,
                                                           List<Map<String, String>> userAnswers) {
        return buildWriterResearchPayload(projectId, chapter, chapterGoal, sceneBrief, characterNames, userAnswers, "");
    }

    private Map<String, Object> buildWriterResearchPayload(String projectId, String chapter, String chapterGoal,
                                                           SceneBrief sceneBrief, List<String> characterNames,
                                                           List<Map<String, String>> userAnswers,
                                                           String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (selectEngine == null) {
            return payload;
        }
        boolean traceTiming = traceId != null && !traceId.isBlank();
        long totalStartedAt = System.nanoTime();
        Map<String, Long> stageTimings = traceTiming ? new LinkedHashMap<>() : null;
        List<String> roundSummaries = traceTiming ? new ArrayList<>() : List.of();

        long stageStartedAt = System.nanoTime();
        List<Map<String, String>> effectiveUserAnswers = (userAnswers == null || userAnswers.isEmpty())
                ? List.of()
                : userAnswers;
        List<String> seedCharacters = extractSeedCharacters(projectId, chapter, sceneBrief, chapterGoal, characterNames);
        List<String> seedEntities = extractSeedEntities(projectId, chapter, sceneBrief, chapterGoal, characterNames);
        List<Map<String, Object>> gaps = buildResearchGaps(projectId, chapter, sceneBrief, chapterGoal, seedCharacters);
        if (traceTiming) {
            stageTimings.put("seed_entities_and_gaps", elapsedMillis(stageStartedAt));
        }
        stageStartedAt = System.nanoTime();
        Map<String, Object> initialPlan = writerAgent.generateResearchPlan(
                chapterGoal,
                gaps,
                Map.of(),
                1
        );
        if (traceTiming) {
            stageTimings.put("initial_research_plan", elapsedMillis(stageStartedAt));
        }
        stageStartedAt = System.nanoTime();
        List<String> plannedQueries = extractPlanQueries(initialPlan, gaps, 6);
        Map<Integer, List<String>> plannedQueriesByRound = new LinkedHashMap<>();
        if (!plannedQueries.isEmpty()) {
            plannedQueriesByRound.put(1, new ArrayList<>(plannedQueries));
        }
        List<Map<String, Object>> answerEvidence = buildAnswerEvidenceItems(chapter, effectiveUserAnswers);
        Set<String> answeredGapTexts = answeredGapTextsFromAnswers(chapter, gaps, effectiveUserAnswers);
        Set<String> unknownGapTexts = unknownGapTextsFromAnswers(chapter, gaps, effectiveUserAnswers);
        List<String> recentChapters = recentChapterWindow(projectId, chapter, 2);
        List<Map<String, Object>> memoryEvidence = loadMemoryEvidence(projectId, recentChapters, seedEntities, chapterGoal);
        List<Map<String, Object>> currentChapterEvidence = loadCurrentChapterEvidence(projectId, chapter, seedEntities, chapterGoal, sceneBrief);
        if (traceTiming) {
            stageTimings.put("load_existing_evidence", elapsedMillis(stageStartedAt));
        }

        stageStartedAt = System.nanoTime();
        List<ContextItem> criticalItems = selectEngine.deterministicSelect(projectId, "writer");
        String combinedQuery = String.join(" ", seedEntities) + " "
                + String.join(" ", plannedQueries) + " "
                + (chapterGoal != null ? chapterGoal : "");
        List<ContextItem> seedRelevantItems = selectEngine.retrievalSelect(
                projectId, combinedQuery.trim(), List.of("character", "world", "fact", "summary", "text_chunk"), 24
        );
        if (traceTiming) {
            stageTimings.put("initial_context_retrieval", elapsedMillis(stageStartedAt));
        }

        List<String> characterCards = new ArrayList<>();
        List<String> worldCards = new ArrayList<>();
        List<Map<String, Object>> textChunks = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<Map<String, Object>> evidenceItems = new ArrayList<>();
        List<Map<String, Object>> evidenceGroups = new ArrayList<>();
        List<Map<String, Object>> retrievalRequests = new ArrayList<>();
        Set<String> evidenceKeys = new LinkedHashSet<>();
        Map<String, Boolean> gapSupported = new LinkedHashMap<>();
        Map<String, Double> gapSupportScores = new LinkedHashMap<>();
        List<String> focusTerms = new ArrayList<>();

        for (Map<String, Object> item : currentChapterEvidence) {
            addEvidenceItem(evidenceItems, evidenceKeys, item);
        }

        for (ContextItem item : criticalItems) {
            String type = item.getType() != null ? item.getType().getValue() : "";
            if ("character_card".equals(type) && characterCards.size() < 10) {
                characterCards.add(item.getContent());
            } else if ("world_card".equals(type) && worldCards.size() < 10) {
                worldCards.add(item.getContent());
            }
        }

        for (String entity : seedEntities) {
            if (entity != null && !entity.isBlank() && !focusTerms.contains(entity)) {
                focusTerms.add(entity);
            }
            if (focusTerms.size() >= 6) {
                break;
            }
        }
        for (String term : extractFocusTerms(compactChapterGoal(chapterGoal, sceneBrief), 18)) {
            if (term != null && !term.isBlank() && !focusTerms.contains(term)) {
                focusTerms.add(term);
            }
            if (focusTerms.size() >= 20) {
                break;
            }
        }

        int effectiveMaxRounds = isFirstChapter(chapter) ? 1 : MAX_RESEARCH_ROUNDS;
        List<Map<String, Object>> unresolvedGaps = new ArrayList<>();
        String researchStopReason = "unknown";
        List<String> nextRoundQueries = new ArrayList<>(plannedQueries);

        for (int roundIndex = 1; roundIndex <= effectiveMaxRounds; roundIndex++) {
            long roundStartedAt = System.nanoTime();
            long gapEvidenceMillis = 0L;
            long supportMillis = 0L;
            long nextPlanMillis = 0L;
            int skippedAnsweredGaps = 0;
            List<Map<String, Object>> roundGaps = withExtraResearchGap(chapter, gaps, nextRoundQueries);
            for (Map<String, Object> gap : roundGaps) {
                @SuppressWarnings("unchecked")
                List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
                String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
                String gapKind = String.valueOf(gap.getOrDefault("kind", "")).trim();
                String gapId = gapSupportKey(gap);
                if (!gapText.isBlank()
                        && answeredGapTexts.contains(gapText)
                        && !unknownGapTexts.contains(gapText)
                        && ANSWER_SKIP_RETRIEVAL_KINDS.contains(gapKind)) {
                    skippedAnsweredGaps++;
                    gapSupported.put(gapId, true);
                    gapSupportScores.put(gapId, Math.max(gapSupportScores.getOrDefault(gapId, 0.0), MIN_GAP_SUPPORT_SCORE + 1.0));
                    retrievalRequests.add(buildSkippedRetrievalRequest(roundIndex, gap, queries, "answered_gap_skip_retrieval", MIN_GAP_SUPPORT_SCORE + 1.0));
                    continue;
                }

                long gapStartedAt = System.nanoTime();
                Map<String, Object> result = collectGapEvidence(
                        projectId,
                        chapter,
                        chapterGoal,
                        recentChapters,
                        memoryEvidence,
                        gap,
                        roundIndex,
                        traceId
                );
                gapEvidenceMillis += elapsedMillis(gapStartedAt);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> selectedGapItems = (List<Map<String, Object>>) result.getOrDefault("items", List.of());
                for (Map<String, Object> item : selectedGapItems) {
                    addEvidenceItem(evidenceItems, evidenceKeys, item);
                }

                double support = result.get("support") instanceof Number n ? n.doubleValue() : 0.0;
                boolean semanticRerankEnabled = Boolean.TRUE.equals(result.get("semantic_rerank"));
                String rerankQuery = String.valueOf(result.getOrDefault("rerank_query", ""));
                int rerankTopK = intValue(result.get("rerank_top_k"), 0);
                int requestLimit = intValue(result.get("limit"), roundIndex == 1 ? 10 : 8);
                String note = String.valueOf(result.getOrDefault("note", ""));

                evidenceGroups.add(buildEvidenceGroup(
                        gap,
                        queries,
                        selectedGapItems,
                        buildEvidenceGroupStats(selectedGapItems, queries, requestLimit, roundIndex, note, semanticRerankEnabled, rerankQuery, rerankTopK)
                ));
                retrievalRequests.add(buildRetrievalRequest(roundIndex, gap, queries, selectedGapItems, support, note, nextRoundQueries));
            }

            long supportStartedAt = System.nanoTime();
            List<Map<String, Object>> supportItems = new ArrayList<>(evidenceItems);
            supportItems.addAll(answerEvidence);
            recomputeGapSupport(gaps, supportItems, gapSupported, gapSupportScores);
            unresolvedGaps = decorateQuestionGapsWithSupport(
                    selectQuestionGaps(gaps, gapSupported, gapSupportScores, focusTerms, false),
                    gapSupportScores
            );

            List<Map<String, Object>> roundFinalEvidenceItems = applyTypeQuotas(
                    mergeEvidenceWithAnswers(evidenceItems, answerEvidence),
                    buildGlobalWriterQuotas(),
                    12
            );
            Map<String, Object> roundSufficiencyReport = buildSufficiencyReport(
                    gaps,
                    gapSupported,
                    gapSupportScores,
                    roundFinalEvidenceItems,
                    focusTerms,
                    unknownGapTexts
            );
            supportMillis = elapsedMillis(supportStartedAt);
            boolean sufficientNow = Boolean.TRUE.equals(roundSufficiencyReport.get("sufficient"));
            if (sufficientNow) {
                researchStopReason = "sufficient";
                if (traceTiming) {
                    roundSummaries.add(String.format(Locale.ROOT,
                            "r%d total=%dms gaps=%d skipped=%d gap_evidence=%dms support=%dms next_plan=%dms unresolved=%d evidence=%d stop=%s",
                            roundIndex,
                            elapsedMillis(roundStartedAt),
                            roundGaps.size(),
                            skippedAnsweredGaps,
                            gapEvidenceMillis,
                            supportMillis,
                            nextPlanMillis,
                            unresolvedGaps.size(),
                            roundFinalEvidenceItems.size(),
                            researchStopReason));
                }
                break;
            }
            if (roundIndex >= effectiveMaxRounds) {
                researchStopReason = "max_rounds";
                if (traceTiming) {
                    roundSummaries.add(String.format(Locale.ROOT,
                            "r%d total=%dms gaps=%d skipped=%d gap_evidence=%dms support=%dms next_plan=%dms unresolved=%d evidence=%d stop=%s",
                            roundIndex,
                            elapsedMillis(roundStartedAt),
                            roundGaps.size(),
                            skippedAnsweredGaps,
                            gapEvidenceMillis,
                            supportMillis,
                            nextPlanMillis,
                            unresolvedGaps.size(),
                            roundFinalEvidenceItems.size(),
                            researchStopReason));
                }
                break;
            }

            long nextPlanStartedAt = System.nanoTime();
            Map<String, Object> nextPlan = writerAgent.generateResearchPlan(
                    chapterGoal,
                    unresolvedGaps,
                    Map.of(
                            "types", countTypesInEvidence(roundFinalEvidenceItems),
                            "total", roundFinalEvidenceItems.size(),
                            "queries", plannedQueriesByRound.getOrDefault(roundIndex, List.of()),
                            "top_sources", extractTopSources(roundFinalEvidenceItems, 3)
                    ),
                    roundIndex + 1
            );
            nextPlanMillis = elapsedMillis(nextPlanStartedAt);
            nextRoundQueries = extractPlanQueries(nextPlan, unresolvedGaps, 6);
            if (traceTiming) {
                roundSummaries.add(String.format(Locale.ROOT,
                        "r%d total=%dms gaps=%d skipped=%d gap_evidence=%dms support=%dms next_plan=%dms unresolved=%d evidence=%d stop=%s",
                        roundIndex,
                        elapsedMillis(roundStartedAt),
                        roundGaps.size(),
                        skippedAnsweredGaps,
                        gapEvidenceMillis,
                        supportMillis,
                        nextPlanMillis,
                        unresolvedGaps.size(),
                        roundFinalEvidenceItems.size(),
                        nextRoundQueries.isEmpty() ? "no_queries" : "continue"));
            }
            if (nextRoundQueries.isEmpty()) {
                researchStopReason = "no_queries";
                break;
            }
            plannedQueriesByRound.put(roundIndex + 1, new ArrayList<>(nextRoundQueries));
        }

        if (evidenceItems.isEmpty() && !combinedQuery.isBlank()) {
            List<Map<String, Object>> fallbackItems = new ArrayList<>();
            Set<String> fallbackKeys = new LinkedHashSet<>();
            for (ContextItem item : seedRelevantItems) {
                Map<String, Object> evidence = toEvidenceItem(item);
                if (evidence != null) {
                    addEvidenceItem(evidenceItems, evidenceKeys, evidence);
                    addEvidenceItem(fallbackItems, fallbackKeys, evidence);
                }
            }
            for (Map<String, Object> item : filterMemoryEvidence(memoryEvidence, List.of(combinedQuery))) {
                addEvidenceItem(evidenceItems, evidenceKeys, item);
                addEvidenceItem(fallbackItems, fallbackKeys, item);
            }
            if (!fallbackItems.isEmpty()) {
                Map<String, Object> fallbackGap = new LinkedHashMap<>();
                fallbackGap.put("kind", "fallback");
                fallbackGap.put("text", "goal_fallback");
                fallbackGap.put("queries", List.of(combinedQuery));
                fallbackGap.put("ask_user", false);
                fallbackGap.put("key", makeQuestionKey(chapter, "fallback", "goal_fallback"));
                evidenceGroups.add(buildEvidenceGroup(
                        fallbackGap,
                        List.of(combinedQuery),
                        fallbackItems,
                        buildEvidenceGroupStats(fallbackItems, List.of(combinedQuery), 12, 1, "goal_fallback", false, combinedQuery, 0)
                ));
                retrievalRequests.add(buildRetrievalRequest(1, fallbackGap, List.of(combinedQuery), fallbackItems, scoreGapSupport(List.of(combinedQuery), fallbackItems), "goal_fallback", List.of()));
            }
        }

        stageStartedAt = System.nanoTime();
        recomputeGapSupport(gaps, mergeEvidenceWithAnswers(evidenceItems, answerEvidence), gapSupported, gapSupportScores);
        unresolvedGaps = decorateQuestionGapsWithSupport(
                selectQuestionGaps(gaps, gapSupported, gapSupportScores, focusTerms, false),
                gapSupportScores
        );
        List<Map<String, Object>> finalEvidenceItems = applyTypeQuotas(
                mergeEvidenceWithAnswers(evidenceItems, answerEvidence),
                buildGlobalWriterQuotas(),
                12
        );
        populateContextCollections(finalEvidenceItems, characterCards, worldCards, facts, textChunks, focusTerms);

        Map<String, Object> sufficiencyReport = buildSufficiencyReport(
                gaps,
                gapSupported,
                gapSupportScores,
                finalEvidenceItems,
                focusTerms,
                unknownGapTexts
        );
        List<Map<String, String>> questions = buildQuestions(unresolvedGaps, chapter, unknownGapTexts);
        if ("unknown".equals(researchStopReason) || researchStopReason.isBlank()) {
            if (finalEvidenceItems.isEmpty() && retrievalRequests.isEmpty()) {
                researchStopReason = "empty_payload";
            } else if (Boolean.TRUE.equals(sufficiencyReport.get("sufficient"))) {
                researchStopReason = "sufficient";
            } else {
                researchStopReason = "max_rounds";
            }
        }
        boolean needsUserInput = Boolean.TRUE.equals(sufficiencyReport.get("needs_user_input"));
        if (!"max_rounds".equals(researchStopReason) || !needsUserInput) {
            questions = List.of();
        }
        List<Map<String, Object>> researchTrace = buildResearchTrace(
                retrievalRequests,
                researchStopReason,
                Boolean.TRUE.equals(sufficiencyReport.get("sufficient"))
        );

        String workingMemory = buildWorkingMemory(
                chapterGoal,
                sceneBrief,
                finalEvidenceItems,
                unresolvedGaps,
                answeredGapTexts,
                unknownGapTexts
        );

        boolean sufficient = Boolean.TRUE.equals(sufficiencyReport.get("sufficient"));
        List<String> allPlannedQueries = new ArrayList<>();
        int latestRoundIndex = 1;
        for (Map.Entry<Integer, List<String>> entry : plannedQueriesByRound.entrySet()) {
            List<String> roundQueries = entry.getValue() == null ? List.of() : entry.getValue();
            latestRoundIndex = Math.max(latestRoundIndex, entry.getKey());
            if (entry.getKey() > 1 && !roundQueries.isEmpty()) {
                payload.put("planned_queries_round" + entry.getKey(), roundQueries);
            }
            for (String query : roundQueries) {
                if (query != null && !query.isBlank() && !allPlannedQueries.contains(query)) {
                    allPlannedQueries.add(query);
                }
            }
        }
        List<String> latestRoundQueries = plannedQueriesByRound.getOrDefault(latestRoundIndex, plannedQueries);
        List<String> persistedInitialQueries = finalizePersistedPlannedQueries(
                projectId,
                chapter,
                sceneBrief,
                plannedQueries,
                unresolvedGaps,
                focusTerms,
                sufficient
        );
        List<String> persistedLatestQueries = finalizePersistedPlannedQueries(
                projectId,
                chapter,
                sceneBrief,
                latestRoundQueries,
                unresolvedGaps,
                focusTerms,
                sufficient
        );
        List<String> persistedAllPlannedQueries = finalizePersistedPlannedQueries(
                projectId,
                chapter,
                sceneBrief,
                allPlannedQueries,
                unresolvedGaps,
                focusTerms,
                sufficient
        );

        List<String> primaryPersistedQueries = !persistedLatestQueries.isEmpty()
                ? persistedLatestQueries
                : (!persistedAllPlannedQueries.isEmpty() ? persistedAllPlannedQueries : persistedInitialQueries);

        payload.put("seed_entities", seedEntities);
        payload.put("seed_window", 2);
        payload.put("gaps", gaps);
        payload.put("planned_queries", primaryPersistedQueries);
        if (!persistedAllPlannedQueries.isEmpty()) {
            payload.put("planned_queries_all", persistedAllPlannedQueries);
        }
        payload.put("character_cards", characterCards);
        payload.put("world_cards", worldCards);
        payload.put("text_chunks", textChunks);
        payload.put("facts", facts);
        Map<String, Object> evidenceStats = new LinkedHashMap<>();
        evidenceStats.put("total", finalEvidenceItems.size());
        evidenceStats.put("types", countTypesInEvidence(finalEvidenceItems));
        evidenceStats.put("queries", retrievalRequests.stream()
                .flatMap(request -> ((List<String>) request.getOrDefault("queries", List.of())).stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        evidenceStats.put("hits", finalEvidenceItems.size());
        evidenceStats.put("top_sources", extractTopSources(finalEvidenceItems, 3));
        evidenceStats.put("limit", 12);
        evidenceStats.put("types_requested", List.of("fact", "summary", "character", "world_rule", "world_entity", "text_chunk", "memory"));
        evidenceStats.put("semantic_rerank", evidenceGroups.stream().anyMatch(group -> booleanValue(group, "stats", "semantic_rerank")));
        evidenceStats.put("rerank_query", firstNonBlankNestedValue(evidenceGroups, "stats", "rerank_query"));
        evidenceStats.put("rerank_top_k", firstNonZeroNestedInt(evidenceGroups, "stats", "rerank_top_k"));
        evidenceStats.put("round", retrievalRequests.stream().mapToInt(item -> intValue(item.get("round"), 1)).max().orElse(1));
        evidenceStats.put("note", "");

        Map<String, Object> evidencePack = new LinkedHashMap<>();
        evidencePack.put("items", finalEvidenceItems);
        evidencePack.put("groups", evidenceGroups);
        evidencePack.put("stats", evidenceStats);
        payload.put("evidence_pack", evidencePack);
        payload.put("retrieval_requests", retrievalRequests);
        payload.put("unresolved_gaps", unresolvedGaps.stream().limit(6).toList());
        payload.put("questions", questions);
        payload.put("sufficiency_report", sufficiencyReport);
        payload.put("research_trace", researchTrace);
        payload.put("research_stop_reason", researchStopReason);
        if (workingMemory != null && !workingMemory.isBlank()) {
            payload.put("working_memory", workingMemory);
        }
        if (traceTiming) {
            stageTimings.put("final_assembly", elapsedMillis(stageStartedAt));
            stageTimings.put("total", elapsedMillis(totalStartedAt));
            log.info("buildWriterResearchPayload timing: trace={}, project={}, chapter={}, gaps={}, planned_queries={}, retrieval_requests={}, evidence_items={}, unresolved_gaps={}, stop_reason={}, timings={}, rounds={}",
                    traceId,
                    projectId,
                    chapter,
                    gaps.size(),
                    plannedQueries.size(),
                    retrievalRequests.size(),
                    finalEvidenceItems.size(),
                    unresolvedGaps.size(),
                    researchStopReason,
                    formatTimingMap(stageTimings),
                    roundSummaries);
        }
        return payload;
    }

    private List<Map<String, Object>> withExtraResearchGap(String chapter,
                                                           List<Map<String, Object>> baseGaps,
                                                           List<String> extraQueries) {
        List<Map<String, Object>> roundGaps = new ArrayList<>();
        Set<String> baseQueries = new LinkedHashSet<>();
        if (baseGaps != null) {
            for (Map<String, Object> gap : baseGaps) {
                if (gap != null) {
                    roundGaps.add(new LinkedHashMap<>(gap));
                    Object rawQueries = gap.get("queries");
                    if (rawQueries instanceof List<?> list) {
                        for (Object item : list) {
                            String query = item == null ? "" : item.toString().trim();
                            if (!query.isBlank()) {
                                baseQueries.add(query);
                            }
                        }
                    }
                }
            }
        }
        List<String> cleanedExtraQueries = new ArrayList<>();
        for (String query : extraQueries == null ? List.<String>of() : extraQueries) {
            String text = query == null ? "" : query.trim();
            if (!text.isBlank() && !baseQueries.contains(text) && !cleanedExtraQueries.contains(text)) {
                cleanedExtraQueries.add(text);
            }
            if (cleanedExtraQueries.size() >= 8) {
                break;
            }
        }
        if (!cleanedExtraQueries.isEmpty()) {
            roundGaps.add(gapItem(
                    chapter,
                    "extra_research",
                    "研究补充查询",
                    cleanedExtraQueries,
                    false,
                    null
            ));
        }
        return roundGaps;
    }

    private Map<String, Object> collectGapEvidence(String projectId,
                                                   String chapter,
                                                   String chapterGoal,
                                                   List<String> recentChapters,
                                                   List<Map<String, Object>> memoryEvidence,
                                                   Map<String, Object> gap,
                                                   int roundIndex) {
        return collectGapEvidence(projectId, chapter, chapterGoal, recentChapters, memoryEvidence, gap, roundIndex, "");
    }

    private Map<String, Object> collectGapEvidence(String projectId,
                                                   String chapter,
                                                   String chapterGoal,
                                                   List<String> recentChapters,
                                                   List<Map<String, Object>> memoryEvidence,
                                                   Map<String, Object> gap,
                                                   int roundIndex,
                                                   String traceId) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
        String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
        String gapKind = String.valueOf(gap.getOrDefault("kind", "")).trim();
        String gapQuery = queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));

        long totalStartedAt = System.nanoTime();
        long memoryFilterStartedAt = System.nanoTime();
        List<Map<String, Object>> gapCandidates = new ArrayList<>();
        gapCandidates.addAll(filterMemoryEvidence(memoryEvidence, queries));
        long memoryFilterMillis = elapsedMillis(memoryFilterStartedAt);
        boolean semanticRerank = false;
        String rerankQuery = "";
        long retrievalMillis = 0L;
        long chunkSearchMillis = 0L;
        long rerankMillis = 0L;
        if (!gapQuery.isBlank()) {
            long retrievalStartedAt = System.nanoTime();
            List<ContextItem> gapItems = selectEngine.retrievalSelect(
                    projectId,
                    gapQuery,
                    List.of("character", "world", "fact", "summary", "text_chunk"),
                    roundIndex == 1 ? 16 : 12,
                    recentChapters == null || recentChapters.isEmpty() ? null : recentChapters,
                    List.of(chapter)
            );
            retrievalMillis = elapsedMillis(retrievalStartedAt);
            for (ContextItem item : gapItems) {
                Map<String, Object> evidence = toEvidenceItem(item);
                if (evidence != null) {
                    gapCandidates.add(evidence);
                }
            }

            long chunkSearchStartedAt = System.nanoTime();
            List<Map<String, Object>> chunkHits = draftStorage.searchTextChunks(
                    projectId,
                    gapQuery,
                    roundIndex == 1 ? 8 : 6,
                    recentChapters == null || recentChapters.isEmpty() ? null : recentChapters,
                    List.of(chapter),
                    false
            );
            chunkSearchMillis = elapsedMillis(chunkSearchStartedAt);
            semanticRerank = "plot_point".equalsIgnoreCase(gapKind) && chunkHits.size() >= 3;
            rerankQuery = semanticRerank
                    ? (chapterGoal != null && !chapterGoal.isBlank() ? chapterGoal + " | " + gapText : gapText)
                    : "";
            if (semanticRerank) {
                long rerankStartedAt = System.nanoTime();
                chunkHits = rerankChunkHits(chunkHits, rerankQuery, roundIndex == 1 ? 8 : 6);
                rerankMillis = elapsedMillis(rerankStartedAt);
            }
            for (Map<String, Object> chunk : chunkHits) {
                gapCandidates.add(toChunkEvidenceItem(chunk));
            }
        }

        int limit = roundIndex == 1 ? 10 : 8;
        List<Map<String, Object>> selectedGapItems = applyTypeQuotas(gapCandidates, buildGapQuotas(gapKind), limit);
        double support = scoreGapSupport(queries, selectedGapItems);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", selectedGapItems);
        result.put("support", support);
        result.put("semantic_rerank", semanticRerank);
        result.put("rerank_query", rerankQuery);
        result.put("rerank_top_k", semanticRerank ? (roundIndex == 1 ? 8 : 6) : 0);
        result.put("limit", limit);
        result.put("note", roundIndex > 1 ? "expanded_round" : "");
        if (traceId != null && !traceId.isBlank() && log.isDebugEnabled()) {
            log.debug("collectGapEvidence timing: trace={}, project={}, chapter={}, round={}, kind={}, gap={}, memory_filter={}ms, retrieval={}ms, chunk_search={}ms, rerank={}ms, total={}ms, candidates={}, selected={}, support={}",
                    traceId,
                    projectId,
                    chapter,
                    roundIndex,
                    gapKind,
                    truncateForLog(gapText, 48),
                    memoryFilterMillis,
                    retrievalMillis,
                    chunkSearchMillis,
                    rerankMillis,
                    elapsedMillis(totalStartedAt),
                    gapCandidates.size(),
                    selectedGapItems.size(),
                    String.format(Locale.ROOT, "%.2f", support));
        }
        return result;
    }

    private void recomputeGapSupport(List<Map<String, Object>> gaps,
                                     List<Map<String, Object>> evidenceItems,
                                     Map<String, Boolean> gapSupported,
                                     Map<String, Double> gapSupportScores) {
        if (gaps == null || gaps.isEmpty()) {
            return;
        }
        for (Map<String, Object> gap : gaps) {
            if (gap == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
            String gapId = gapSupportKey(gap);
            double support = scoreGapSupport(queries, evidenceItems);
            gapSupportScores.put(gapId, Math.max(gapSupportScores.getOrDefault(gapId, 0.0), support));
            gapSupported.put(gapId, gapSupportScores.getOrDefault(gapId, 0.0) >= MIN_GAP_SUPPORT_SCORE);
        }
    }

    private List<Map<String, Object>> decorateQuestionGapsWithSupport(List<Map<String, Object>> gaps,
                                                                      Map<String, Double> gapSupportScores) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> gap : gaps) {
            if (gap == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(gap);
            copy.put("support_score", gapSupportScores.getOrDefault(gapSupportKey(gap), 0.0));
            result.add(copy);
        }
        return result;
    }

    private List<Map<String, Object>> mergeEvidenceWithAnswers(List<Map<String, Object>> evidenceItems,
                                                               List<Map<String, Object>> answerEvidence) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> mergedKeys = new LinkedHashSet<>();
        for (Map<String, Object> item : evidenceItems == null ? List.<Map<String, Object>>of() : evidenceItems) {
            addEvidenceItem(merged, mergedKeys, item);
        }
        for (Map<String, Object> item : answerEvidence == null ? List.<Map<String, Object>>of() : answerEvidence) {
            addEvidenceItem(merged, mergedKeys, item);
        }
        return merged;
    }

    private String gapSupportKey(Map<String, ?> gap) {
        if (gap == null) {
            return "";
        }
        String key = String.valueOf(gap.containsKey("key") ? gap.get("key") : "").trim();
        if (!key.isBlank()) {
            return key;
        }
        String kind = String.valueOf(gap.containsKey("kind") ? gap.get("kind") : "").trim();
        String text = String.valueOf(gap.containsKey("text") ? gap.get("text") : "").trim();
        return kind + "|" + text;
    }

    private void addEvidenceItem(List<Map<String, Object>> evidenceItems,
                                 Set<String> evidenceKeys,
                                 Map<String, Object> rawItem) {
        if (rawItem == null || rawItem.isEmpty()) {
            return;
        }
        String text = String.valueOf(rawItem.getOrDefault("text", ""));
        if (text.isBlank()) {
            return;
        }
        String id = String.valueOf(rawItem.getOrDefault("id", "")).trim();
        String type = String.valueOf(rawItem.getOrDefault("type", "")).trim();
        String key = (id != null && !id.isBlank() ? id : type + ":" + text.hashCode());
        if (!evidenceKeys.add(key)) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", key);
        item.put("type", type);
        item.put("text", text.length() > 500 ? text.substring(0, 500) + "..." : text);
        item.put("score", rawItem.get("score") instanceof Number scoreNumber ? scoreNumber.doubleValue() : 0.0);
        item.put("source", rawItem.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of());
        item.put("scope", rawItem.getOrDefault("scope", null));
        item.put("entities", extractStringList(rawItem.get("entities")));
        item.put("meta", rawItem.get("meta") instanceof Map<?, ?> meta ? castStringObjectMap(meta) : Map.of());
        evidenceItems.add(item);
    }

    private Map<String, Object> buildEvidenceGroup(Map<String, Object> gap,
                                                   List<String> queries,
                                                   List<Map<String, Object>> items,
                                                   Map<String, Object> stats) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("gap", gap != null ? new LinkedHashMap<>(gap) : Map.of());
        group.put("queries", queries == null ? List.of() : new ArrayList<>(queries));
        group.put("items", items == null ? List.of() : items);
        group.put("stats", stats == null ? Map.of() : stats);
        return group;
    }

    private Map<String, Object> buildEvidenceGroupStats(List<Map<String, Object>> items,
                                                        List<String> queries,
                                                        int limit,
                                                        int round,
                                                        String note,
                                                        boolean semanticRerank,
                                                        String rerankQuery,
                                                        int rerankTopK) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", items == null ? 0 : items.size());
        stats.put("types", countTypesInEvidence(items == null ? List.of() : items));
        stats.put("queries", queries == null ? List.of() : new ArrayList<>(queries));
        stats.put("hits", items == null ? 0 : items.size());
        stats.put("top_sources", extractTopSources(items == null ? List.of() : items, 3));
        stats.put("semantic_rerank", semanticRerank && rerankQuery != null && !rerankQuery.isBlank());
        stats.put("rerank_query", rerankQuery != null ? rerankQuery : "");
        stats.put("rerank_top_k", rerankTopK);
        stats.put("limit", limit);
        stats.put("types_requested", List.of("fact", "summary", "character", "world_rule", "world_entity", "text_chunk", "memory"));
        stats.put("round", round);
        stats.put("note", note != null ? note : "");
        return stats;
    }

    private Map<String, Object> buildRetrievalRequest(int round,
                                                      Map<String, Object> gap,
                                                      List<String> queries,
                                                      List<Map<String, Object>> items,
                                                      double supportScore,
                                                      String reason,
                                                      List<String> planQueries) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("round", round);
        request.put("gap", gap);
        request.put("queries", queries == null ? List.of() : new ArrayList<>(queries));
        request.put("types", countTypesInEvidence(items == null ? List.of() : items));
        request.put("count", items == null ? 0 : items.size());
        request.put("support_score", supportScore);
        request.put("top_sources", extractTopSources(items == null ? List.of() : items, 3));
        if (reason != null && !reason.isBlank()) {
            request.put("reason", reason);
        }
        if (planQueries != null && !planQueries.isEmpty()) {
            request.put("plan_queries", new ArrayList<>(planQueries));
        }
        return request;
    }

    private Map<String, Object> buildSkippedRetrievalRequest(int round,
                                                             Map<String, Object> gap,
                                                             List<String> queries,
                                                             String reason,
                                                             double supportScore) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("round", round);
        request.put("gap", gap);
        request.put("queries", queries == null ? List.of() : new ArrayList<>(queries));
        request.put("types", Map.of());
        request.put("count", 0);
        request.put("support_score", supportScore);
        request.put("top_sources", List.of());
        request.put("skipped", true);
        request.put("reason", reason != null ? reason : "skipped");
        return request;
    }

    private boolean booleanValue(Map<String, Object> outer, String nestedKey, String targetKey) {
        if (outer == null || outer.isEmpty()) {
            return false;
        }
        Object nested = outer.get(nestedKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return false;
        }
        Object value = map.get(targetKey);
        return value instanceof Boolean bool && bool;
    }

    private String firstNonBlankNestedValue(List<Map<String, Object>> items, String nestedKey, String targetKey) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Object nested = item.get(nestedKey);
            if (!(nested instanceof Map<?, ?> map)) {
                continue;
            }
            Object value = map.get(targetKey);
            String text = value == null ? "" : value.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private int firstNonZeroNestedInt(List<Map<String, Object>> items, String nestedKey, String targetKey) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Object nested = item.get(nestedKey);
            if (!(nested instanceof Map<?, ?> map)) {
                continue;
            }
            int value = intValue(map.get(targetKey), 0);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private int intValue(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> toEvidenceItem(ContextItem item) {
        if (item == null || item.getType() == null || item.getContent() == null || item.getContent().isBlank()) {
            return null;
        }
        Map<String, Object> metadata = item.getMetadata() != null ? new LinkedHashMap<>(item.getMetadata()) : Map.of();
        String type = resolveEvidenceType(item.getType().getValue(), metadata);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("type", type);
        map.put("text", item.getContent().trim());
        map.put("score", item.getRelevanceScore());
        map.put("source", extractEvidenceSource(metadata));
        map.put("scope", metadata.getOrDefault("scope", null));
        map.put("entities", extractStringList(metadata.get("entities")));
        map.put("meta", metadata.get("meta") instanceof Map<?, ?> meta ? castStringObjectMap(meta) : Map.of());
        return map;
    }

    private Map<String, Object> toChunkEvidenceItem(Map<String, Object> chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "text_chunk:" + chunk.getOrDefault("chapter", "") + ":" + chunk.getOrDefault("chunk_index", 0));
        map.put("type", "text_chunk");
        map.put("text", String.valueOf(chunk.getOrDefault("text", "")));
        map.put("score", chunk.get("score") instanceof Number n ? n.doubleValue() : 0.0);
        map.put("source", chunk.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of("chapter", chunk.getOrDefault("chapter", "")));
        map.put("scope", "chapter");
        map.put("entities", List.of());
        map.put("meta", Map.of());
        return map;
    }

    private String resolveEvidenceType(String rawType, Map<String, Object> metadata) {
        String evidenceType = metadata == null ? "" : String.valueOf(metadata.getOrDefault("evidence_type", "")).trim();
        if (!evidenceType.isBlank()) {
            return evidenceType;
        }
        String type = rawType == null ? "" : rawType.trim();
        if ("character_card".equals(type)) return "character";
        if ("world_card".equals(type)) return "world_entity";
        if ("chapter_summary".equals(type)) return "summary";
        if ("style_card".equals(type)) return "style";
        return type;
    }

    private Map<String, Object> extractEvidenceSource(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (Set.of("evidence_type", "scope", "entities", "meta").contains(key)) {
                continue;
            }
            source.put(key, entry.getValue());
        }
        return source;
    }

    private List<String> extractStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = item == null ? "" : item.toString().trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private Map<String, Object> castStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<Map<String, Object>> rerankChunkHits(List<Map<String, Object>> chunks, String query, int topK) {
        if (chunks == null || chunks.size() < 3 || query == null || query.isBlank()) {
            return chunks == null ? List.of() : chunks;
        }
        int k = Math.min(Math.max(3, topK), chunks.size());
        List<Map<String, Object>> candidates = new ArrayList<>(chunks.subList(0, k));

        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            String id = "c" + i;
            c.put("_rerank_id", id);
            String text = String.valueOf(c.getOrDefault("text", ""));
            String clipped = text.length() > 500 ? text.substring(0, 500) : text;
            payload.append(id).append(": ").append(clipped.replace("\n", " ")).append("\n");
        }

        String prompt = """
                请根据查询对候选段落进行相关性重排，仅返回 JSON：
                {"ranked_ids":["c2","c0","c1"]}
                查询：
                %s
                候选：
                %s
                """.formatted(query, payload);
        try {
            String provider = llmGateway.getProviderForAgent("writer");
            com.wenshape.llm.LlmResponse response = llmGateway.chatOnce(
                    List.of(Map.of("role", "user", "content", prompt)),
                    provider,
                    0.0,
                    RERANK_MAX_TOKENS
            );
            String content = response.getContent() == null ? "" : response.getContent().trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                content = content.substring(start, end + 1);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class);
            Object idsObj = json.get("ranked_ids");
            if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
                return cleanupRerankIds(chunks);
            }

            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> c : candidates) {
                byId.put(String.valueOf(c.get("_rerank_id")), c);
            }
            List<Map<String, Object>> reranked = new ArrayList<>();
            for (Object idObj : ids) {
                Map<String, Object> hit = byId.get(String.valueOf(idObj));
                if (hit != null) reranked.add(hit);
            }
            Set<String> used = reranked.stream().map(m -> String.valueOf(m.get("_rerank_id"))).collect(java.util.stream.Collectors.toSet());
            for (Map<String, Object> c : chunks) {
                String id = String.valueOf(c.getOrDefault("_rerank_id", ""));
                if (id.isBlank() || !used.contains(id)) {
                    reranked.add(c);
                }
            }
            return cleanupRerankIds(reranked);
        } catch (Exception e) {
            return cleanupRerankIds(chunks);
        }
    }

    private List<Map<String, Object>> cleanupRerankIds(List<Map<String, Object>> items) {
        if (items == null) return List.of();
        for (Map<String, Object> m : items) {
            if (m != null) m.remove("_rerank_id");
        }
        return items;
    }

    private String stringValue(Map<?, ?> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private List<Map<String, Object>> loadMemoryEvidence(String projectId, List<String> recentChapters,
                                                         List<String> seedEntities, String chapterGoal) {
        List<String> chapters = new ArrayList<>();
        if (recentChapters != null) chapters.addAll(recentChapters);
        if (!chapters.contains(currentChapter) && currentChapter != null) {
            chapters.add(currentChapter);
        }
        List<String> queries = new ArrayList<>();
        if (seedEntities != null) queries.addAll(seedEntities);
        if (chapterGoal != null && !chapterGoal.isBlank()) queries.add(chapterGoal);

        List<Map<String, Object>> items = new ArrayList<>();
        for (String chapter : chapters.stream().filter(Objects::nonNull).distinct().toList()) {
            Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
            if (pack == null || pack.isEmpty()) {
                continue;
            }
            items.addAll(extractMemoryItems(chapter, pack, queries));
        }
        return items;
    }

    private List<Map<String, Object>> loadCurrentChapterEvidence(String projectId,
                                                                 String chapter,
                                                                 List<String> seedEntities,
                                                                 String chapterGoal,
                                                                 SceneBrief sceneBrief) {
        if (projectId == null || projectId.isBlank() || chapter == null || chapter.isBlank()) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        if (seedEntities != null) {
            queries.addAll(seedEntities);
        }
        if (chapterGoal != null && !chapterGoal.isBlank()) {
            queries.add(chapterGoal);
        }
        if (sceneBrief != null && sceneBrief.getGoal() != null && !sceneBrief.getGoal().isBlank()) {
            queries.add(sceneBrief.getGoal());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            String brief = cleanMemoryText(summary.getBriefSummary());
            if (!brief.isBlank()) {
                items.add(currentChapterEvidenceItem("summary:" + chapter, "summary", brief, chapter, "chapter_summary", 4.6d, queries));
            }
            for (String event : summary.getKeyEvents() == null ? List.<String>of() : summary.getKeyEvents().stream().limit(3).toList()) {
                String cleaned = cleanMemoryText(event);
                if (!cleaned.isBlank()) {
                    items.add(currentChapterEvidenceItem("summary:" + chapter + ":event:" + cleaned.hashCode(), "summary", cleaned, chapter, "key_event", 4.2d, queries));
                }
            }
            for (String loop : summary.getOpenLoops() == null ? List.<String>of() : summary.getOpenLoops().stream().limit(2).toList()) {
                String cleaned = cleanMemoryText(loop);
                if (!cleaned.isBlank()) {
                    items.add(currentChapterEvidenceItem("summary:" + chapter + ":loop:" + cleaned.hashCode(), "summary", cleaned, chapter, "open_loop", 3.8d, queries));
                }
            }
        });

        for (com.wenshape.model.entity.Fact fact : canonStorage.listFacts(projectId)) {
            String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
            if (!chapter.equalsIgnoreCase(factChapter)) {
                continue;
            }
            String statement = cleanMemoryText(firstNonBlank(fact.getStatement(), fact.getContent()));
            if (statement.isBlank()) {
                continue;
            }
            items.add(currentChapterEvidenceItem(
                    "fact:" + chapter + ":" + statement.hashCode(),
                    "fact",
                    statement,
                    chapter,
                    "canon_fact",
                    5.2d,
                    queries
            ));
        }

        return items;
    }

    private Map<String, Object> currentChapterEvidenceItem(String id,
                                                           String type,
                                                           String text,
                                                           String chapter,
                                                           String field,
                                                           double baseScore,
                                                           List<String> queries) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("text", text);
        item.put("score", Math.max(baseScore, scoreText(queries, text) + 1.5d));
        item.put("source", Map.of("chapter", chapter, "field", field));
        item.put("scope", "chapter");
        item.put("entities", List.of());
        item.put("meta", Map.of("kind", "current_chapter"));
        return item;
    }

    private List<Map<String, Object>> filterMemoryEvidence(List<Map<String, Object>> items, List<String> queries) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (queries == null || queries.isEmpty()) {
            return items;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            double score = scoreGapSupport(queries, List.of(item));
            if (score <= 0) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(item);
            copy.put("score", Math.max(
                    copy.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                    score
            ));
            result.add(copy);
        }
        result.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        return result.size() > 6 ? new ArrayList<>(result.subList(0, 6)) : result;
    }

    private List<Map<String, Object>> extractMemoryItems(String chapter, Map<String, Object> pack, List<String> queries) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object payloadObj = pack.get("payload");
        if (payloadObj instanceof Map<?, ?> payload) {
            String workingMemory = stringValue(payload, "working_memory", "");
            if (!workingMemory.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":working_memory", chapter, workingMemory, "working_memory", queries));
            }
            Object evidencePack = payload.get("evidence_pack");
            if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> evidenceItems) {
                int count = 0;
                for (Object item : evidenceItems) {
                    if (item instanceof Map<?, ?> ev) {
                        String text = stringValue(ev, "text", "");
                        if (!text.isBlank()) {
                            items.add(memoryItem("memory:" + chapter + ":evidence:" + count, chapter, text, "evidence_pack", queries));
                            if (++count >= 4) break;
                        }
                    }
                }
            }
        }
        Object digestObj = pack.get("chapter_digest");
        if (digestObj instanceof Map<?, ?> digest) {
            String summary = stringValue(digest, "summary", "");
            if (!summary.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":summary", chapter, summary, "chapter_digest", queries));
            }
            String tail = stringValue(digest, "tail_excerpt", "");
            if (!tail.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":tail", chapter, tail, "tail_excerpt", queries));
            }
        }
        return items;
    }

    private Map<String, Object> memoryItem(String id, String chapter, String text, String field, List<String> queries) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "memory");
        item.put("text", text);
        item.put("score", scoreText(queries, text));
        item.put("source", Map.of("chapter", chapter, "field", field));
        item.put("scope", "chapter");
        item.put("entities", List.of());
        item.put("meta", Map.of());
        return item;
    }

    private List<String> extractSeedCharacters(String projectId,
                                               String chapter,
                                               SceneBrief sceneBrief,
                                               String chapterGoal,
                                               List<String> characterNames) {
        LinkedHashSet<String> characters = new LinkedHashSet<>();
        List<String> recentCharacterSeeds = filterHistoricalSeedsForCurrentChapter(
                projectId,
                chapter,
                loadBindingSeeds(projectId, chapter, 2, true, false),
                sceneBrief,
                chapterGoal
        );
        recentCharacterSeeds.stream()
                .filter(this::isValidSeedEntity)
                .forEach(characters::add);
        if (characterNames != null) {
            characterNames.stream().filter(this::isValidSeedEntity).map(String::trim).forEach(characters::add);
        }
        if (sceneBrief != null && sceneBrief.getCharacters() != null) {
            for (Map<String, String> character : sceneBrief.getCharacters()) {
                if (character == null) {
                    continue;
                }
                String name = character.get("name");
                if (isValidSeedEntity(name)) {
                    characters.add(name.trim());
                }
            }
        }
        collectCardMentions(projectId, compactChapterGoal(chapterGoal, sceneBrief), true, characters, 6);
        return characters.stream().limit(6).toList();
    }

    private List<String> extractSeedEntities(String projectId,
                                             String chapter,
                                             SceneBrief sceneBrief,
                                             String chapterGoal,
                                             List<String> characterNames) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        List<String> recentEntitySeeds = filterHistoricalSeedsForCurrentChapter(
                projectId,
                chapter,
                loadBindingSeeds(projectId, chapter, 2, true, true),
                sceneBrief,
                chapterGoal
        );
        recentEntitySeeds.stream()
                .filter(this::isValidSeedEntity)
                .forEach(entities::add);
        extractSeedCharacters(projectId, chapter, sceneBrief, chapterGoal, characterNames)
                .forEach(entities::add);
        collectCardMentions(projectId, compactChapterGoal(chapterGoal, sceneBrief), false, entities, 10);
        return entities.stream().limit(10).toList();
    }

    private List<String> filterHistoricalSeedsForCurrentChapter(String projectId,
                                                                String chapter,
                                                                List<String> seeds,
                                                                SceneBrief sceneBrief,
                                                                String chapterGoal) {
        if (seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        String signalText = buildCurrentChapterSignalText(projectId, chapter, sceneBrief, chapterGoal);
        boolean hasCurrentEvidence = hasCurrentChapterEvidence(projectId, chapter, sceneBrief, signalText);
        if (!hasCurrentEvidence) {
            return seeds.stream()
                    .filter(this::isValidSeedEntity)
                    .map(String::trim)
                    .distinct()
                    .toList();
        }

        Set<String> explicit = new LinkedHashSet<>();
        Map<String, Object> currentBinding = readChapterBindings(projectId, chapter);
        if (currentBinding != null && !currentBinding.isEmpty()) {
            normalizeBindingNameList(currentBinding.get("characters")).forEach(explicit::add);
            List<String> worlds = normalizeBindingNameList(currentBinding.get("world_entities"));
            if (worlds.isEmpty()) {
                worlds = normalizeBindingNameList(currentBinding.get("world"));
            }
            worlds.forEach(explicit::add);
        }
        extractCurrentSummarySubjects(projectId, chapter).forEach(explicit::add);

        List<String> filtered = new ArrayList<>();
        for (String seed : seeds) {
            String name = seed == null ? "" : seed.trim();
            if (!isValidSeedEntity(name)) {
                continue;
            }
            if (explicit.contains(name) || matchesEntityInSignalText(projectId, signalText, name)) {
                filtered.add(name);
            }
        }

        if (!filtered.isEmpty()) {
            return filtered.stream().distinct().toList();
        }
        if (!explicit.isEmpty()) {
            return explicit.stream().filter(this::isValidSeedEntity).limit(Math.min(6, explicit.size())).toList();
        }
        return seeds.stream()
                .filter(this::isValidSeedEntity)
                .map(String::trim)
                .distinct()
                .limit(Math.min(4, seeds.size()))
                .toList();
    }

    private Set<String> extractCurrentSummarySubjects(String projectId, String chapter) {
        LinkedHashSet<String> subjects = new LinkedHashSet<>();
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            if (summary.getCharacterStateChanges() != null) {
                for (String line : summary.getCharacterStateChanges()) {
                    String cleaned = cleanMemoryText(line);
                    int idx = cleaned.indexOf('：');
                    if (idx <= 0) {
                        continue;
                    }
                    String name = cleaned.substring(0, idx).trim();
                    if (isValidSeedEntity(name)) {
                        subjects.add(name);
                    }
                }
            }
        });
        for (Map<String, Object> state : loadCurrentChapterStates(projectId, chapter)) {
            String name = cleanMemoryText(String.valueOf(state.getOrDefault("character", "")));
            if (isValidSeedEntity(name)) {
                subjects.add(name);
            }
        }
        return subjects;
    }

    private boolean hasCurrentChapterEvidence(String projectId,
                                              String chapter,
                                              SceneBrief sceneBrief,
                                              String signalText) {
        if (signalText != null && !signalText.isBlank()) {
            return true;
        }
        if (sceneBrief != null) {
            if (sceneBrief.getFacts() != null && !sceneBrief.getFacts().isEmpty()) {
                return true;
            }
            if (sceneBrief.getCharacters() != null && !sceneBrief.getCharacters().isEmpty()) {
                return true;
            }
        }
        Map<String, Object> binding = readChapterBindings(projectId, chapter);
        if (binding != null && !binding.isEmpty()) {
            if (!normalizeBindingNameList(binding.get("characters")).isEmpty()) {
                return true;
            }
            List<String> worlds = normalizeBindingNameList(binding.get("world_entities"));
            if (worlds.isEmpty()) {
                worlds = normalizeBindingNameList(binding.get("world"));
            }
            if (!worlds.isEmpty()) {
                return true;
            }
        }
        return draftStorage.getFinalDraft(projectId, chapter).filter(text -> text != null && !text.isBlank()).isPresent()
                || draftStorage.getChapterSummary(projectId, chapter).isPresent();
    }

    private String buildCurrentChapterSignalText(String projectId,
                                                 String chapter,
                                                 SceneBrief sceneBrief,
                                                 String chapterGoal) {
        List<String> parts = new ArrayList<>();
        if (chapterGoal != null && !chapterGoal.isBlank()) {
            parts.add(chapterGoal);
        }
        if (sceneBrief != null) {
            if (sceneBrief.getGoal() != null && !sceneBrief.getGoal().isBlank()) {
                parts.add(sceneBrief.getGoal());
            }
            if (sceneBrief.getConflict() != null && !sceneBrief.getConflict().isBlank()) {
                parts.add(sceneBrief.getConflict());
            }
            if (sceneBrief.getKeyBeats() != null) {
                parts.addAll(sceneBrief.getKeyBeats());
            }
            if (sceneBrief.getTimelineContext() != null) {
                for (String key : List.of("current", "after")) {
                    String value = sceneBrief.getTimelineContext().getOrDefault(key, "");
                    if (value != null && !value.isBlank()) {
                        parts.add(value);
                    }
                }
            }
        }
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            if (summary.getTitle() != null && !summary.getTitle().isBlank()) {
                parts.add(summary.getTitle());
            }
            if (summary.getBriefSummary() != null && !summary.getBriefSummary().isBlank()) {
                parts.add(summary.getBriefSummary());
            }
            if (summary.getKeyEvents() != null) {
                parts.addAll(summary.getKeyEvents());
            }
            if (summary.getNewFacts() != null) {
                parts.addAll(summary.getNewFacts());
            }
            if (summary.getCharacterStateChanges() != null) {
                parts.addAll(summary.getCharacterStateChanges());
            }
            if (summary.getOpenLoops() != null) {
                parts.addAll(summary.getOpenLoops());
            }
        });
        for (com.wenshape.model.entity.Fact fact : canonStorage.listFacts(projectId)) {
            String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
            if (!chapter.equalsIgnoreCase(factChapter)) {
                continue;
            }
            String statement = cleanMemoryText(firstNonBlank(fact.getStatement(), fact.getContent()));
            if (!statement.isBlank()) {
                parts.add(statement);
            }
        }
        for (Map<String, Object> state : loadCurrentChapterStates(projectId, chapter)) {
            String character = cleanMemoryText(String.valueOf(state.getOrDefault("character", "")));
            String value = cleanMemoryText(String.valueOf(state.getOrDefault("state", "")));
            if (!character.isBlank()) {
                parts.add(character);
            }
            if (!value.isBlank()) {
                parts.add(value);
            }
        }
        Map<String, Object> currentBinding = readChapterBindings(projectId, chapter);
        if (currentBinding != null && !currentBinding.isEmpty()) {
            parts.addAll(normalizeBindingNameList(currentBinding.get("characters")));
            List<String> worlds = normalizeBindingNameList(currentBinding.get("world_entities"));
            if (worlds.isEmpty()) {
                worlds = normalizeBindingNameList(currentBinding.get("world"));
            }
            parts.addAll(worlds);
        }
        return cleanMemoryText(String.join("；", parts));
    }

    private boolean matchesEntityInSignalText(String projectId, String signalText, String entityName) {
        String text = signalText == null ? "" : signalText;
        String name = entityName == null ? "" : entityName.trim();
        if (text.isBlank() || name.isBlank()) {
            return false;
        }
        if (text.contains(name)) {
            return true;
        }
        for (String alias : loadCardAliases(projectId, name, true)) {
            if (alias != null && !alias.isBlank() && text.contains(alias)) {
                return true;
            }
        }
        for (String alias : loadCardAliases(projectId, name, false)) {
            if (alias != null && !alias.isBlank() && text.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildAnswerEvidenceItems(String chapter, List<Map<String, String>> userAnswers) {
        if (userAnswers == null || userAnswers.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (Map<String, String> answer : userAnswers) {
            if (answer == null) continue;
            String answerText = safeAnswerText(answer.get("answer"));
            if (answerText.isBlank() || isUnknownAnswer(answerText)) {
                continue;
            }
            String question = safeAnswerText(firstNonBlank(answer.get("question"), answer.get("text"), answer.get("key")));
            String questionKey = safeAnswerText(firstNonBlank(answer.get("key"), answer.get("question_key")));
            String answerKind = safeAnswerText(answer.get("type"));
            if (questionKey.isBlank()) {
                questionKey = makeQuestionKey(chapter, answerKind, question);
            }
            items.add(new LinkedHashMap<>(Map.of(
                    "id", "answer:" + chapter + ":" + index++,
                    "type", "memory",
                    "text", (question.isBlank() ? "" : question + "\n") + answerText,
                    "score", 10.0,
                    "source", Map.of(
                            "chapter", chapter,
                            "field", "user_answer",
                            "question", question,
                            "question_key", questionKey,
                            "kind", answerKind
                    ),
                    "meta", Map.of("kind", "user_answer")
            )));
        }
        return items;
    }

    private Set<String> answeredGapTextsFromAnswers(String chapter, List<Map<String, Object>> gaps, List<Map<String, String>> userAnswers) {
        Set<String> answered = new LinkedHashSet<>();
        if (gaps == null || gaps.isEmpty() || userAnswers == null || userAnswers.isEmpty()) {
            return answered;
        }
        for (Map<String, Object> gap : gaps) {
            String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
            if (gapText.isBlank()) continue;
            for (Map<String, String> answer : userAnswers) {
                if (matchesGapAnswer(gap, chapter, answer, false)) {
                    answered.add(gapText);
                    break;
                }
            }
        }
        return answered;
    }

    private Set<String> unknownGapTextsFromAnswers(String chapter, List<Map<String, Object>> gaps, List<Map<String, String>> userAnswers) {
        Set<String> unknown = new LinkedHashSet<>();
        if (gaps == null || gaps.isEmpty() || userAnswers == null || userAnswers.isEmpty()) {
            return unknown;
        }
        for (Map<String, Object> gap : gaps) {
            String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
            if (gapText.isBlank()) continue;
            for (Map<String, String> answer : userAnswers) {
                if (matchesGapAnswer(gap, chapter, answer, true)) {
                    unknown.add(gapText);
                    break;
                }
            }
        }
        return unknown;
    }

    private boolean matchesGapAnswer(Map<String, Object> gap, String chapter, Map<String, String> answer, boolean unknownOnly) {
        if (gap == null || answer == null) {
            return false;
        }
        String answerText = safeAnswerText(answer.get("answer"));
        if (unknownOnly) {
            if (!isUnknownAnswer(answerText)) {
                return false;
            }
        } else if (answerText.isBlank() || isUnknownAnswer(answerText)) {
            return false;
        }

        String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
        String gapKey = gapKey(gap, chapter);
        String answerKey = safeAnswerText(firstNonBlank(answer.get("key"), answer.get("question_key")));
        if (!gapKey.isBlank() && !answerKey.isBlank() && gapKey.equals(answerKey)) {
            return true;
        }

        String question = safeAnswerText(firstNonBlank(answer.get("question"), answer.get("text")));
        if (!question.isBlank() && !gapText.isBlank() && (question.contains(gapText) || gapText.contains(question))) {
            return true;
        }
        for (String query : queries) {
            if (query != null && !query.isBlank() && !question.isBlank()
                    && (question.contains(query) || query.contains(question))) {
                return true;
            }
        }
        return false;
    }

    private String safeAnswerText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isUnknownAnswer(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.isBlank()
                || normalized.equals("unknown")
                || normalized.equals("n/a")
                || normalized.equals("不知道")
                || normalized.equals("不清楚")
                || normalized.equals("未定");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<Map<String, String>> loadPersistedUserAnswers(String projectId, String chapter) {
        Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
        if (pack == null || pack.isEmpty()) {
            return List.of();
        }
        Object payloadObj = pack.get("payload");
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return List.of();
        }
        Object rawAnswers = payload.get("user_answers");
        if (!(rawAnswers instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> answers = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> answer = new LinkedHashMap<>();
                putIfNotBlank(answer, "type", stringValue(map, "type", ""));
                putIfNotBlank(answer, "question", stringValue(map, "question", ""));
                putIfNotBlank(answer, "text", stringValue(map, "text", ""));
                putIfNotBlank(answer, "key", stringValue(map, "key", ""));
                putIfNotBlank(answer, "question_key", stringValue(map, "question_key", ""));
                putIfNotBlank(answer, "answer", stringValue(map, "answer", ""));
                if (!answer.isEmpty()) {
                    answers.add(answer);
                }
            }
        }
        return answers;
    }

    private void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, Object> buildSufficiencyReport(List<Map<String, Object>> gaps,
                                                       Map<String, Boolean> gapSupported,
                                                       Map<String, Double> gapSupportScores,
                                                       List<Map<String, Object>> evidenceItems,
                                                       List<String> focusTerms,
                                                       Set<String> unknownGapTexts) {
        List<String> unresolved = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        List<String> criticalWeak = new ArrayList<>();
        List<String> missingEntities = new ArrayList<>();

        for (Map<String, Object> gap : gaps) {
            if (!Boolean.TRUE.equals(gap.getOrDefault("ask_user", Boolean.TRUE))) {
                continue;
            }
            String text = String.valueOf(gap.getOrDefault("text", "")).trim();
            if (text.isBlank()) {
                continue;
            }
            String gapId = gapSupportKey(gap);
            boolean supported = gapSupported.getOrDefault(gapId, false);
            double score = gapSupportScores.getOrDefault(gapId, 0.0);
            if (!supported) {
                if (!unresolved.contains(text)) unresolved.add(text);
                if (!missingEntities.contains(text)) missingEntities.add(text);
            } else if (score < (MIN_GAP_SUPPORT_SCORE + 0.8)) {
                if (!weak.contains(text)) weak.add(text);
                if (isFocusRelated(text, focusTerms) || "plot_point".equals(String.valueOf(gap.getOrDefault("kind", "")))) {
                    if (!criticalWeak.contains(text)) criticalWeak.add(text);
                }
            }
        }

        boolean insufficient = !unresolved.isEmpty() || !criticalWeak.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sufficient", !insufficient);
        report.put("needs_user_input", insufficient);
        report.put("missing_entities", missingEntities);
        report.put("weak_gaps", weak);
        report.put("critical_weak_gaps", criticalWeak);
        report.put("unknown_gaps", new ArrayList<>(unknownGapTexts));
        report.put("evidence_types", countTypesInEvidence(evidenceItems));
        return report;
    }

    private List<Map<String, Object>> selectQuestionGaps(List<Map<String, Object>> gaps,
                                                         Map<String, Boolean> gapSupported,
                                                         Map<String, Double> gapSupportScores,
                                                         List<String> focusTerms,
                                                         boolean forceMinimumQuestions) {
        List<Map<String, Object>> askable = gaps == null ? List.of() : gaps.stream()
                .filter(Objects::nonNull)
                .filter(gap -> Boolean.TRUE.equals(gap.getOrDefault("ask_user", Boolean.TRUE)))
                .toList();
        if (askable.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        java.util.function.Consumer<Map<String, Object>> addGap = gap -> {
            if (gap == null || selected.size() >= 3) {
                return;
            }
            String text = String.valueOf(gap.getOrDefault("text", "")).trim();
            if (!text.isBlank() && seen.add(text)) {
                selected.add(gap);
            }
        };

        if (forceMinimumQuestions) {
            for (Map<String, Object> gap : askable) {
                if ("plot_point".equals(String.valueOf(gap.getOrDefault("kind", "")).trim())) {
                    addGap.accept(gap);
                    break;
                }
            }
        }

        for (Map<String, Object> gap : askable) {
            if (selected.size() >= 3) {
                break;
            }
            String text = String.valueOf(gap.getOrDefault("text", "")).trim();
            String kind = String.valueOf(gap.getOrDefault("kind", "")).trim();
            if (text.isBlank()) {
                continue;
            }
            String gapId = gapSupportKey(gap);
            boolean supported = gapSupported.getOrDefault(gapId, false);
            double score = gapSupportScores.getOrDefault(gapId, 0.0);
            boolean focusRelated = isFocusRelated(text, focusTerms) || "plot_point".equals(kind);
            if (!supported || (score < (MIN_GAP_SUPPORT_SCORE + 0.8) && focusRelated)) {
                addGap.accept(gap);
            }
        }

        if (forceMinimumQuestions) {
            for (Map<String, Object> gap : askable) {
                if (selected.size() >= 3) {
                    break;
                }
                addGap.accept(gap);
            }
        }
        return selected;
    }

    private boolean shouldAskFallbackQuestions(Map<String, Object> writerContext) {
        if (writerContext == null || writerContext.isEmpty()) {
            return false;
        }
        String stopReason = String.valueOf(writerContext.getOrDefault("research_stop_reason", "")).trim();
        if (!"max_rounds".equals(stopReason)) {
            return false;
        }
        Object reportObj = writerContext.get("sufficiency_report");
        if (reportObj instanceof Map<?, ?> report) {
            Object needs = report.get("needs_user_input");
            return Boolean.TRUE.equals(needs);
        }
        return false;
    }

    private boolean shouldAskFollowupQuestions(Map<String, Object> writerContext) {
        return shouldAskFallbackQuestions(writerContext);
    }

    private List<Map<String, String>> buildQuestions(List<Map<String, Object>> gaps,
                                                     String chapter,
                                                     Set<String> unknownGapTexts) {
        List<Map<String, String>> questions = new ArrayList<>();
        for (Map<String, Object> gap : gaps) {
            if (questions.size() >= 3 || gap == null) {
                break;
            }
            String kind = String.valueOf(gap.getOrDefault("kind", "detail_gap")).trim();
            String text = String.valueOf(gap.getOrDefault("text", "")).trim();
            if (text.isBlank() || (unknownGapTexts != null && unknownGapTexts.contains(text))) {
                continue;
            }
            String question;
            if ("plot_point".equals(kind)) {
                question = "为达成本章目标，" + text + "？";
            } else if ("character_state".equals(kind) || "character_change".equals(kind)) {
                question = "角色方面：" + text + "？";
            } else {
                question = "细节方面：" + text + "？";
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("type", kind);
            item.put("text", question);
            item.put("key", gapKey(gap, chapter));
            item.put("reason", "证据不足，缺口：" + text);
            questions.add(item);
        }
        return questions;
    }

    private List<String> finalizePersistedPlannedQueries(String projectId,
                                                         String chapter,
                                                         SceneBrief sceneBrief,
                                                         List<String> rawQueries,
                                                         List<Map<String, Object>> unresolvedGaps,
                                                         List<String> focusTerms,
                                                         boolean sufficient) {
        List<String> queries = rawQueries == null ? List.of() : rawQueries;
        AdaptiveHintProfile hintProfile = resolveAdaptiveHintProfile(
                projectId,
                chapter,
                collectAdaptiveOverlayTexts(sceneBrief, queries, focusTerms)
        );
        List<String> localQueries = sanitizePlannedQueryList(
                deriveLocalPlannedQueries(projectId, chapter, sceneBrief, focusTerms, 3, hintProfile),
                hintProfile
        );
        List<String> compressedQueries = sanitizePlannedQueryList(
                compressPlannedQueries(queries, focusTerms, 4, hintProfile),
                hintProfile
        );
        if (hasReadablePlanningQueries(compressedQueries)) {
            return compressedQueries.stream().limit(4).toList();
        }
        boolean noUnresolvedGaps = unresolvedGaps == null || unresolvedGaps.isEmpty();
        if (sufficient && noUnresolvedGaps) {
            return sanitizePlannedQueryList(mergePlannedQueries(localQueries, compressedQueries, 4, hintProfile), hintProfile);
        }
        if (!localQueries.isEmpty() && shouldPreferLocalQueries(localQueries, compressedQueries, hintProfile)) {
            return sanitizePlannedQueryList(mergePlannedQueries(localQueries, compressedQueries, 4, hintProfile), hintProfile);
        }
        return sanitizePlannedQueryList(mergePlannedQueries(compressedQueries, localQueries, 4, hintProfile), hintProfile);
    }

    private List<String> deriveLocalPlannedQueries(String projectId,
                                                   String chapter,
                                                   SceneBrief sceneBrief,
                                                   List<String> focusTerms,
                                                   int limit,
                                                   AdaptiveHintProfile hintProfile) {
        if (limit <= 0) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            if (summary.getOpenLoops() != null) {
                for (String loop : summary.getOpenLoops()) {
                    String query = compactLocalQuery(loop, focusTerms, hintProfile);
                    if (!query.isBlank()) {
                        queries.add(query);
                    }
                    if (queries.size() >= limit) {
                        return;
                    }
                }
            }
            if (queries.size() < limit && summary.getCharacterStateChanges() != null) {
                for (String line : summary.getCharacterStateChanges()) {
                    String query = compactLocalQuery(line, focusTerms, hintProfile);
                    if (!query.isBlank()) {
                        queries.add(query);
                    }
                    if (queries.size() >= limit) {
                        return;
                    }
                }
            }
            if (queries.size() < limit && summary.getNewFacts() != null) {
                for (String fact : summary.getNewFacts()) {
                    String query = compactLocalQuery(fact, focusTerms, hintProfile);
                    if (!query.isBlank()) {
                        queries.add(query);
                    }
                    if (queries.size() >= limit) {
                        return;
                    }
                }
            }
        });
        if (queries.size() < limit && sceneBrief != null) {
            for (String beat : sceneBrief.getKeyBeats() == null ? List.<String>of() : sceneBrief.getKeyBeats()) {
                String query = compactLocalQuery(beat, focusTerms, hintProfile);
                if (!query.isBlank()) {
                    queries.add(query);
                }
                if (queries.size() >= limit) {
                    break;
                }
            }
        }
        if (queries.size() < limit && sceneBrief != null) {
            for (String fact : sceneBrief.getFacts() == null ? List.<String>of() : sceneBrief.getFacts()) {
                String query = compactLocalQuery(fact, focusTerms, hintProfile);
                if (!query.isBlank()) {
                    queries.add(query);
                }
                if (queries.size() >= limit) {
                    break;
                }
            }
        }
        return queries.stream().limit(limit).toList();
    }

    private List<String> mergePlannedQueries(List<String> primary,
                                             List<String> secondary,
                                             int limit,
                                             AdaptiveHintProfile hintProfile) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> first = primary == null ? List.of() : primary;
        List<String> second = secondary == null ? List.of() : secondary;
        if (first.isEmpty() && second.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Integer> sourceOrder = new LinkedHashMap<>();
        int index = 0;
        for (String query : first) {
            String cleaned = cleanMemoryText(query);
            if (!cleaned.isBlank() && !sourceOrder.containsKey(cleaned)) {
                sourceOrder.put(cleaned, index++);
            }
        }
        for (String query : second) {
            String cleaned = cleanMemoryText(query);
            if (!cleaned.isBlank() && !sourceOrder.containsKey(cleaned)) {
                sourceOrder.put(cleaned, index++);
            }
        }
        return sourceOrder.keySet().stream()
                .sorted((left, right) -> {
                    int scoreCompare = Double.compare(
                            scorePlannedQuery(right, hintProfile),
                            scorePlannedQuery(left, hintProfile)
                    );
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Integer.compare(sourceOrder.getOrDefault(left, Integer.MAX_VALUE),
                            sourceOrder.getOrDefault(right, Integer.MAX_VALUE));
                })
                .limit(limit)
                .toList();
    }

    private List<String> compressPlannedQueries(List<String> rawQueries,
                                                List<String> focusTerms,
                                                int limit,
                                                AdaptiveHintProfile hintProfile) {
        if (rawQueries == null || rawQueries.isEmpty() || limit <= 0) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String raw : rawQueries) {
            String compact = compactLocalQuery(raw, focusTerms, hintProfile);
            if (!compact.isBlank()) {
                queries.add(compact);
            }
            if (queries.size() >= limit) {
                break;
            }
        }
        return queries.stream().limit(limit).toList();
    }

    private boolean shouldPreferLocalQueries(List<String> localQueries,
                                             List<String> rawQueries,
                                             AdaptiveHintProfile hintProfile) {
        if (localQueries == null || localQueries.isEmpty()) {
            return false;
        }
        if (rawQueries == null || rawQueries.isEmpty()) {
            return true;
        }
        double localScore = localQueries.stream().mapToDouble(query -> scorePlannedQuery(query, hintProfile)).sum();
        double rawScore = rawQueries.stream().mapToDouble(query -> scorePlannedQuery(query, hintProfile)).sum();
        boolean localHasSemantic = localQueries.stream().anyMatch(query ->
                containsAdaptiveHint(query, hintProfile == null ? List.of() : hintProfile.queryHints()));
        boolean rawLooksWeak = rawQueries.stream().allMatch(query -> scorePlannedQuery(query, hintProfile) < 2.0d);
        return (localHasSemantic && rawLooksWeak) || localScore > rawScore + 1.2d;
    }

    private double scorePlannedQuery(String query, AdaptiveHintProfile hintProfile) {
        String cleaned = cleanMemoryText(query);
        if (cleaned.isBlank()) {
            return 0.0d;
        }
        if (looksLikeFragmentaryQuery(cleaned)) {
            return -1.0d;
        }
        double score = Math.min(cleaned.length() / 10.0d, 2.0d);
        if (containsAdaptiveHint(cleaned, hintProfile == null ? List.of() : hintProfile.queryHints())) {
            score += 1.5d;
        }
        if (containsAnyToken(cleaned, List.of("王方用", "为何", "仍未", "是否", "来历", "二字"))) {
            score -= 0.9d;
        }
        if (cleaned.split("\\s+").length <= 4) {
            score += 0.4d;
        }
        return score;
    }

    private List<String> sanitizePlannedQueryList(List<String> queries, AdaptiveHintProfile hintProfile) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String query : queries) {
            String normalized = cleanMemoryText(query);
            if (normalized.isBlank()) {
                continue;
            }
            if (looksLikeFragmentaryQuery(normalized)) {
                continue;
            }
            cleaned.add(normalized);
            if (cleaned.size() >= 6) {
                break;
            }
        }
        if (!cleaned.isEmpty()) {
            return new ArrayList<>(cleaned);
        }
        return queries.stream()
                .map(this::cleanMemoryText)
                .filter(text -> !text.isBlank())
                .filter(text -> !looksLikeFragmentaryQuery(text) || looksLikeResearchPlanningQuery(text))
                .distinct()
                .limit(6)
                .toList();
    }

    private boolean hasReadablePlanningQueries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return false;
        }
        return queries.stream()
                .map(this::cleanMemoryText)
                .anyMatch(query -> !query.isBlank()
                        && looksLikeResearchPlanningQuery(query)
                        && !looksLikeFragmentaryQuery(query));
    }

    private boolean looksLikeFragmentaryQuery(String query) {
        String cleaned = cleanMemoryText(query);
        if (cleaned.isBlank()) {
            return true;
        }
        if (looksLikeResearchPlanningQuery(cleaned)) {
            return false;
        }
        if (containsAnyToken(cleaned, List.of("情绪", "目标", "伤势", "持有")) && cleaned.contains(" ")) {
            return true;
        }
        List<String> parts = Arrays.stream(cleaned.split("\\s+"))
                .map(this::cleanMemoryText)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() < 2) {
            return false;
        }
        int overlapPairs = 0;
        for (int i = 0; i < parts.size() - 1; i++) {
            if (hasTokenOverlap(parts.get(i), parts.get(i + 1))) {
                overlapPairs++;
            }
        }
        boolean mostlyOverlapping = overlapPairs >= Math.max(1, parts.size() - 2);
        boolean lacksSemanticAnchor = !containsAdaptiveHint(cleaned, QUERY_COMPOUND_ANCHORS)
                && !containsAnyToken(cleaned, List.of("规则", "禁忌", "代价", "机关", "壁刻", "禁制", "古洞", "石缝", "石面", "异动", "气机", "遗迹"));
        return mostlyOverlapping && lacksSemanticAnchor;
    }

    private boolean hasTokenOverlap(String left, String right) {
        String a = cleanMemoryText(left).replaceAll("[：:，。、“”‘’（）()]", "");
        String b = cleanMemoryText(right).replaceAll("[：:，。、“”‘’（）()]", "");
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        int max = Math.min(Math.min(a.length(), b.length()), 4);
        for (int len = max; len >= 2; len--) {
            if (a.substring(a.length() - len).equals(b.substring(0, len))) {
                return true;
            }
        }
        return a.contains(b) || b.contains(a);
    }

    private String compactLocalQuery(String rawText,
                                     List<String> focusTerms,
                                     AdaptiveHintProfile hintProfile) {
        String cleaned = cleanMemoryText(rawText)
                .replaceAll("[？?！!]+$", "")
                .replaceFirst("^(请提供|请补充|请说明|请确认|请明确|关于|角色方面：|细节方面：|为达成本章目标，)", "")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }

        if (looksLikeResearchPlanningQuery(cleaned)) {
            return compactPlanningQuery(cleaned, hintProfile);
        }

        List<String> exactAdaptivePhrases = extractAdaptiveExactQueryPhrases(cleaned, hintProfile);
        if (!exactAdaptivePhrases.isEmpty()) {
            return String.join(" ", exactAdaptivePhrases.stream().limit(3).toList());
        }

        List<String> semanticPhrases = extractSemanticQueryPhrases(cleaned, hintProfile);
        if (!semanticPhrases.isEmpty()) {
            return String.join(" ", semanticPhrases.stream().limit(3).toList());
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : extractQueryTermsFromLocalText(cleaned, hintProfile)) {
            if (isUsefulQueryTerm(term)) {
                terms.add(term);
            }
            if (terms.size() >= 4) {
                break;
            }
        }
        if (focusTerms != null && terms.isEmpty()) {
            for (String term : focusTerms) {
                if (isUsefulQueryTerm(term) && cleaned.contains(term)) {
                    terms.add(term);
                }
                if (terms.size() >= 4) {
                    break;
                }
            }
        }
        if (!terms.isEmpty()) {
            return String.join(" ", terms.stream().limit(4).toList());
        }
        if (cleaned.length() <= 24) {
            return cleaned;
        }
        return truncateToBoundary(cleaned, 24);
    }

    private String compactPlanningQuery(String cleaned, AdaptiveHintProfile hintProfile) {
        boolean hasPlanningSemantics = containsAnyToken(cleaned, List.of(
                "规则", "禁忌", "代价", "机关", "变化", "方案", "阶段", "说明", "设计",
                "壁刻", "禁制", "关联", "影响", "触发条件", "演化"
        ));
        if (hasPlanningSemantics) {
            if (cleaned.length() <= 140) {
                return cleaned;
            }
            return truncateToBoundary(cleaned, 140);
        }
        List<String> exactHints = extractPlanningHintMatches(cleaned, hintProfile);
        if (exactHints.size() >= 2) {
            return String.join(" ", exactHints.stream().limit(4).toList());
        }
        if (cleaned.length() <= 120) {
            return cleaned;
        }
        return truncateToBoundary(cleaned, 120);
    }

    private List<String> extractPlanningHintMatches(String text, AdaptiveHintProfile hintProfile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (String hint : prioritizeQueryHints(hintProfile)) {
            String cleanedHint = cleanMemoryText(hint);
            if (cleanedHint.isBlank() || cleanedHint.length() < 3) {
                continue;
            }
            if (!text.contains(cleanedHint)) {
                continue;
            }
            if (!isUsefulQueryTerm(cleanedHint)) {
                continue;
            }
            matches.add(cleanedHint);
            if (matches.size() >= 6) {
                break;
            }
        }
        return new ArrayList<>(matches);
    }

    private List<String> extractQueryTermsFromLocalText(String text, AdaptiveHintProfile hintProfile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String exact : extractAdaptiveExactQueryPhrases(text, hintProfile)) {
            if (isUsefulQueryTerm(exact)) {
                terms.add(exact);
            }
            if (terms.size() >= 6) {
                return new ArrayList<>(terms);
            }
        }
        if (text.contains("拖擦") || text.contains("异响") || text.contains("脚步声")) {
            terms.add("异响");
        }
        if (text.contains("石面") || text.contains("狭缝") || text.contains("石门") || text.contains("入口")) {
            terms.add("入口");
        }
        if (text.contains("微光") || text.contains("青光") || text.contains("白光")) {
            terms.add("异常光源");
        }
        if (text.contains("寒气") || text.contains("气机") || text.contains("灵气") || text.contains("气息")) {
            terms.add("异常气机");
        }
        if (text.contains("谷底")) {
            terms.add("谷底");
        }
        if (text.contains("内伤") || text.contains("运气") || text.contains("经脉")) {
            terms.add("伤势变化");
        }
        if (text.contains("法器") && text.contains("遗失")) {
            terms.add("关键物遗失");
        }
        if (text.contains("未知") || text.contains("什么东西")) {
            terms.add("未知危险");
        }
        if (text.contains("阵纹") || text.contains("阵法") || text.contains("禁制") || text.contains("纹路")) {
            terms.add("阵法禁制");
        }
        if (text.contains("壁刻") || text.contains("人形图") || text.contains("修炼图") || text.contains("图谱")) {
            terms.add("壁刻图谱");
        }
        if (text.contains("传承") || text.contains("功法")) {
            terms.add("传承线索");
        }
        if (text.contains("闷堵") || text.contains("冲开")) {
            terms.add("伤势变化");
        }
        if ((text.contains("木牌") || text.contains("玉佩") || text.contains("令牌") || text.contains("钥匙"))
                && (text.contains("异动") || text.contains("轻响") || text.contains("发热") || text.contains("震"))) {
            terms.add("关键物反应");
        }
        if (hintProfile != null && hintProfile.queryHints() != null) {
            for (String hint : prioritizeQueryHints(hintProfile)) {
                if (hint != null && !hint.isBlank() && text.contains(hint)) {
                    terms.add(hint);
                }
                if (terms.size() >= 6) {
                    break;
                }
            }
        }
        for (String term : extractFocusTerms(text, 10)) {
            if (isUsefulQueryTerm(term)) {
                terms.add(term);
            }
            if (terms.size() >= 6) {
                break;
            }
        }
        return new ArrayList<>(terms);
    }

    private List<String> extractSemanticQueryPhrases(String text, AdaptiveHintProfile hintProfile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        phrases.addAll(extractAdaptiveExactQueryPhrases(text, hintProfile));
        if (text.contains("阵纹") || text.contains("阵法") || text.contains("禁制") || text.contains("纹路")) {
            phrases.add("阵法禁制");
        }
        if (text.contains("嗡") || text.contains("轻响") || text.contains("震")) {
            phrases.add("异常反应");
        }
        if (text.contains("灰白雾气") || text.contains("白雾") || text.contains("黑雾")) {
            phrases.add("异常雾气");
        }
        if (text.contains("壁刻") || text.contains("人形图") || text.contains("修炼图") || text.contains("图谱")) {
            phrases.add("壁刻图谱");
        }
        if (text.contains("传承") || text.contains("功法") || text.contains("古修")) {
            phrases.add("传承线索");
        }
        if (text.contains("寒气") || text.contains("气机") || text.contains("灵气") || text.contains("气息")) {
            phrases.add("异常气机");
        }
        if (text.contains("火烧火燎") || text.contains("闷堵") || text.contains("内伤") || text.contains("冲开")) {
            phrases.add("伤势变化");
        }
        if ((text.contains("木牌") || text.contains("玉佩") || text.contains("令牌") || text.contains("钥匙"))
                && (text.contains("异动") || text.contains("轻响") || text.contains("发热") || text.contains("震"))) {
            phrases.add("关键物反应");
        }
        if (text.contains("拖擦") || text.contains("异响") || text.contains("脚步声")) {
            phrases.add("异响");
        }
        if (text.contains("石面") || text.contains("狭缝") || text.contains("石门") || text.contains("入口")) {
            phrases.add("入口");
        }
        if (text.contains("微光") || text.contains("青光") || text.contains("白光")) {
            phrases.add("异常光源");
        }
        if (hintProfile != null && hintProfile.queryHints() != null) {
            for (String hint : prioritizeQueryHints(hintProfile)) {
                if (hint != null && !hint.isBlank() && text.contains(hint)) {
                    phrases.add(hint);
                }
                if (phrases.size() >= 6) {
                    break;
                }
            }
        }
        return new ArrayList<>(phrases);
    }

    private List<String> extractAdaptiveExactQueryPhrases(String text, AdaptiveHintProfile hintProfile) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        List<HanlpLexiconSupport.TokenInfo> tokens = HanlpLexiconSupport.tokenize(text);
        for (int window = 3; window >= 2; window--) {
            for (int i = 0; i <= tokens.size() - window; i++) {
                String candidate = buildCompoundQueryCandidate(tokens, i, window);
                if (candidate.isBlank() || !isUsefulCompoundQueryCandidate(candidate, hintProfile)) {
                    continue;
                }
                phrases.add(candidate);
                if (phrases.size() >= 6) {
                    return new ArrayList<>(phrases);
                }
            }
        }
        for (String hint : prioritizeQueryHints(hintProfile)) {
            if (hint != null && !hint.isBlank() && text.contains(hint) && isUsefulQueryTerm(hint)) {
                phrases.add(hint);
            }
            if (phrases.size() >= 6) {
                return new ArrayList<>(phrases);
            }
        }
        return new ArrayList<>(phrases);
    }

    private List<String> prioritizeQueryHints(AdaptiveHintProfile hintProfile) {
        if (hintProfile == null) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<String> projectTerms = hintProfile.projectTerms() == null ? List.of() : hintProfile.projectTerms();
        LinkedHashSet<String> normalizedQueryHints = new LinkedHashSet<>();
        List<String> queryHints = hintProfile.queryHints() == null ? List.of() : hintProfile.queryHints();
        queryHints.stream()
                .map(this::cleanMemoryText)
                .filter(term -> !term.isBlank())
                .forEach(normalizedQueryHints::add);

        projectTerms.stream()
                .map(this::cleanMemoryText)
                .filter(term -> !term.isBlank())
                .filter(term -> normalizedQueryHints.contains(term) || containsAdaptiveHint(term, QUERY_COMPOUND_ANCHORS))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .forEach(ordered::add);

        queryHints.stream()
                .map(this::cleanMemoryText)
                .filter(term -> !term.isBlank())
                .filter(term -> !GENERIC_QUERY_FALLBACK_TERMS.contains(term) || projectTerms.contains(term))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .forEach(ordered::add);

        queryHints.stream()
                .map(this::cleanMemoryText)
                .filter(term -> !term.isBlank())
                .filter(GENERIC_QUERY_FALLBACK_TERMS::contains)
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .forEach(ordered::add);
        return new ArrayList<>(ordered);
    }

    private String buildCompoundQueryCandidate(List<HanlpLexiconSupport.TokenInfo> tokens, int start, int window) {
        if (tokens == null || start < 0 || window <= 1 || start + window > tokens.size()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < start + window; i++) {
            String word = cleanMemoryText(tokens.get(i).word());
            if (word.isBlank() || QUERY_COMPOUND_TOKEN_STOPWORDS.contains(word)) {
                return "";
            }
            builder.append(word);
        }
        String candidate = builder.toString().trim();
        if (candidate.length() < 3 || candidate.length() > 8) {
            return "";
        }
        return candidate;
    }

    private boolean isUsefulCompoundQueryCandidate(String candidate, AdaptiveHintProfile hintProfile) {
        if (!isUsefulQueryTerm(candidate) || candidate.length() < 3 || candidate.length() > 8) {
            return false;
        }
        boolean hasAdaptiveSupport = containsAdaptiveHint(candidate, prioritizeQueryHints(hintProfile));
        boolean hasSemanticAnchor = containsAdaptiveHint(candidate, QUERY_COMPOUND_ANCHORS);
        if (!(hasAdaptiveSupport || hasSemanticAnchor)) {
            return false;
        }
        return !containsAnyToken(candidate, List.of(
                "为什么", "为何", "是否", "究竟", "什么", "哪里", "自己", "确认", "继续", "无法", "当前"
        ));
    }

    private boolean looksLikeResearchPlanningQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String cleaned = cleanMemoryText(text);
        if (cleaned.length() < 36) {
            return false;
        }
        String normalized = cleaned.replaceAll("\\s+", "");
        return normalized.contains("本章需")
                || normalized.contains("本章关键推进")
                || normalized.contains("本章需要")
                || normalized.contains("核心动机")
                || normalized.contains("心理状态")
                || normalized.contains("世界规则")
                || normalized.contains("禁忌")
                || normalized.contains("代价")
                || normalized.contains("触发条件")
                || normalized.contains("如何确保不偏离")
                || normalized.contains("哪一种")
                || normalized.contains("基于现有线索")
                || normalized.contains("请汇总")
                || normalized.contains("请提炼")
                || normalized.contains("请设计")
                || normalized.contains("请筛选")
                || normalized.contains("请基于")
                || normalized.contains("围绕哪些")
                || normalized.contains("分阶段说明")
                || normalized.contains("要求兼容")
                || cleaned.contains("→")
                || cleaned.contains("其中每一步")
                || cleaned.contains("例如");
    }

    private boolean isUsefulQueryTerm(String term) {
        String cleaned = cleanMemoryText(term);
        if (!isValidFocusTerm(cleaned)) {
            return false;
        }
        String normalized = cleaned.replaceAll("\\s+", "");
        return !List.of(
                "作品", "类型", "整体", "整体世界观", "世界观", "时代背景", "设定", "规则", "体系",
                "主线", "本章", "当前", "剧情", "上下文", "目标", "变化", "心理状态", "动机",
                "来历", "什么", "意味着", "情况", "方面", "角色", "细节", "需要", "是否", "希望",
                "为何", "仍未", "究竟", "到底", "王方", "王方用", "二字", "原本", "部分", "再次", "确认"
        ).contains(normalized);
    }

    private String makeQuestionKey(String chapter, String kind, String text) {
        String base = (chapter == null ? "" : chapter) + ":" + (kind == null ? "detail_gap" : kind) + ":" + (text == null ? "" : text);
        return base.replaceAll("\\s+", "_");
    }

    private String gapKey(Map<String, ?> gap, String chapter) {
        if (gap == null) {
            return makeQuestionKey(chapter, "", "");
        }
        String existing = String.valueOf(gap.containsKey("key") ? gap.get("key") : "").trim();
        if (!existing.isBlank()) {
            return existing;
        }
        String kind = String.valueOf(gap.containsKey("kind") ? gap.get("kind") : "").trim();
        String text = String.valueOf(gap.containsKey("text") ? gap.get("text") : "").trim();
        return makeQuestionKey(chapter, kind, text);
    }

    private boolean isFocusRelated(String text, List<String> focusTerms) {
        if (text == null || text.isBlank() || focusTerms == null || focusTerms.isEmpty()) {
            return false;
        }
        for (String term : focusTerms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildResearchTrace(List<Map<String, Object>> retrievalRequests,
                                                         String stopReason,
                                                         boolean sufficient) {
        List<Map<String, Object>> trace = new ArrayList<>();
        for (Map<String, Object> request : retrievalRequests) {
            if (request == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("round", intValue(request.get("round"), 1));
            entry.put("gap", request.get("gap"));
            entry.put("queries", request.getOrDefault("queries", List.of()));
            entry.put("count", request.getOrDefault("count", 0));
            entry.put("types", request.getOrDefault("types", Map.of()));
            entry.put("support_score", request.getOrDefault("support_score", 0.0));
            entry.put("skipped", request.getOrDefault("skipped", false));
            entry.put("reason", request.getOrDefault("reason", ""));
            entry.put("top_sources", request.getOrDefault("top_sources", List.of()));
            entry.put("stop_reason", stopReason);
            entry.put("note", switch (stopReason) {
                case "sufficient" -> "证据充分，结束研究";
                case "no_queries" -> "未生成新的有效检索查询";
                default -> sufficient ? "证据充分，结束研究" : "达到问题确认阶段";
            });
            trace.add(entry);
        }
        return trace;
    }

    private List<Map<String, String>> extractPayloadQuestions(Map<String, Object> context) {
        if (context == null) {
            return List.of();
        }
        Object raw = context.get("questions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> questions = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, String> question = new LinkedHashMap<>();
            putIfNotBlank(question, "type", stringValue(map, "type", ""));
            putIfNotBlank(question, "text", stringValue(map, "text", ""));
            putIfNotBlank(question, "key", stringValue(map, "key", ""));
            putIfNotBlank(question, "reason", stringValue(map, "reason", ""));
            putIfNotBlank(question, "source", stringValue(map, "source", ""));
            if (!question.isEmpty()) {
                questions.add(question);
            }
        }
        return questions;
    }

    private Set<String> extractAnsweredQuestionKeys(List<Map<String, String>> answers) {
        Set<String> keys = new LinkedHashSet<>();
        if (answers == null || answers.isEmpty()) {
            return keys;
        }
        for (Map<String, String> answer : answers) {
            if (answer == null) {
                continue;
            }
            String key = safeAnswerText(firstNonBlank(answer.get("key"), answer.get("question_key")));
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Set<String> extractAnsweredQuestionSignatures(List<Map<String, String>> answers) {
        Set<String> signatures = new LinkedHashSet<>();
        if (answers == null || answers.isEmpty()) {
            return signatures;
        }
        for (Map<String, String> answer : answers) {
            if (answer == null) {
                continue;
            }
            String type = safeAnswerText(answer.get("type"));
            String text = safeAnswerText(answer.get("question"));
            if (text.isBlank()) {
                text = safeAnswerText(answer.get("text"));
            }
            String signature = questionSignature(type, text);
            if (!signature.isBlank()) {
                signatures.add(signature);
            }
        }
        return signatures;
    }

    private List<Map<String, String>> applyQuestionPrefills(String projectId,
                                                            String chapter,
                                                            List<Map<String, String>> questions,
                                                            List<Map<String, String>> explicitAnswers) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> prefillCandidates = new ArrayList<>();
        if (explicitAnswers != null && !explicitAnswers.isEmpty()) {
            prefillCandidates.addAll(explicitAnswers);
        }
        List<Map<String, String>> persistedAnswers = loadPersistedUserAnswers(projectId, chapter);
        if (persistedAnswers != null && !persistedAnswers.isEmpty()) {
            prefillCandidates.addAll(persistedAnswers);
        }
        if (prefillCandidates.isEmpty()) {
            return questions;
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> question : questions) {
            if (question == null || question.isEmpty()) {
                continue;
            }
            Map<String, String> copy = new LinkedHashMap<>(question);
            String prefill = findQuestionPrefill(copy, prefillCandidates);
            if (!prefill.isBlank()) {
                copy.put("prefill_answer", prefill);
            }
            result.add(copy);
        }
        return result;
    }

    private String findQuestionPrefill(Map<String, String> question, List<Map<String, String>> answers) {
        if (question == null || question.isEmpty() || answers == null || answers.isEmpty()) {
            return "";
        }
        String questionKey = safeAnswerText(question.get("key"));
        String signature = questionSignature(
                safeAnswerText(question.get("type")),
                safeAnswerText(question.get("text"))
        );

        for (Map<String, String> answer : answers) {
            if (answer == null || answer.isEmpty()) {
                continue;
            }
            String answerText = safeAnswerText(answer.get("answer"));
            if (answerText.isBlank()) {
                continue;
            }
            String answerKey = safeAnswerText(firstNonBlank(answer.get("key"), answer.get("question_key")));
            if (!questionKey.isBlank() && !answerKey.isBlank() && questionKey.equals(answerKey)) {
                return answerText;
            }
        }

        for (Map<String, String> answer : answers) {
            if (answer == null || answer.isEmpty()) {
                continue;
            }
            String answerText = safeAnswerText(answer.get("answer"));
            if (answerText.isBlank()) {
                continue;
            }
            String answerSignature = questionSignature(
                    safeAnswerText(answer.get("type")),
                    safeAnswerText(firstNonBlank(answer.get("question"), answer.get("text")))
            );
            if (!signature.isBlank() && !answerSignature.isBlank() && signature.equals(answerSignature)) {
                return answerText;
            }
        }
        return "";
    }

    private String questionSignature(String type, String text) {
        String normalizedType = type != null ? type.trim() : "";
        String normalizedText = text != null ? text.trim() : "";
        if (normalizedType.isBlank() && normalizedText.isBlank()) {
            return "";
        }
        return normalizedType + "||" + normalizedText;
    }

    private List<Map<String, String>> resolvePreWritingQuestions(String projectId,
                                                                 String chapter,
                                                                 String chapterGoal,
                                                                 SceneBrief sceneBrief,
                                                                 Map<String, Object> writerContext,
                                                                 List<Map<String, String>> userAnswers,
                                                                 boolean allowFallbackLlm) {
        String compactGoal = compactChapterGoal(chapterGoal, sceneBrief);
        SceneBrief compactSceneBrief = compactSceneBrief(sceneBrief, compactGoal);
        List<Map<String, String>> payloadQuestions = extractPayloadQuestions(writerContext);
        List<Map<String, Object>> unresolvedGaps = extractPayloadUnresolvedGaps(writerContext);
        List<Map<String, String>> resolvedQuestions;

        if (!unresolvedGaps.isEmpty() && (payloadQuestions.isEmpty() || shouldRewritePayloadQuestions(payloadQuestions))) {
            List<Map<String, String>> rewrittenQuestions = writerAgent.generateQuestionsFromGaps(
                    compactSceneBrief,
                    compactGoal,
                    loadRecentSummaries(projectId, chapter),
                    unresolvedGaps,
                    userAnswers != null ? userAnswers : List.of()
            );
            if (rewrittenQuestions != null && !rewrittenQuestions.isEmpty()) {
                resolvedQuestions = rewrittenQuestions;
            } else {
                resolvedQuestions = payloadQuestions == null ? List.of() : payloadQuestions;
            }
        } else if ((payloadQuestions == null || payloadQuestions.isEmpty())
                && (unresolvedGaps == null || unresolvedGaps.isEmpty())
                && allowFallbackLlm) {
            resolvedQuestions = writerAgent.generateQuestionsWithProject(
                    projectId,
                    compactSceneBrief,
                    compactGoal,
                    loadRecentSummaries(projectId, chapter)
            );
        } else {
            resolvedQuestions = payloadQuestions == null ? List.of() : payloadQuestions;
        }
        return applyQuestionPrefills(projectId, chapter, resolvedQuestions, userAnswers);
    }

    private List<ChapterSummary> loadRecentSummaries(String projectId, String chapter) {
        return draftStorage.listChapterSummaries(projectId, null)
                .stream()
                .filter(s -> compareChapterIds(s.getChapter(), chapter) < 0)
                .sorted((a, b) -> compareChapterIds(b.getChapter(), a.getChapter()))
                .limit(3)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        list -> {
                            java.util.Collections.reverse(list);
                            return list;
                        }
                ));
    }

    private List<Map<String, Object>> extractPayloadUnresolvedGaps(Map<String, Object> context) {
        if (context == null) {
            return List.of();
        }
        Object raw = context.get("unresolved_gaps");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> gap = new LinkedHashMap<>();
            String kind = stringValue(map, "kind", "");
            String text = stringValue(map, "text", "");
            if (!kind.isBlank()) {
                gap.put("kind", kind);
            }
            if (!text.isBlank()) {
                gap.put("text", text);
            }
            String key = stringValue(map, "key", "");
            if (!key.isBlank()) {
                gap.put("key", key);
            }
            String entityName = stringValue(map, "entity_name", "");
            if (!entityName.isBlank()) {
                gap.put("entity_name", entityName);
            }
            Object rawQueries = map.get("queries");
            if (rawQueries instanceof List<?> queries && !queries.isEmpty()) {
                gap.put("queries", extractStringList(queries));
            }
            if (map.get("support_score") instanceof Number n) {
                gap.put("support_score", n.doubleValue());
            }
            if (!gap.isEmpty()) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    private boolean shouldRewritePayloadQuestions(List<Map<String, String>> questions) {
        if (questions == null || questions.isEmpty()) {
            return false;
        }
        boolean hasNonTemplateSource = questions.stream()
                .map(q -> q.getOrDefault("source", ""))
                .anyMatch(source -> !source.isBlank() && !"gap_template".equals(source));
        if (hasNonTemplateSource) {
            return false;
        }
        return questions.stream().allMatch(this::isLowValueQuestion);
    }

    private boolean isLowValueQuestion(Map<String, String> question) {
        if (question == null) {
            return false;
        }
        String text = question.getOrDefault("text", "").trim();
        if (text.isBlank()) {
            return false;
        }
        if (text.startsWith("为达成本章目标，") || text.startsWith("角色方面：") || text.startsWith("细节方面：")) {
            return true;
        }
        return text.contains("关键推进点是什么")
                || text.contains("动机/状态是否有变化")
                || text.contains("已确立事实有哪些")
                || text.contains("世界规则/禁忌/代价有哪些")
                || text.contains("主要角色有哪些");
    }

    private List<Map<String, Object>> buildResearchGaps(String projectId,
                                                        String chapter,
                                                        SceneBrief sceneBrief,
                                                        String chapterGoal,
                                                        List<String> seedCharacters) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        String effectiveGoal = compactChapterGoal(chapterGoal, sceneBrief);
        List<String> goalQueries = compactGoalQueries(effectiveGoal, sceneBrief, GOAL_QUERY_LIMIT);
        if (!goalQueries.isEmpty()) {
            gaps.add(gapItem(
                    chapter,
                    "plot_point",
                    "围绕章节目标的关键推进点是什么（避免偏离目标）",
                    goalQueries,
                    true,
                    null
            ));
        }

        List<String> characterCandidates = new ArrayList<>();
        for (String seedEntity : seedCharacters == null ? List.<String>of() : seedCharacters) {
            if (seedEntity != null && !seedEntity.isBlank() && !characterCandidates.contains(seedEntity.trim())) {
                characterCandidates.add(seedEntity.trim());
            }
        }
        if (characterCandidates.isEmpty() && sceneBrief != null && sceneBrief.getCharacters() != null) {
            String signalText = buildCurrentChapterSignalText(projectId, chapter, sceneBrief, chapterGoal);
            for (Map<String, String> character : sceneBrief.getCharacters()) {
                if (character == null) continue;
                String name = character.get("name");
                String cleaned = name == null ? "" : name.trim();
                if (!isValidSeedEntity(cleaned) || characterCandidates.contains(cleaned)) {
                    continue;
                }
                if (signalText.isBlank() || matchesEntityInSignalText(projectId, signalText, cleaned)) {
                    characterCandidates.add(cleaned);
                }
            }
        }

        if (characterCandidates.isEmpty()) {
            gaps.add(gapItem(
                    chapter,
                    "detail_gap",
                    "本章涉及的主要角色有哪些",
                    List.of("角色 人物 参与"),
                    true,
                    null
            ));
        } else {
            for (String name : characterCandidates.stream().limit(2).toList()) {
                gaps.add(gapItem(
                        chapter,
                        "character_change",
                        name + " 在本章的动机/状态是否有变化",
                        List.of(name + " 动机", name + " 状态"),
                        true,
                        name
                ));
            }
        }

        if (!hasTimelineContext(sceneBrief)) {
            gaps.add(gapItem(
                    chapter,
                    "detail_gap",
                    "本章时间/地点的具体边界是什么",
                    List.of("时间 地点 场景"),
                    true,
                    null
            ));
        }

        if (sceneBrief == null || sceneBrief.getWorldConstraints() == null || sceneBrief.getWorldConstraints().isEmpty()) {
            gaps.add(gapItem(
                    chapter,
                    "plot_point",
                    "本章需遵守的世界规则/禁忌/代价有哪些",
                    List.of("规则 禁忌 代价 限制"),
                    true,
                    null
            ));
        }
        if (sceneBrief == null || sceneBrief.getFacts() == null || sceneBrief.getFacts().isEmpty()) {
            gaps.add(gapItem(
                    chapter,
                    "detail_gap",
                    "与本章目标直接相关的已确立事实有哪些",
                    List.of("关键事实 已确立事实"),
                    true,
                    null
            ));
        }
        return uniqueGaps(gaps, 8);
    }

    private boolean hasTimelineContext(SceneBrief sceneBrief) {
        if (sceneBrief == null || sceneBrief.getTimelineContext() == null || sceneBrief.getTimelineContext().isEmpty()) {
            return false;
        }
        for (String key : List.of("before", "current", "after", "time", "location")) {
            String value = String.valueOf(sceneBrief.getTimelineContext().getOrDefault(key, "")).trim();
            if (!value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> gapItem(String chapter, String kind, String text, List<String> queries, boolean askUser, String entityName) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("kind", kind);
        gap.put("text", text);
        gap.put("queries", queries);
        gap.put("ask_user", askUser);
        gap.put("key", makeQuestionKey(chapter, kind, text));
        if (entityName != null && !entityName.isBlank()) {
            gap.put("entity_name", entityName);
        }
        return gap;
    }

    private List<Map<String, Object>> uniqueGaps(List<Map<String, Object>> gaps, int limit) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> gap : gaps) {
            if (gap == null) {
                continue;
            }
            String key = String.valueOf(gap.getOrDefault("kind", "")).trim() + "|"
                    + String.valueOf(gap.getOrDefault("text", "")).trim();
            if (seen.add(key)) {
                result.add(gap);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private List<String> buildPlannedQueries(List<Map<String, Object>> gaps, int limit) {
        List<String> queries = new ArrayList<>();
        if (gaps == null || gaps.isEmpty()) {
            return queries;
        }
        for (Map<String, Object> gap : gaps) {
            if (gap == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> gapQueries = (List<String>) gap.getOrDefault("queries", List.of());
            for (String query : gapQueries) {
                if (query != null && !query.isBlank() && !queries.contains(query.trim())) {
                    queries.add(query.trim());
                }
                if (queries.size() >= limit) {
                    return queries;
                }
            }
        }
        return queries;
    }

    private List<String> extractPlanQueries(Map<String, Object> plan,
                                            List<Map<String, Object>> gaps,
                                            int limit) {
        List<String> fallback = buildPlannedQueries(gaps, limit);
        if (plan == null || plan.isEmpty()) {
            return fallback;
        }
        Object raw = plan.get("queries");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return fallback;
        }
        List<String> queries = new ArrayList<>();
        for (Object item : list) {
            String query = String.valueOf(item != null ? item : "").trim();
            if (!query.isBlank() && !queries.contains(query)) {
                queries.add(query);
            }
            if (queries.size() >= limit) {
                break;
            }
        }
        return queries.isEmpty() ? fallback : queries;
    }

    private boolean isFirstChapter(String chapter) {
        try {
            int[] parsed = parseChapterId(chapter);
            return parsed[0] <= 1 && parsed[1] <= 1;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> recentChapterWindow(String projectId, String chapter, int window) {
        return draftStorage.listChapterSummaries(projectId, null).stream()
                .filter(summary -> compareChapterIds(summary.getChapter(), chapter) < 0)
                .sorted((a, b) -> compareChapterIds(b.getChapter(), a.getChapter()))
                .limit(window)
                .map(ChapterSummary::getChapter)
                .toList();
    }

    private double scoreGapSupport(List<String> queries, List<Map<String, Object>> evidenceItems) {
        if (queries == null || queries.isEmpty() || evidenceItems == null || evidenceItems.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (Map<String, Object> item : evidenceItems) {
            double score = item.get("score") instanceof Number n ? n.doubleValue() : 0.0;
            if (score <= 0) {
                continue;
            }
            String text = String.valueOf(item.getOrDefault("text", ""));
            if (!queryHits(text, queries)) {
                continue;
            }
            if (score > best) {
                best = score;
            }
        }
        return best;
    }

    private boolean queryHits(String text, List<String> queries) {
        if (text == null || text.isBlank() || queries == null || queries.isEmpty()) {
            return false;
        }
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            if (TextSearchUtils.calculateOverlapScore(query, text) > 0) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractQueryTerms(String text) {
        return new ArrayList<>(TextSearchUtils.tokenSet(text));
    }

    private int termOverlap(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String term : terms) {
            if (term != null && !term.isBlank() && TextSearchUtils.countTerm(text, term) > 0) {
                count++;
            }
        }
        return count;
    }

    private double scoreText(List<String> queries, String text) {
        if (queries == null || queries.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        return scoreGapSupport(
                queries,
                List.of(Map.of("text", text))
        );
    }

    private Map<String, Map<String, Integer>> buildGapQuotas(String gapKind) {
        Map<String, Map<String, Integer>> quotas = new LinkedHashMap<>();
        quotas.put("fact", quota(2, 6));
        quotas.put("text_chunk", quota(2, 6));
        quotas.put("character", quota(0, 4));
        quotas.put("world", quota(0, 3));
        quotas.put("memory", quota(0, 3));
        if ("world_rule".equals(gapKind)) {
            quotas.put("world", quota(1, 4));
            quotas.put("fact", quota(1, 4));
        } else if ("character_state".equals(gapKind) || "character_change".equals(gapKind)) {
            quotas.put("character", quota(1, 5));
            quotas.put("memory", quota(1, 4));
        }
        return quotas;
    }

    private Map<String, Map<String, Integer>> buildGlobalWriterQuotas() {
        Map<String, Map<String, Integer>> quotas = new LinkedHashMap<>();
        quotas.put("fact", quota(2, 6));
        quotas.put("text_chunk", quota(3, 6));
        quotas.put("character", quota(0, 4));
        quotas.put("world", quota(0, 3));
        quotas.put("memory", quota(0, 3));
        return quotas;
    }

    private Map<String, Integer> quota(int min, int max) {
        return Map.of("min", min, "max", max);
    }

    private List<Map<String, Object>> applyTypeQuotas(List<Map<String, Object>> scored,
                                                      Map<String, Map<String, Integer>> quotas,
                                                      int limit) {
        if (scored == null || scored.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (Map<String, Object> item : scored) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(item);
        }
        for (List<Map<String, Object>> items : byType.values()) {
            items.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0.0)).doubleValue()
            ));
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> usedIds = new LinkedHashSet<>();

        for (Map.Entry<String, Map<String, Integer>> entry : quotas.entrySet()) {
            String type = entry.getKey();
            int min = Math.max(entry.getValue().getOrDefault("min", 0), 0);
            List<Map<String, Object>> candidates = byType.getOrDefault(type, List.of());
            for (int i = 0; i < Math.min(min, candidates.size()) && selected.size() < limit; i++) {
                Map<String, Object> item = candidates.get(i);
                String id = String.valueOf(item.getOrDefault("id", ""));
                if (usedIds.add(id)) {
                    selected.add(item);
                    counts.put(type, counts.getOrDefault(type, 0) + 1);
                }
            }
        }

        List<Map<String, Object>> remaining = new ArrayList<>(scored);
        remaining.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        for (Map<String, Object> item : remaining) {
            if (selected.size() >= limit) break;
            String id = String.valueOf(item.getOrDefault("id", ""));
            if (!usedIds.add(id)) continue;
            String type = String.valueOf(item.getOrDefault("type", ""));
            int max = quotas.getOrDefault(type, Map.of()).getOrDefault("max", Integer.MAX_VALUE);
            if (counts.getOrDefault(type, 0) >= max) continue;
            selected.add(item);
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return selected;
    }

    private Map<String, Integer> countTypesInEvidence(List<Map<String, Object>> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            if (!type.isBlank()) {
                counts.put(type, counts.getOrDefault(type, 0) + 1);
            }
        }
        return counts;
    }

    private List<Map<String, Object>> extractTopSources(List<Map<String, Object>> items, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort((a, b) -> Double.compare(safeScore(b), safeScore(a)));
        for (Map<String, Object> item : sorted) {
            if (result.size() >= limit) break;
            if ("memory".equals(String.valueOf(item.getOrDefault("type", "")).trim())) {
                continue;
            }
            String text = String.valueOf(item.getOrDefault("text", "")).trim();
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
            Map<String, Object> sourceSummary = new LinkedHashMap<>();
            for (String key : List.of("chapter", "draft", "path", "paragraph", "field", "fact_id", "card", "introduced_in")) {
                Object value = source.get(key);
                if (value != null) {
                    sourceSummary.put(key, value);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", item.getOrDefault("type", ""));
            entry.put("score", safeScore(item));
            entry.put("snippet", text.length() > 80 ? text.substring(0, 80) : text);
            entry.put("source", sourceSummary);
            String key = entry.toString();
            if (seen.add(key)) {
                result.add(entry);
            }
        }
        return result;
    }

    private void populateContextCollections(List<Map<String, Object>> items,
                                            List<String> characterCards,
                                            List<String> worldCards,
                                            List<String> facts,
                                            List<Map<String, Object>> textChunks,
                                            List<String> focusTerms) {
        List<String> factCandidates = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            String text = String.valueOf(item.getOrDefault("text", ""));
            switch (type) {
                case "character" -> {
                    if (!text.isBlank() && characterCards.size() < 10 && !characterCards.contains(text)) characterCards.add(text);
                }
                case "world", "world_entity", "world_rule" -> {
                    if (!text.isBlank() && worldCards.size() < 10 && !worldCards.contains(text)) worldCards.add(text);
                }
                case "fact" -> {
                    if (!text.isBlank()) {
                        factCandidates.add(text);
                    }
                }
                case "text_chunk" -> {
                    if (!text.isBlank() && textChunks.size() < 8) {
                        Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
                        textChunks.add(Map.of(
                                "chapter", String.valueOf(source.getOrDefault("chapter", "")),
                                "text", text,
                                "score", item.getOrDefault("score", 0.0)
                        ));
                    }
                }
            }
        }
        facts.addAll(selectFocusFacts(factCandidates, focusTerms, 12));
    }

    private int createCardsFromProposals(String projectId, List<Map<String, Object>> proposals, boolean overwrite) {
        int created = 0;
        if (proposals == null) {
            return 0;
        }
        for (Map<String, Object> item : proposals) {
            if (item == null) {
                continue;
            }
            String type = String.valueOf(item.getOrDefault("type", "")).trim().toLowerCase();
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            if (name.isBlank()) {
                continue;
            }
            try {
                if ("character".equals(type)) {
                    if (!overwrite && cardStorage.getCharacterCard(projectId, name).isPresent()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> aliases = item.get("aliases") instanceof List<?> list
                            ? list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).distinct().toList()
                            : List.of();
                    Integer stars = item.get("stars") instanceof Number number ? number.intValue() : 1;
                    com.wenshape.model.entity.CharacterCard card = com.wenshape.model.entity.CharacterCard.builder()
                            .name(name)
                            .aliases(aliases)
                            .description(String.valueOf(item.getOrDefault("description", item.getOrDefault("source_text", ""))))
                            .stars(Math.max(1, Math.min(3, stars)))
                            .build();
                    cardStorage.saveCharacterCard(projectId, card);
                    created++;
                } else if ("world".equals(type)) {
                    if (!overwrite && cardStorage.getWorldCard(projectId, name).isPresent()) {
                        continue;
                    }
                    com.wenshape.model.entity.WorldCard card = com.wenshape.model.entity.WorldCard.builder()
                            .name(name)
                            .description(String.valueOf(item.getOrDefault("description", item.getOrDefault("source_text", ""))))
                            .category("analysis")
                            .build();
                    cardStorage.saveWorldCard(projectId, card);
                    created++;
                }
            } catch (Exception e) {
                log.warn("createCardsFromProposals 失败: {}", e.getMessage());
            }
        }
        return created;
    }

    /**
     * 构建 writer-facing working_memory。
     *
     * 相比旧版“证据摘抄”，这里更接近 Python 的组织方式：
     * - 本章目标
     * - 硬约束（world constraints / forbidden / 高分 world rule）
     * - 可用素材（按类型和分数筛选）
     * - 未解决缺口
     */
    private String buildWorkingMemory(String chapterGoal,
                                      SceneBrief sceneBrief,
                                      List<Map<String, Object>> evidenceItems,
                                      List<Map<String, Object>> unresolvedGaps,
                                      Set<String> answeredGapTexts,
                                      Set<String> unknownGapTexts) {
        String goal = compactChapterGoal(chapterGoal, sceneBrief);
        if (goal.isBlank()) {
            goal = "未提供";
        }

        List<String> lines = new ArrayList<>();
        lines.add("本章目标: " + goal);

        lines.add("硬约束:");
        List<String> constraintLines = new ArrayList<>();
        Map<String, String> ruleTextToCard = buildRuleTextToCard(evidenceItems);
        if (sceneBrief != null) {
            for (String item : sceneBrief.getWorldConstraints()) {
                String text = maybePrefixWorldRule(item, ruleTextToCard);
                if (!text.isBlank()) {
                    constraintLines.add(text);
                }
            }
            for (String item : sceneBrief.getForbidden()) {
                String text = cleanMemoryText(item);
                if (!text.isBlank()) {
                    constraintLines.add("禁忌: " + text);
                }
            }
        }
        constraintLines.addAll(selectEvidenceLines(evidenceItems, Set.of("world_rule"), 6, 140));
        constraintLines = dedupMaterialLines(constraintLines);
        if (constraintLines.isEmpty()) {
            lines.add("- 无");
        } else {
            for (String item : constraintLines.stream().limit(6).toList()) {
                lines.add("- " + item);
            }
        }

        List<String> focusTerms = buildWorkingMemoryFocusTerms(sceneBrief, goal);
        lines.add("可用素材:");
        List<String> materialLines = new ArrayList<>();
        List<String> factLines = selectFocusFactLines(evidenceItems, 8, 140, focusTerms);
        materialLines.addAll(factLines);
        String currentChapter = sceneBrief != null ? normalizeChapterId(sceneBrief.getChapter()) : "";
        List<String> currentChapterTextChunks = selectEvidenceLinesPreferChapter(
                evidenceItems,
                Set.of("text_chunk"),
                2,
                140,
                focusTerms,
                currentChapter,
                false
        );
        if (!currentChapterTextChunks.isEmpty()) {
            materialLines.addAll(currentChapterTextChunks);
        } else if (factLines.size() < 4) {
            materialLines.addAll(selectEvidenceLines(evidenceItems, Set.of("text_chunk"), 2, 140, focusTerms));
        }
        materialLines.addAll(selectEvidenceLines(evidenceItems, Set.of("character"), 4, 140));
        materialLines.addAll(selectEvidenceLines(evidenceItems, Set.of("world", "world_entity"), 4, 140));
        materialLines.addAll(selectEvidenceLines(evidenceItems, Set.of("memory", "chapter_summary", "summary"), 4, 140, focusTerms));
        if (sceneBrief != null) {
            for (String fact : selectFocusFacts(sceneBrief.getFacts(), focusTerms, 6)) {
                if (!fact.isBlank()) {
                    materialLines.add(fact);
                }
            }
            List<String> characterNames = sceneBrief.getCharacters() == null ? List.of() : sceneBrief.getCharacters().stream()
                    .map(character -> character != null ? String.valueOf(character.getOrDefault("name", "")).trim() : "")
                    .filter(name -> !name.isBlank())
                    .toList();
            if (!characterNames.isEmpty()) {
                materialLines.add("出场角色: " + String.join("、", characterNames));
            }
            if (sceneBrief.getTimelineContext() != null && !sceneBrief.getTimelineContext().isEmpty()) {
                List<String> timelineParts = new ArrayList<>();
                for (String key : List.of("current", "after", "before")) {
                    String value = cleanMemoryText(sceneBrief.getTimelineContext().getOrDefault(key, ""));
                    if (value.isBlank()) {
                        continue;
                    }
                    if ("before".equals(key) && termOverlap(value, focusTerms) < 2) {
                        continue;
                    }
                    timelineParts.add(key + "=" + value);
                    if (timelineParts.size() >= 3) {
                        break;
                    }
                }
                String summary = String.join("；", timelineParts);
                if (!summary.isBlank()) {
                    materialLines.add("时间线上下文: " + cleanMemoryText(summary));
                }
            }
        }
        materialLines = dedupMaterialLines(materialLines);
        if (materialLines.isEmpty()) {
            lines.add("- 无");
        } else {
            for (String item : materialLines.stream().limit(18).toList()) {
                lines.add("- " + item);
            }
        }

        lines.add("未解决缺口:");
        List<String> unresolvedLines = unresolvedGaps == null ? List.of() : unresolvedGaps.stream()
                .map(gap -> cleanMemoryText(String.valueOf(gap.getOrDefault("text", ""))))
                .filter(text -> !text.isBlank()
                        && (answeredGapTexts == null || !answeredGapTexts.contains(text))
                        && (unknownGapTexts == null || !unknownGapTexts.contains(text)))
                .distinct()
                .limit(6)
                .toList();
        if (unresolvedLines.isEmpty()) {
            lines.add("- 无");
        } else {
            for (String item : unresolvedLines) {
                lines.add("- " + item);
            }
        }

        String result = String.join("\n", lines).trim();
        return result.length() > goal.length() + 40 ? result : null;
    }

    private List<String> buildWorkingMemoryFocusTerms(SceneBrief sceneBrief, String goal) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (sceneBrief != null && sceneBrief.getCharacters() != null) {
            for (Map<String, String> character : sceneBrief.getCharacters()) {
                if (character == null) {
                    continue;
                }
                String name = character.get("name");
                if (name != null && !name.isBlank()) {
                    terms.add(name.trim());
                }
                if (terms.size() >= 8) {
                    break;
                }
            }
        }
        if (goal != null && !goal.isBlank()) {
            for (String term : extractFocusTerms(goal, 18)) {
                if (terms.size() >= 20) {
                    break;
                }
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    private List<String> selectEvidenceLines(List<Map<String, Object>> evidenceItems,
                                             Set<String> allowedTypes,
                                             int limit,
                                             int truncateAt) {
        return selectEvidenceLines(evidenceItems, allowedTypes, limit, truncateAt, List.of());
    }

    private List<String> selectEvidenceLines(List<Map<String, Object>> evidenceItems,
                                             Set<String> allowedTypes,
                                             int limit,
                                             int truncateAt,
                                             List<String> focusTerms) {
        if (evidenceItems == null || evidenceItems.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = evidenceItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> allowedTypes.contains(String.valueOf(item.getOrDefault("type", "")).trim()))
                .filter(this::shouldIncludeMaterial)
                .sorted((a, b) -> {
                    double focusA = focusScore(String.valueOf(a.getOrDefault("text", "")), focusTerms);
                    double focusB = focusScore(String.valueOf(b.getOrDefault("text", "")), focusTerms);
                    if (Double.compare(focusB, focusA) != 0) {
                        return Double.compare(focusB, focusA);
                    }
                    return Double.compare(safeScore(b), safeScore(a));
                })
                .limit(limit)
                .toList();

        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : candidates) {
            String text = formatEvidenceForMemory(item, truncateAt);
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return lines;
    }

    private List<String> selectEvidenceLinesPreferChapter(List<Map<String, Object>> evidenceItems,
                                                          Set<String> allowedTypes,
                                                          int limit,
                                                          int truncateAt,
                                                          List<String> focusTerms,
                                                          String preferredChapter,
                                                          boolean fallbackToAll) {
        if (preferredChapter == null || preferredChapter.isBlank()) {
            return fallbackToAll ? selectEvidenceLines(evidenceItems, allowedTypes, limit, truncateAt, focusTerms) : List.of();
        }
        List<Map<String, Object>> sameChapterItems = evidenceItems == null ? List.of() : evidenceItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> preferredChapter.equalsIgnoreCase(evidenceSourceChapter(item)))
                .toList();
        List<String> selected = selectEvidenceLines(sameChapterItems, allowedTypes, limit, truncateAt, focusTerms);
        if (!selected.isEmpty() || !fallbackToAll) {
            return selected;
        }
        return selectEvidenceLines(evidenceItems, allowedTypes, limit, truncateAt, focusTerms);
    }

    private List<String> selectFocusFactLines(List<Map<String, Object>> evidenceItems,
                                              int limit,
                                              int truncateAt,
                                              List<String> focusTerms) {
        if (evidenceItems == null || evidenceItems.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> candidates = evidenceItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> "fact".equals(String.valueOf(item.getOrDefault("type", "")).trim()))
                .filter(this::shouldIncludeMaterial)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        if (focusTerms == null || focusTerms.isEmpty()) {
            selected.addAll(candidates.stream().limit(limit).toList());
        } else {
            List<Map<String, Object>> ranked = new ArrayList<>(candidates);
            ranked.sort((a, b) -> {
                int overlapA = termOverlapCount(String.valueOf(a.getOrDefault("text", "")), focusTerms);
                int overlapB = termOverlapCount(String.valueOf(b.getOrDefault("text", "")), focusTerms);
                if (overlapA != overlapB) {
                    return Integer.compare(overlapB, overlapA);
                }
                int lengthA = String.valueOf(a.getOrDefault("text", "")).length();
                int lengthB = String.valueOf(b.getOrDefault("text", "")).length();
                if (lengthA != lengthB) {
                    return Integer.compare(lengthB, lengthA);
                }
                return Double.compare(safeScore(b), safeScore(a));
            });
            List<Map<String, Object>> focused = ranked.stream()
                    .filter(item -> termOverlapCount(String.valueOf(item.getOrDefault("text", "")), focusTerms) > 0)
                    .limit(limit)
                    .toList();
            if (!focused.isEmpty()) {
                selected.addAll(focused);
            } else {
                selected.addAll(candidates.stream().limit(Math.min(limit, 5)).toList());
            }
        }

        List<String> lines = new ArrayList<>();
        for (Map<String, Object> item : selected) {
            String text = formatEvidenceForMemory(item, truncateAt);
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return lines;
    }

    private List<String> selectFocusFacts(List<String> facts, List<String> focusTerms, int limit) {
        if (facts == null || facts.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<String> raw = facts.stream()
                .map(this::cleanMemoryText)
                .filter(text -> !text.isBlank())
                .toList();
        if (raw.isEmpty()) {
            return List.of();
        }
        if (focusTerms == null || focusTerms.isEmpty()) {
            return raw.stream().limit(limit).toList();
        }

        List<String> ranked = new ArrayList<>(raw);
        ranked.sort((a, b) -> {
            int overlapA = termOverlapCount(a, focusTerms);
            int overlapB = termOverlapCount(b, focusTerms);
            if (overlapA != overlapB) {
                return Integer.compare(overlapB, overlapA);
            }
            return Integer.compare(b.length(), a.length());
        });
        List<String> focused = ranked.stream()
                .filter(text -> termOverlapCount(text, focusTerms) > 0)
                .limit(limit)
                .toList();
        if (!focused.isEmpty()) {
            return focused;
        }
        return raw.stream().limit(Math.min(limit, 5)).toList();
    }

    private List<String> dedupMaterialLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        List<String> keptNorms = new ArrayList<>();
        for (String line : lines) {
            String cleaned = cleanMemoryText(line);
            if (cleaned.isBlank()) {
                continue;
            }
            String normalized = normalizeMaterialDedupKey(cleaned);
            if (normalized.isBlank()) {
                continue;
            }

            Integer duplicateIndex = null;
            for (int i = 0; i < keptNorms.size(); i++) {
                String existing = keptNorms.get(i);
                if (existing.contains(normalized) || normalized.contains(existing)) {
                    duplicateIndex = i;
                    break;
                }
            }

            if (duplicateIndex == null) {
                kept.add(cleaned);
                keptNorms.add(normalized);
                continue;
            }

            if (cleaned.length() > kept.get(duplicateIndex).length()) {
                kept.set(duplicateIndex, cleaned);
                keptNorms.set(duplicateIndex, normalized);
            }
        }
        return kept;
    }

    private String normalizeMaterialDedupKey(String text) {
        String cleaned = cleanMemoryText(text);
        if (cleaned.isBlank()) {
            return "";
        }
        String normalized = cleaned.replaceFirst("^(事实|片段|角色|设定|规则|记忆|摘要|文风)\\s*[:：]\\s*", "");
        normalized = normalized.replaceAll("[\\s\\-—–,，。；;、:：()（）\\[\\]{}<>\"“”'’]", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean shouldIncludeMaterial(Map<String, Object> item) {
        String type = String.valueOf(item.getOrDefault("type", "")).trim();
        String text = String.valueOf(item.getOrDefault("text", "")).trim();
        if (text.isBlank()) {
            return false;
        }
        Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
        String field = String.valueOf(source.getOrDefault("field", "")).trim();
        Map<String, Object> meta = item.get("meta") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
        String memoryKind = String.valueOf(meta.getOrDefault("kind", "")).trim();
        if ("memory".equals(type) && ("research_trace".equals(field) || "research_trace".equals(memoryKind) || "user_unknown".equals(memoryKind))) {
            return false;
        }
        return !text.startsWith("理由:") && !text.startsWith("aliases:") && !text.startsWith("immutable:");
    }

    private String evidenceSourceChapter(Map<String, Object> item) {
        if (item == null) {
            return "";
        }
        Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
        String chapter = String.valueOf(source.getOrDefault("chapter", "")).trim();
        return chapter.isBlank() ? "" : normalizeChapterId(chapter);
    }

    private String formatEvidenceForMemory(Map<String, Object> item, int truncateAt) {
        String type = String.valueOf(item.getOrDefault("type", "")).trim();
        String text = cleanMemoryText(String.valueOf(item.getOrDefault("text", "")));
        if (text.isBlank()) {
            return "";
        }
        Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
        String card = String.valueOf(source.getOrDefault("card", "")).trim();
        String field = String.valueOf(source.getOrDefault("field", "")).trim();

        if ("world_rule".equals(type) && !card.isBlank()) {
            if (!(text.startsWith(card + ":") || text.startsWith(card + "："))) {
                text = card + ": " + text;
            }
        } else if ("world".equals(type) || "world_entity".equals(type)) {
            if (!card.isBlank() && !(text.equals(card) || text.startsWith(card + ":") || text.startsWith(card + "："))) {
                text = card + ": " + text;
            }
        } else if ("character".equals(type) && !card.isBlank()) {
            String stripped = stripFieldPrefix(text, field);
            text = stripped.isBlank() ? card : card + ": " + stripped;
        } else if ("text_chunk".equals(type) || "summary".equals(type) || "chapter_summary".equals(type)) {
            text = truncateToBoundary(text, Math.min(truncateAt, 120));
        }

        String prefix = switch (type) {
            case "fact" -> "事实: ";
            case "text_chunk" -> "片段: ";
            case "character" -> "角色: ";
            case "world", "world_entity" -> "设定: ";
            case "world_rule" -> "规则: ";
            case "memory" -> "记忆: ";
            case "summary", "chapter_summary" -> "摘要: ";
            case "style" -> "文风: ";
            default -> "";
        };
        String result = prefix + text;
        return result.length() > truncateAt ? result.substring(0, truncateAt - 1) + "…" : result;
    }

    private String cleanMemoryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("<think>.*?</think>", "")
                .replaceAll("\\[[0-9]{1,3}]", "")
                .replaceAll("(?m)^(Title|Summary|Table|RawText)\\s*:\\s*.*$", "")
                .replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                .replaceAll("__(.*?)__", "$1")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \t]+", " ")
                .trim();
        int reasonIdx = cleaned.indexOf("理由:");
        if (reasonIdx >= 0) {
            cleaned = cleaned.substring(0, reasonIdx).trim();
        }
        cleaned = cleaned.replace("…", "")
                .replaceAll("\\.{3,}", ".")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private Map<String, String> buildRuleTextToCard(List<Map<String, Object>> items) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map<String, Object> item : items == null ? List.<Map<String, Object>>of() : items) {
            if (!"world_rule".equals(String.valueOf(item.getOrDefault("type", "")).trim())) {
                continue;
            }
            String text = cleanMemoryText(String.valueOf(item.getOrDefault("text", "")));
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
            String card = String.valueOf(source.getOrDefault("card", "")).trim();
            String key = normalizeText(text);
            if (!card.isBlank() && !key.isBlank() && !mapping.containsKey(key)) {
                mapping.put(key, card);
            }
        }
        return mapping;
    }

    private String maybePrefixWorldRule(String text, Map<String, String> ruleTextToCard) {
        String cleaned = cleanMemoryText(text);
        if (cleaned.isBlank() || ruleTextToCard == null || ruleTextToCard.isEmpty()) {
            return cleaned;
        }
        String card = ruleTextToCard.get(normalizeText(cleaned));
        if (card == null || card.isBlank()) {
            return cleaned;
        }
        if (cleaned.startsWith(card + ":") || cleaned.startsWith(card + "：")) {
            return cleaned;
        }
        return cleanMemoryText(card + ": " + cleaned);
    }

    private String stripFieldPrefix(String text, String field) {
        if (text == null) {
            return "";
        }
        String cleaned = text.trim();
        if (field == null || field.isBlank()) {
            return cleaned;
        }
        String prefix = field + ":";
        if (cleaned.startsWith(prefix)) {
            return cleaned.substring(prefix.length()).trim();
        }
        return cleaned;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    }

    private AdaptiveHintProfile resolveAdaptiveHintProfile(String projectId,
                                                           String chapter,
                                                           Collection<String> overlayTexts) {
        return adaptiveHintResolver.resolve(projectId, chapter, overlayTexts == null ? List.of() : overlayTexts);
    }

    private List<String> collectAdaptiveOverlayTexts(ChapterSummary summary,
                                                     List<Map<String, Object>> facts,
                                                     List<Map<String, Object>> characterStates) {
        List<String> overlays = new ArrayList<>();
        if (summary != null) {
            overlays.add(cleanMemoryText(summary.getBriefSummary()));
            if (summary.getKeyEvents() != null) {
                overlays.addAll(summary.getKeyEvents());
            }
            if (summary.getNewFacts() != null) {
                overlays.addAll(summary.getNewFacts());
            }
            if (summary.getOpenLoops() != null) {
                overlays.addAll(summary.getOpenLoops());
            }
            if (summary.getCharacterStateChanges() != null) {
                overlays.addAll(summary.getCharacterStateChanges());
            }
        }
        if (facts != null) {
            for (Map<String, Object> fact : facts) {
                overlays.add(cleanMemoryText(String.valueOf(fact.getOrDefault("statement", ""))));
            }
        }
        if (characterStates != null) {
            for (Map<String, Object> state : characterStates) {
                overlays.add(cleanMemoryText(String.valueOf(state.getOrDefault("state", ""))));
                overlays.add(cleanMemoryText(String.valueOf(state.getOrDefault("location", ""))));
            }
        }
        return overlays.stream().filter(text -> text != null && !text.isBlank()).toList();
    }

    private List<String> collectAdaptiveOverlayTexts(SceneBrief sceneBrief,
                                                     List<String> rawQueries,
                                                     List<String> focusTerms) {
        List<String> overlays = new ArrayList<>();
        if (sceneBrief != null) {
            overlays.add(cleanMemoryText(sceneBrief.getGoal()));
            if (sceneBrief.getFacts() != null) {
                overlays.addAll(sceneBrief.getFacts());
            }
            if (sceneBrief.getKeyBeats() != null) {
                overlays.addAll(sceneBrief.getKeyBeats());
            }
            if (sceneBrief.getWorldConstraints() != null) {
                overlays.addAll(sceneBrief.getWorldConstraints());
            }
        }
        if (rawQueries != null) {
            overlays.addAll(rawQueries);
        }
        if (focusTerms != null) {
            overlays.addAll(focusTerms);
        }
        return overlays.stream().map(this::cleanMemoryText).filter(text -> !text.isBlank()).toList();
    }

    private List<String> mergeHintLists(List<String> base, List<String> dynamic) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (base != null) {
            merged.addAll(base);
        }
        if (dynamic != null) {
            merged.addAll(dynamic);
        }
        return new ArrayList<>(merged);
    }

    private boolean containsAdaptiveHint(String text, List<String> hints) {
        return containsAnyToken(text, hints);
    }

    private boolean containsAnyToken(String text, List<String> tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String truncateToBoundary(String text, int maxLen) {
        if (text == null || text.isBlank() || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        String head = text.substring(0, maxLen);
        for (String punct : List.of("。", "！", "？", "；", ";", "，", "、", ".", ",")) {
            int idx = head.lastIndexOf(punct);
            if (idx >= Math.max(12, maxLen / 3)) {
                return head.substring(0, idx + 1).trim();
            }
        }
        return head.trim() + "…";
    }

    private double safeScore(Map<String, Object> item) {
        Object value = item == null ? null : item.get("score");
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double focusScore(String text, List<String> focusTerms) {
        if (text == null || text.isBlank() || focusTerms == null || focusTerms.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String term : focusTerms) {
            if (term != null && !term.isBlank() && TextSearchUtils.countTerm(text, term) > 0) {
                score += Math.min(2.0, 0.6 + term.length() * 0.08);
            }
        }
        return score;
    }

    private int termOverlapCount(String text, List<String> focusTerms) {
        if (text == null || text.isBlank() || focusTerms == null || focusTerms.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String term : focusTerms) {
            if (term != null && !term.isBlank() && TextSearchUtils.countTerm(text, term) > 0) {
                count++;
            }
        }
        return count;
    }

    private List<String> extractFocusTerms(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>(TextSearchUtils.tokenSet(text));
        candidates.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : candidates) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (!isValidFocusTerm(term)) {
                continue;
            }
            terms.add(term);
            if (terms.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(terms);
    }

    private SceneBrief compactSceneBrief(SceneBrief sceneBrief, String compactGoal) {
        if (sceneBrief == null) {
            return null;
        }
        SceneBrief copy = new SceneBrief();
        copy.setChapter(sceneBrief.getChapter());
        copy.setTitle(sceneBrief.getTitle());
        copy.setGoal(compactGoal != null && !compactGoal.isBlank() ? compactGoal : sceneBrief.getGoal());
        copy.setConflict(sceneBrief.getConflict());
        copy.setKeyBeats(sceneBrief.getKeyBeats() == null ? new ArrayList<>() : new ArrayList<>(sceneBrief.getKeyBeats()));
        copy.setCharacters(sceneBrief.getCharacters() == null ? new ArrayList<>() : new ArrayList<>(sceneBrief.getCharacters()));
        copy.setTimelineContext(sceneBrief.getTimelineContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sceneBrief.getTimelineContext()));
        copy.setWorldConstraints(sceneBrief.getWorldConstraints() == null ? new ArrayList<>() : new ArrayList<>(sceneBrief.getWorldConstraints()));
        copy.setFacts(sceneBrief.getFacts() == null ? new ArrayList<>() : new ArrayList<>(sceneBrief.getFacts()));
        copy.setStyleReminder(sceneBrief.getStyleReminder());
        copy.setForbidden(sceneBrief.getForbidden() == null ? new ArrayList<>() : new ArrayList<>(sceneBrief.getForbidden()));
        return copy;
    }

    private String normalizeChapterGoalForCurrentChapter(String chapter, String chapterGoal) {
        String raw = chapterGoal == null ? "" : chapterGoal.trim();
        if (raw.isBlank()) {
            return raw;
        }

        String normalizedChapter = chapter == null ? "" : chapter.trim().toUpperCase(Locale.ROOT);
        String normalizedText = raw.replace("\r\n", "\n").replace('\r', '\n');

        if (!normalizedChapter.isBlank()) {
            String escapedChapter = java.util.regex.Pattern.quote(normalizedChapter);
            java.util.regex.Pattern chapterOutlinePattern = java.util.regex.Pattern.compile(
                    "(?is)\\[章节细纲\\s*" + escapedChapter + "\\]\\s*(.*?)(?=\\n\\[[^\\n\\]]+\\]|\\z)"
            );
            java.util.regex.Matcher matcher = chapterOutlinePattern.matcher(normalizedText);
            if (matcher.find()) {
                String chapterOutline = cleanMemoryText(matcher.group(1));
                if (!chapterOutline.isBlank()) {
                    return chapterOutline;
                }
            }
        }

        if (looksLikePlanningBlob(raw)) {
            String compact = compactChapterGoal(raw, null);
            if (!compact.isBlank()) {
                return compact;
            }
        }

        return raw;
    }

    private String compactChapterGoal(String chapterGoal, SceneBrief sceneBrief) {
        String raw = firstNonBlank(
                chapterGoal != null ? chapterGoal.trim() : "",
                sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal().trim() : ""
        );
        if (raw.isBlank()) {
            return "";
        }
        if (looksLikePlanningBlob(raw)) {
            List<String> briefBeats = extractSceneBriefGoalBeats(sceneBrief, GOAL_BEAT_LIMIT);
            if (!briefBeats.isEmpty()) {
                return truncateToBoundary(cleanMemoryText(String.join("；", briefBeats)), 180);
            }
        }
        List<String> beats = extractGoalBeats(raw, sceneBrief, GOAL_BEAT_LIMIT);
        if (!beats.isEmpty()) {
            return truncateToBoundary(cleanMemoryText(String.join("；", beats)), 180);
        }

        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n+")) {
            String candidate = normalizeGoalLine(line);
            if (candidate.isBlank() || isMetaInstructionLine(candidate)) {
                continue;
            }
            return truncateToBoundary(cleanMemoryText(candidate), 180);
        }

        String fallback = truncateToBoundary(cleanMemoryText(raw), 180);
        return isMetaInstructionLine(fallback) ? "" : fallback;
    }

    private List<String> compactGoalQueries(String chapterGoal, SceneBrief sceneBrief, int limit) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (looksLikePlanningBlob(chapterGoal)) {
            for (String beat : extractSceneBriefGoalBeats(sceneBrief, Math.max(limit, 1))) {
                String cleaned = truncateToBoundary(cleanMemoryText(beat), 48);
                if (!cleaned.isBlank()) {
                    queries.add(cleaned);
                }
                if (queries.size() >= limit) {
                    break;
                }
            }
        }
        for (String beat : extractGoalBeats(chapterGoal, sceneBrief, Math.max(limit, 1))) {
            String cleaned = truncateToBoundary(cleanMemoryText(beat), 48);
            if (!cleaned.isBlank()) {
                queries.add(cleaned);
            }
            if (queries.size() >= limit) {
                break;
            }
        }
        if (queries.isEmpty()) {
            String compactGoal = compactChapterGoal(chapterGoal, sceneBrief);
            if (!compactGoal.isBlank()) {
                queries.add(truncateToBoundary(compactGoal, 48));
            }
        }
        return queries.stream().limit(limit).toList();
    }

    private List<String> extractSceneBriefGoalBeats(SceneBrief sceneBrief, int limit) {
        if (sceneBrief == null || limit <= 0) {
            return List.of();
        }
        LinkedHashSet<String> beats = new LinkedHashSet<>();

        if (sceneBrief.getKeyBeats() != null) {
            for (String beat : sceneBrief.getKeyBeats()) {
                String cleaned = normalizeGoalLine(beat);
                if (!cleaned.isBlank() && !isMetaInstructionLine(cleaned) && looksLikeGoalBeat(cleaned, sceneBrief)) {
                    beats.add(truncateToBoundary(cleaned, 72));
                }
                if (beats.size() >= limit) {
                    return beats.stream().limit(limit).toList();
                }
            }
        }

        Map<String, String> timeline = sceneBrief.getTimelineContext();
        if (timeline != null) {
            for (String key : List.of("current", "after", "before")) {
                String value = cleanMemoryText(timeline.getOrDefault(key, ""));
                if (value.isBlank()) {
                    continue;
                }
                for (String piece : value.split("[；;。]")) {
                    String cleaned = normalizeGoalLine(piece);
                    if (!cleaned.isBlank() && !isMetaInstructionLine(cleaned) && looksLikeGoalBeat(cleaned, sceneBrief)) {
                        beats.add(truncateToBoundary(cleaned, 72));
                    }
                    if (beats.size() >= limit) {
                        return beats.stream().limit(limit).toList();
                    }
                }
            }
        }

        if (sceneBrief.getConflict() != null) {
            String conflict = normalizeGoalLine(sceneBrief.getConflict());
            if (!conflict.isBlank() && !isMetaInstructionLine(conflict) && looksLikeGoalBeat(conflict, sceneBrief)) {
                beats.add(truncateToBoundary(conflict, 72));
            }
        }
        return beats.stream().limit(limit).toList();
    }

    private List<String> extractGoalBeats(String chapterGoal, SceneBrief sceneBrief, int limit) {
        LinkedHashSet<String> beats = new LinkedHashSet<>();
        String raw = firstNonBlank(
                chapterGoal != null ? chapterGoal.trim() : "",
                sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal().trim() : ""
        );
        if (raw.isBlank()) {
            return List.of();
        }

        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n+");
        for (String line : lines) {
            String candidate = normalizeGoalLine(line);
            if (candidate.isBlank() || isMetaInstructionLine(candidate)) {
                continue;
            }
            if (!looksLikeGoalBeat(candidate, sceneBrief)) {
                continue;
            }
            beats.add(truncateToBoundary(candidate, 72));
            if (beats.size() >= limit) {
                break;
            }
        }
        if (!beats.isEmpty()) {
            return beats.stream().limit(limit).toList();
        }

        String[] sentences = raw.split("[\n。！？；;]+");
        for (String sentence : sentences) {
            String candidate = normalizeGoalLine(sentence);
            if (candidate.isBlank() || isMetaInstructionLine(candidate)) {
                continue;
            }
            if (candidate.length() < 6 || candidate.length() > 80) {
                continue;
            }
            beats.add(candidate);
            if (beats.size() >= limit) {
                break;
            }
        }
        return beats.stream().limit(limit).toList();
    }

    private String normalizeGoalLine(String line) {
        if (line == null) {
            return "";
        }
        String cleaned = line.replace('\u3000', ' ').trim();
        if (cleaned.isBlank()) {
            return "";
        }
        cleaned = cleaned.replaceFirst("^#{1,6}\\s*", "");
        cleaned = cleaned.replaceFirst("^[-*•]\\s*", "");
        cleaned = cleaned.replaceFirst("^[（(]?[0-9一二三四五六七八九十]+[）).、]\\s*", "");
        cleaned = cleaned.replaceFirst("^第[一二三四五六七八九十0-9]+[步章节]\\s*", "");
        cleaned = cleaned.replaceFirst("^(本章目标|章节目标|时间线上下文|当前阶段|事件要点|信息点|结尾事件|章节落点|场景)\\s*[:：]\\s*", "");
        return cleanMemoryText(cleaned);
    }

    private boolean looksLikeGoalBeat(String text, SceneBrief sceneBrief) {
        if (text == null || text.isBlank() || text.length() < 6 || text.length() > 100) {
            return false;
        }
        for (String hint : GOAL_BEAT_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return text.startsWith("让")
                || text.startsWith("把")
                || text.startsWith("在结尾")
                || text.startsWith("先")
                || text.contains("市场风向")
                || text.contains("需求上升");
    }

    private boolean isMetaInstructionLine(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            return true;
        }
        String normalized = cleaned.replaceAll("\\s+", "");
        for (String prefix : GOAL_META_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return normalized.contains("请严格依据以上大纲")
                || normalized.contains("先保证剧情推进与设定一致")
                || normalized.contains("未解决缺口")
                || normalized.contains("可用素材")
                || normalized.contains("写作任务")
                || normalized.contains("章节结构大纲")
                || normalized.contains("主角：")
                || normalized.contains("观测者/套利者")
                || normalized.contains("渠道商/加杠杆的赌徒")
                || normalized.matches(".*(身份|能力|代价|外在形象|真实逻辑|行为逻辑)[：:].*");
    }

    private boolean looksLikePlanningBlob(String text) {
        if (text == null) {
            return false;
        }
        String cleaned = text.trim();
        if (cleaned.length() > 400 && cleaned.contains("\n")) {
            return true;
        }
        String normalized = cleaned.replaceAll("\\s+", "");
        return normalized.contains("[总纲]")
                || normalized.contains("章节细纲")
                || normalized.contains("写作任务")
                || normalized.contains("硬约束")
                || normalized.contains("可用素材")
                || normalized.contains("未解决缺口")
                || normalized.contains("核心出场人物");
    }

    private List<String> loadBindingSeeds(String projectId,
                                          String chapter,
                                          int window,
                                          boolean includeCharacters,
                                          boolean includeWorldEntities) {
        if (projectId == null || projectId.isBlank() || chapter == null || chapter.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        for (String recentChapter : recentChapterWindow(projectId, chapter, window)) {
            Map<String, Object> binding = readChapterBindings(projectId, recentChapter);
            if (binding == null || binding.isEmpty()) {
                continue;
            }
            if (includeCharacters) {
                normalizeBindingNameList(binding.get("characters")).forEach(seeds::add);
            }
            if (includeWorldEntities) {
                List<String> worlds = normalizeBindingNameList(binding.get("world_entities"));
                if (worlds.isEmpty()) {
                    worlds = normalizeBindingNameList(binding.get("world"));
                }
                worlds.forEach(seeds::add);
            }
        }
        return new ArrayList<>(seeds);
    }

    private Map<String, Object> readChapterBindings(String projectId, String chapter) {
        String canonical = normalizeChapterId(chapter);
        Path bindingPath = draftStorage.getProjectPath(projectId)
                .resolve("index").resolve("chapters").resolve(canonical).resolve("bindings.yaml");
        Path legacyPath = draftStorage.getProjectPath(projectId)
                .resolve("drafts").resolve(canonical).resolve("bindings.yaml");
        Path pathToRead = Files.exists(bindingPath) ? bindingPath : (Files.exists(legacyPath) ? legacyPath : null);
        if (pathToRead == null) {
            return null;
        }
        try {
            return draftStorage.readYamlAsMap(pathToRead);
        } catch (IOException e) {
            log.warn("读取 bindings 失败: {}", pathToRead, e);
            return null;
        }
    }

    private List<String> normalizeBindingNameList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String text) {
                if (isValidSeedEntity(text)) {
                    names.add(text.trim());
                }
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                String name = String.valueOf(map.containsKey("name") ? map.get("name") : "").trim();
                if (isValidSeedEntity(name)) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    private void collectCardMentions(String projectId,
                                     String text,
                                     boolean characterMode,
                                     LinkedHashSet<String> target,
                                     int limit) {
        if (projectId == null || projectId.isBlank() || text == null || text.isBlank() || target == null || target.size() >= limit) {
            return;
        }
        List<String> names = characterMode ? cardStorage.listCharacterCards(projectId) : cardStorage.listWorldCards(projectId);
        names.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(this::isValidSeedEntity)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(name -> {
                    if (target.size() >= limit || !isValidSeedEntity(name)) {
                        return;
                    }
                    if (text.contains(name)) {
                        target.add(name);
                        return;
                    }
                    for (String alias : loadCardAliases(projectId, name, characterMode)) {
                        if (alias != null && !alias.isBlank() && text.contains(alias)) {
                            target.add(name);
                            break;
                        }
                    }
                });
    }

    private List<String> loadCardAliases(String projectId, String name, boolean characterMode) {
        try {
            if (characterMode) {
                return cardStorage.getCharacterCard(projectId, name)
                        .map(card -> card.getAliases() == null ? List.<String>of() : card.getAliases())
                        .orElse(List.of());
            }
            return cardStorage.getWorldCard(projectId, name)
                    .map(card -> card.getAliases() == null ? List.<String>of() : card.getAliases())
                    .orElse(List.of());
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isValidSeedEntity(String text) {
        if (text == null) {
            return false;
        }
        String candidate = text.trim();
        if (candidate.isBlank() || candidate.length() < 2 || candidate.length() > 24) {
            return false;
        }
        if (candidate.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        if (candidate.matches("(?i)v\\d+c\\d+(?:[ei]\\d+)?")) {
            return false;
        }
        if (candidate.matches("(?i)[a-z0-9_\\-]+")) {
            return false;
        }
        String normalized = candidate.replaceAll("[\\[\\]【】()（）:：\\-—_\\s]", "");
        if (normalized.isBlank() || META_ENTITY_STOPWORDS.contains(normalized)) {
            return false;
        }
        return !isMetaInstructionLine(candidate);
    }

    private boolean isValidFocusTerm(String term) {
        return isValidSeedEntity(term) && term.trim().length() <= 12;
    }

    private List<String> uniqueTextList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String item : items) {
            String text = cleanMemoryText(item);
            if (!text.isBlank() && seen.add(text)) {
                result.add(text);
            }
        }
        return result;
    }

    private int compareChapterIds(String a, String b) {
        try {
            int[] pa = parseChapterId(a);
            int[] pb = parseChapterId(b);
            if (pa[0] != pb[0]) return Integer.compare(pa[0], pb[0]);
            return Integer.compare(pa[1], pb[1]);
        } catch (Exception e) {
            return (a != null ? a : "").compareTo(b != null ? b : "");
        }
    }

    private int[] parseChapterId(String chapter) {
        if (chapter == null) return new int[]{1, 0};
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
            int ch = Integer.parseInt(upper.substring(cIdx + 1));
            return new int[]{vol, ch};
        }
        return new int[]{1, 0};
    }

    private void saveDraftReview(String projectId, String chapter, Draft draft) {
        if (draft == null || draft.getContent() == null || draft.getContent().isBlank()) {
            return;
        }
        try {
            String version = draft.getVersion() != null && !draft.getVersion().isBlank() ? draft.getVersion() : "current";
            draftStorage.saveReview(projectId, chapter, editorAgent.reviewDraft(projectId, chapter, version, draft.getContent()));
        } catch (Exception e) {
            log.warn("saveDraftReview 失败: {}", e.getMessage());
        }
    }

    private void persistMemoryPack(String projectId, String chapter, Map<String, Object> context, String source) {
        if (projectId == null || projectId.isBlank() || chapter == null || chapter.isBlank() || context == null) {
            return;
        }
        try {
            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("project_id", projectId);
            pack.put("chapter", chapter);
            pack.put("built_at", java.time.Instant.now().toString());
            pack.put("source", source);

            Map<String, Object> payload = new LinkedHashMap<>();
            for (String key : List.of(
                    "chapterGoal", "working_memory", "evidence_pack", "unresolved_gaps", "retrieval_requests",
                    "seed_entities", "seed_window", "gaps", "planned_queries", "planned_queries_round2", "planned_queries_all", "questions", "sufficiency_report",
                    "research_trace", "research_stop_reason", "text_chunks", "facts", "character_cards", "world_cards",
                    "user_answers", "userFeedback"
            )) {
                if (context.containsKey(key)) {
                    payload.put(key, context.get(key));
                }
            }
            pack.put("payload", payload);
            pack.put("chapter_digest", buildChapterDigest(projectId, chapter));
            memoryPackStorage.writePack(projectId, chapter, pack);
        } catch (Exception e) {
            log.warn("persistMemoryPack 失败: {}", e.getMessage());
        }
    }

    /**
     * 构建 context_debug 字段（对齐 Python 版返回给前端的调试信息）
     */
    private Map<String, Object> buildContextDebug(String projectId, String chapter) {
        Map<String, Object> debug = new LinkedHashMap<>();
        try {
            debug.put("character_cards", cardStorage.listCharacterCards(projectId).size());
            debug.put("world_cards", cardStorage.listWorldCards(projectId).size());
            debug.put("facts", canonStorage.listFacts(projectId).size());
            debug.put("chapter_summaries", draftStorage.listChapterSummaries(projectId, null).size());
            debug.put("chapter", chapter);
            debug.put("project_id", projectId);
            try {
                Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
                if (pack != null) {
                    debug.put("memory_pack_built_at", pack.getOrDefault("built_at", ""));
                    debug.put("memory_pack_source", pack.getOrDefault("source", ""));
                    Object payloadObj = pack.get("payload");
                    if (payloadObj instanceof Map<?, ?> payload) {
                        debug.put("working_memory", payload.get("working_memory"));
                        debug.put("gaps", payload.get("gaps"));
                        debug.put("unresolved_gaps", payload.get("unresolved_gaps"));
                        debug.put("planned_queries", payload.get("planned_queries"));
                        debug.put("planned_queries_round2", payload.get("planned_queries_round2"));
                        debug.put("planned_queries_all", payload.get("planned_queries_all"));
                        debug.put("seed_entities", payload.get("seed_entities"));
                        debug.put("seed_window", payload.get("seed_window"));
                        debug.put("retrieval_requests", payload.get("retrieval_requests"));
                        debug.put("research_trace", payload.get("research_trace"));
                        debug.put("research_stop_reason", payload.get("research_stop_reason"));
                        debug.put("sufficiency_report", payload.get("sufficiency_report"));
                        debug.put("questions", payload.get("questions"));
                        debug.put("evidence_pack", payload.get("evidence_pack"));
                        Object unresolved = payload.get("unresolved_gaps");
                        if (unresolved instanceof List<?> list) {
                            debug.put("unresolved_gaps_count", list.size());
                        }
                        Object evidencePack = payload.get("evidence_pack");
                        if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> items) {
                            debug.put("evidence_items", items.size());
                        }
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("buildContextDebug 失败: {}", e.getMessage());
        }
        return debug;
    }

    /**
     * 构建 memory pack payload（简化版，对齐 Python _prepare_memory_pack_payload 核心结构）
     */
    private Map<String, Object> buildMemoryPackPayload(String projectId, String chapter) {
        return buildMemoryPackPayload(projectId, chapter, "");
    }

    private Map<String, Object> buildMemoryPackPayload(String projectId, String chapter, String traceId) {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("project_id", projectId);
        pack.put("chapter", chapter);
        pack.put("built_at", java.time.Instant.now().toString());
        pack.put("source", "writer");
        boolean traceTiming = traceId != null && !traceId.isBlank();
        long totalStartedAt = System.nanoTime();
        Map<String, Long> stageTimings = traceTiming ? new LinkedHashMap<>() : null;
        try {
            long stageStartedAt = System.nanoTime();
            SceneBrief sceneBrief = buildMemoryPackSceneBrief(projectId, chapter);
            if (traceTiming) {
                stageTimings.put("build_scene_brief", elapsedMillis(stageStartedAt));
            }
            String goal = sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            stageStartedAt = System.nanoTime();
            Map<String, Object> payload = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of(), traceId);
            if (traceTiming) {
                stageTimings.put("prepare_writer_context", elapsedMillis(stageStartedAt));
            }
            payload.remove("sceneBrief");
            payload.remove("styleCard");
            payload.remove("previousSummaries");
            List<?> characterSnapshot = listValue(payload.get("character_cards"));
            List<?> worldSnapshot = listValue(payload.get("world_cards"));
            pack.put("payload", payload);
            stageStartedAt = System.nanoTime();
            pack.put("chapter_digest", buildChapterDigest(projectId, chapter));
            if (traceTiming) {
                stageTimings.put("chapter_digest", elapsedMillis(stageStartedAt));
            }
            stageStartedAt = System.nanoTime();
            pack.put("card_snapshot", Map.of(
                    "characters", characterSnapshot,
                    "world", worldSnapshot
            ));
            pack.put("character_cards_count", cardStorage.listCharacterCards(projectId).size());
            pack.put("world_cards_count", cardStorage.listWorldCards(projectId).size());
            pack.put("facts_count", canonStorage.listFacts(projectId).size());
            if (traceTiming) {
                stageTimings.put("snapshot_counts", elapsedMillis(stageStartedAt));
            }
        } catch (Exception e) {
            log.warn("buildMemoryPackPayload 失败: {}", e.getMessage());
        } finally {
            if (traceTiming) {
                stageTimings.put("total", elapsedMillis(totalStartedAt));
                log.info("buildMemoryPackPayload timing: trace={}, project={}, chapter={}, timings={}",
                        traceId,
                        projectId,
                        chapter,
                        formatTimingMap(stageTimings));
            }
        }
        return pack;
    }

    private SceneBrief buildMemoryPackSceneBrief(String projectId, String chapter) {
        SceneBrief base = draftStorage.getSceneBrief(projectId, chapter)
                .map(this::copySceneBrief)
                .orElse(SceneBrief.builder().chapter(chapter).build());
        base.setChapter(chapter);
        String preferredTitle = resolvePreferredChapterTitle(projectId, chapter, base.getTitle());
        if (!preferredTitle.isBlank()) {
            base.setTitle(preferredTitle);
        }

        ChapterSummary summary = draftStorage.getChapterSummary(projectId, chapter).orElse(null);
        Map<String, Object> binding = readChapterBindings(projectId, chapter);
        List<Map<String, Object>> chapterStates = loadCurrentChapterStates(projectId, chapter);
        List<String> chapterFacts = loadCurrentChapterFactStatements(projectId, chapter);
        List<String> alignedCharacters = deriveMemoryPackCharacters(base, summary, binding, chapterStates);
        if (!alignedCharacters.isEmpty()) {
            base.setCharacters(buildSceneBriefCharacterEntries(alignedCharacters, base, summary));
        }

        List<String> focusTerms = new ArrayList<>(alignedCharacters);
        if (summary != null) {
            if (summary.getBriefSummary() != null) {
                focusTerms.addAll(extractFocusTerms(summary.getBriefSummary(), 12));
            }
            if (summary.getKeyEvents() != null) {
                for (String event : summary.getKeyEvents()) {
                    focusTerms.addAll(extractFocusTerms(event, 8));
                }
            }
            if (summary.getOpenLoops() != null) {
                for (String loop : summary.getOpenLoops()) {
                    focusTerms.addAll(extractFocusTerms(loop, 6));
                }
            }
        }
        if (base.getGoal() != null && !base.getGoal().isBlank()) {
            focusTerms.addAll(extractFocusTerms(base.getGoal(), 12));
        }
        focusTerms = focusTerms.stream().filter(this::isReasonableFocusTerm).distinct().limit(18).toList();

        String alignedGoal = deriveMemoryPackGoal(base, summary, chapterFacts, chapterStates);
        if (!alignedGoal.isBlank()) {
            base.setGoal(alignedGoal);
        }
        base.setFacts(deriveMemoryPackFacts(base, summary, chapterFacts, chapterStates, focusTerms));
        base.setWorldConstraints(deriveMemoryPackConstraints(base, summary, chapterFacts, chapterStates, focusTerms));
        base.setTimelineContext(deriveMemoryPackTimelineContext(projectId, chapter, base, summary));
        if (summary != null && summary.getOpenLoops() != null && !summary.getOpenLoops().isEmpty()) {
            List<String> beats = new ArrayList<>();
            if (summary.getKeyEvents() != null) {
                beats.addAll(summary.getKeyEvents());
            }
            beats.addAll(summary.getOpenLoops());
            base.setKeyBeats(dedupMaterialLines(beats).stream().limit(6).toList());
        }
        return base;
    }

    private SceneBrief copySceneBrief(SceneBrief source) {
        if (source == null) {
            return SceneBrief.builder().build();
        }
        return SceneBrief.builder()
                .chapter(source.getChapter())
                .title(source.getTitle())
                .goal(source.getGoal())
                .conflict(source.getConflict())
                .keyBeats(source.getKeyBeats() == null ? new ArrayList<>() : new ArrayList<>(source.getKeyBeats()))
                .characters(source.getCharacters() == null ? new ArrayList<>() : new ArrayList<>(source.getCharacters()))
                .timelineContext(source.getTimelineContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getTimelineContext()))
                .worldConstraints(source.getWorldConstraints() == null ? new ArrayList<>() : new ArrayList<>(source.getWorldConstraints()))
                .facts(source.getFacts() == null ? new ArrayList<>() : new ArrayList<>(source.getFacts()))
                .styleReminder(source.getStyleReminder())
                .forbidden(source.getForbidden() == null ? new ArrayList<>() : new ArrayList<>(source.getForbidden()))
                .build();
    }

    private List<Map<String, Object>> loadCurrentChapterStates(String projectId, String chapter) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> state : canonStorage.listCharacterStates(projectId)) {
            if (state == null || state.isEmpty()) {
                continue;
            }
            String lastSeen = String.valueOf(state.getOrDefault("last_seen", state.getOrDefault("source", ""))).trim();
            if (!chapter.equalsIgnoreCase(lastSeen)) {
                continue;
            }
            result.add(new LinkedHashMap<>(state));
        }
        return result;
    }

    private List<String> loadCurrentChapterFactStatements(String projectId, String chapter) {
        List<String> facts = new ArrayList<>();
        for (com.wenshape.model.entity.Fact fact : canonStorage.listFacts(projectId)) {
            String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
            if (!chapter.equalsIgnoreCase(factChapter)) {
                continue;
            }
            String statement = cleanMemoryText(firstNonBlank(fact.getStatement(), fact.getContent()));
            if (!statement.isBlank()) {
                facts.add(statement);
            }
        }
        return dedupMaterialLines(facts);
    }

    private List<String> deriveMemoryPackCharacters(SceneBrief base,
                                                    ChapterSummary summary,
                                                    Map<String, Object> binding,
                                                    List<Map<String, Object>> chapterStates) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (binding != null) {
            normalizeBindingNameList(binding.get("characters")).forEach(names::add);
        }
        for (Map<String, Object> state : chapterStates == null ? List.<Map<String, Object>>of() : chapterStates) {
            String name = cleanMemoryText(String.valueOf(state.getOrDefault("character", "")));
            if (isValidSeedEntity(name)) {
                names.add(name);
            }
        }
        if (summary != null && summary.getCharacterStateChanges() != null) {
            for (String line : summary.getCharacterStateChanges()) {
                String cleaned = cleanMemoryText(line);
                int idx = cleaned.indexOf('：');
                if (idx > 0) {
                    String name = cleaned.substring(0, idx).trim();
                    if (isValidSeedEntity(name)) {
                        names.add(name);
                    }
                }
            }
        }
        if (names.isEmpty() && base != null && base.getCharacters() != null) {
            for (Map<String, String> character : base.getCharacters()) {
                if (character == null) {
                    continue;
                }
                String name = cleanMemoryText(String.valueOf(character.getOrDefault("name", "")));
                if (isValidSeedEntity(name)) {
                    names.add(name);
                }
            }
        }
        return names.stream().limit(6).toList();
    }

    private List<Map<String, String>> buildSceneBriefCharacterEntries(List<String> names,
                                                                      SceneBrief base,
                                                                      ChapterSummary summary) {
        if (names == null || names.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> existingTraits = new LinkedHashMap<>();
        if (base != null && base.getCharacters() != null) {
            for (Map<String, String> item : base.getCharacters()) {
                if (item == null) {
                    continue;
                }
                String name = cleanMemoryText(String.valueOf(item.getOrDefault("name", "")));
                String traits = cleanMemoryText(String.valueOf(item.getOrDefault("relevant_traits", "")));
                if (!name.isBlank() && !traits.isBlank()) {
                    existingTraits.put(name, traits);
                }
            }
        }
        Map<String, String> summaryTraits = new LinkedHashMap<>();
        if (summary != null && summary.getCharacterStateChanges() != null) {
            for (String line : summary.getCharacterStateChanges()) {
                String cleaned = cleanMemoryText(line);
                int idx = cleaned.indexOf('：');
                if (idx <= 0) {
                    continue;
                }
                String name = cleaned.substring(0, idx).trim();
                String traits = cleaned.substring(idx + 1).trim();
                if (!name.isBlank() && !traits.isBlank()) {
                    summaryTraits.putIfAbsent(name, traits);
                }
            }
        }

        List<Map<String, String>> items = new ArrayList<>();
        for (String name : names.stream().filter(this::isValidSeedEntity).distinct().toList()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", name);
            String traits = firstNonBlank(summaryTraits.get(name), existingTraits.get(name));
            if (!traits.isBlank()) {
                item.put("relevant_traits", truncateToBoundary(traits, 96));
            }
            items.add(item);
        }
        return items;
    }

    private String deriveMemoryPackGoal(SceneBrief base,
                                        ChapterSummary summary,
                                        List<String> chapterFacts,
                                        List<Map<String, Object>> chapterStates) {
        String existingGoal = base != null ? cleanMemoryText(base.getGoal()) : "";
        if (!existingGoal.isBlank() && summary == null) {
            return existingGoal;
        }
        if (summary == null) {
            return existingGoal;
        }
        List<String> parts = new ArrayList<>();
        if (summary.getKeyEvents() != null) {
            parts.addAll(summary.getKeyEvents().stream()
                    .map(this::cleanMemoryText)
                    .filter(text -> !text.isBlank())
                    .limit(2)
                    .toList());
        }
        if (parts.isEmpty() && chapterFacts != null) {
            parts.addAll(chapterFacts.stream().limit(2).toList());
        }
        if (summary.getOpenLoops() != null && !summary.getOpenLoops().isEmpty()) {
            String loop = convertOpenLoopToGoalHint(summary.getOpenLoops().get(0));
            if (!loop.isBlank()) {
                parts.add(loop);
            }
        }
        if (parts.isEmpty() && chapterStates != null && !chapterStates.isEmpty()) {
            String state = cleanMemoryText(String.valueOf(chapterStates.get(0).getOrDefault("state", "")));
            if (!state.isBlank()) {
                parts.add(state);
            }
        }
        String derived = truncateToBoundary(cleanMemoryText(String.join("；", dedupMaterialLines(parts))), 180);
        if (existingGoal.isBlank()) {
            return derived;
        }
        if (derived.isBlank()) {
            return existingGoal;
        }
        int overlap = termOverlapCount(existingGoal, extractFocusTerms(derived, 16));
        if (looksLikeDirectiveWritingGoal(existingGoal) || overlap <= 1) {
            return derived;
        }
        return existingGoal;
    }

    private String convertOpenLoopToGoalHint(String openLoop) {
        String cleaned = cleanMemoryText(openLoop).replaceAll("[？?]+$", "").trim();
        if (cleaned.isBlank()) {
            return "";
        }

        String[] split = cleaned.split("[，,]", 2);
        List<String> parts = new ArrayList<>();
        String primary = normalizeOpenLoopClauseToGoalAction(split[0], true);
        if (!primary.isBlank()) {
            parts.add(primary);
        }
        if (split.length > 1) {
            String secondary = normalizeOpenLoopClauseToGoalAction(split[1], false);
            if (!secondary.isBlank()) {
                parts.add(secondary);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return truncateToBoundary(String.join("，并", parts), 96);
    }

    private String normalizeOpenLoopClauseToGoalAction(String clause, boolean primaryClause) {
        String cleaned = cleanMemoryText(clause).replaceAll("[？?]+$", "").trim();
        if (cleaned.isBlank()) {
            return "";
        }
        if (cleaned.startsWith("它是否")) {
            return "确认" + cleaned;
        }
        if (cleaned.startsWith("是否")) {
            return "确认" + cleaned;
        }
        if (cleaned.startsWith("谁在")) {
            return "查明" + cleaned;
        }
        if (cleaned.contains("究竟是什么")) {
            cleaned = cleaned.replace("究竟是什么", "的来源");
        } else if (cleaned.endsWith("是什么")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3) + "的真实情况";
        }
        if (cleaned.contains("究竟会")) {
            cleaned = cleaned.replace("究竟会", "会");
        }
        if (cleaned.contains("为何")) {
            cleaned = cleaned.replace("为何", "为什么");
        }
        if (cleaned.contains("它是否")) {
            return "确认" + cleaned;
        }
        if (cleaned.contains("是否")) {
            return primaryClause ? "查明" + cleaned : "确认" + cleaned;
        }
        if (cleaned.startsWith("查明") || cleaned.startsWith("确认") || cleaned.startsWith("追查")) {
            return cleaned;
        }
        return primaryClause ? "查明" + cleaned : "确认" + cleaned;
    }

    private List<String> deriveMemoryPackFacts(SceneBrief base,
                                               ChapterSummary summary,
                                               List<String> chapterFacts,
                                               List<Map<String, Object>> chapterStates,
                                               List<String> focusTerms) {
        List<String> candidates = new ArrayList<>();
        if (chapterFacts != null) {
            candidates.addAll(chapterFacts);
        }
        if (summary != null) {
            if (summary.getNewFacts() != null) {
                candidates.addAll(summary.getNewFacts());
            }
            if (summary.getKeyEvents() != null) {
                candidates.addAll(summary.getKeyEvents());
            }
            if (summary.getCharacterStateChanges() != null) {
                candidates.addAll(summary.getCharacterStateChanges());
            }
        }
        if (chapterStates != null) {
            for (Map<String, Object> state : chapterStates) {
                String character = cleanMemoryText(String.valueOf(state.getOrDefault("character", "")));
                String value = cleanMemoryText(String.valueOf(state.getOrDefault("state", "")));
                if (!character.isBlank() && !value.isBlank()) {
                    candidates.add(character + "：" + value);
                }
            }
        }
        if (candidates.isEmpty() && base != null && base.getFacts() != null) {
            candidates.addAll(base.getFacts());
        }
        return selectFocusFacts(dedupMaterialLines(candidates), focusTerms, 8);
    }

    private List<String> deriveMemoryPackConstraints(SceneBrief base,
                                                     ChapterSummary summary,
                                                     List<String> chapterFacts,
                                                     List<Map<String, Object>> chapterStates,
                                                     List<String> focusTerms) {
        List<String> candidates = new ArrayList<>();
        if (chapterFacts != null) {
            candidates.addAll(chapterFacts);
        }
        if (summary != null) {
            if (summary.getNewFacts() != null) {
                candidates.addAll(summary.getNewFacts());
            }
            if (summary.getCharacterStateChanges() != null) {
                candidates.addAll(summary.getCharacterStateChanges());
            }
        }
        if (chapterStates != null) {
            for (Map<String, Object> state : chapterStates) {
                String stateText = cleanMemoryText(String.valueOf(state.getOrDefault("state", "")));
                if (!stateText.isBlank()) {
                    candidates.add(stateText);
                }
            }
        }
        List<String> constraintish = new ArrayList<>();
        for (String candidate : dedupMaterialLines(candidates)) {
            String cleaned = cleanMemoryText(candidate);
            if (cleaned.isBlank()) {
                continue;
            }
            if (containsAnyToken(cleaned, List.of("无法", "不能", "不得", "必须", "只剩", "遗失", "见底", "匮乏", "牵动内伤", "求生"))) {
                constraintish.add(cleaned);
            }
        }
        if (constraintish.isEmpty() && base != null && base.getWorldConstraints() != null) {
            for (String candidate : selectFocusFacts(base.getWorldConstraints(), focusTerms, 4)) {
                String cleaned = cleanMemoryText(candidate);
                if (cleaned.isBlank()) {
                    continue;
                }
                if (summary != null && looksLikeDirectiveWritingGoal(cleaned)) {
                    continue;
                }
                if (summary != null && termOverlapCount(cleaned, focusTerms) <= 0) {
                    continue;
                }
                constraintish.add(cleaned);
            }
        }
        return dedupMaterialLines(constraintish).stream().limit(4).toList();
    }

    private boolean looksLikeDirectiveWritingGoal(String text) {
        String cleaned = cleanMemoryText(text);
        if (cleaned.isBlank()) {
            return false;
        }
        return containsAnyToken(cleaned, List.of(
                "承接", "描写", "营造", "通过", "让他", "让她", "重点描写", "不要立刻",
                "而非立刻", "氛围", "节奏", "开场写", "本章写", "本章需要", "突出", "随后通过"
        ));
    }

    private Map<String, String> deriveMemoryPackTimelineContext(String projectId,
                                                                String chapter,
                                                                SceneBrief base,
                                                                ChapterSummary summary) {
        LinkedHashMap<String, String> timeline = new LinkedHashMap<>();
        if (summary == null) {
            if (base != null && base.getTimelineContext() != null) {
                timeline.putAll(base.getTimelineContext());
            }
            return timeline;
        }
        ChapterSummary previous = latestPreviousSummary(projectId, chapter);
        if (previous != null && previous.getBriefSummary() != null && !previous.getBriefSummary().isBlank()) {
            timeline.put("before", truncateToBoundary(cleanMemoryText(previous.getBriefSummary()), 120));
        }
        String current = firstNonBlank(
                truncateToBoundary(cleanMemoryText(summary.getBriefSummary()), 140),
                base != null ? truncateToBoundary(cleanMemoryText(base.getGoal()), 140) : ""
        );
        if (!current.isBlank()) {
            timeline.put("current", current);
        }
        if (summary.getOpenLoops() != null && !summary.getOpenLoops().isEmpty()) {
            timeline.put("after", truncateToBoundary(cleanMemoryText(String.join("；", summary.getOpenLoops())), 140));
        } else if (base != null && base.getTimelineContext() != null) {
            String after = cleanMemoryText(base.getTimelineContext().getOrDefault("after", ""));
            if (!after.isBlank()) {
                timeline.put("after", truncateToBoundary(after, 140));
            }
        }
        return timeline;
    }

    private ChapterSummary latestPreviousSummary(String projectId, String chapter) {
        return draftStorage.listChapterSummaries(projectId, null).stream()
                .filter(summary -> compareChapterIds(summary.getChapter(), chapter) < 0)
                .max(Comparator.comparingInt(summary -> compareChapterIds(summary.getChapter(), chapter)))
                .orElse(null);
    }

    private boolean isReasonableFocusTerm(String term) {
        return term != null && !term.isBlank() && term.trim().length() >= 2 && term.trim().length() <= 18;
    }

    private Map<String, Object> buildChapterDigest(String projectId, String chapter) {
        Map<String, Object> digest = new LinkedHashMap<>();
        String chapterText = draftStorage.getFinalDraft(projectId, chapter)
                .or(() -> draftStorage.getLatestDraft(projectId, chapter).map(draft -> draft.getContent() != null ? draft.getContent() : ""))
                .orElse("");
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            digest.put("summary", summary.getBriefSummary() != null ? summary.getBriefSummary() : "");
            if (summary.getTitle() != null && !summary.getTitle().isBlank()) {
                digest.put("title", summary.getTitle());
            }
        });
        if (!chapterText.isBlank()) {
            String normalized = chapterText.replace("\r\n", "\n").replace('\r', '\n');
            digest.put("tail_excerpt", normalized.length() > 900 ? normalized.substring(normalized.length() - 900) : normalized);
        }
        List<String> topCharacters = deriveDigestMentions(projectId, chapter, chapterText, true, 6);
        if (!topCharacters.isEmpty()) {
            digest.put("top_characters", topCharacters);
        }
        List<String> topWorld = deriveDigestMentions(projectId, chapter, chapterText, false, 6);
        if (!topWorld.isEmpty()) {
            digest.put("top_world", topWorld);
        }
        if (!digest.containsKey("summary")) {
            String fallback = cleanMemoryText(chapterText);
            if (!fallback.isBlank()) {
                digest.put("summary", truncateToBoundary(fallback, 140));
            }
        }
        if (!digest.containsKey("title")) {
            String title = resolvePreferredChapterTitle(projectId, chapter, "");
            if (!title.isBlank()) {
                digest.put("title", title);
            }
        }
        return digest;
    }

    private List<String> deriveDigestMentions(String projectId,
                                              String chapter,
                                              String text,
                                              boolean characterMode,
                                              int limit) {
        LinkedHashSet<String> mentions = new LinkedHashSet<>();
        Map<String, Object> binding = readChapterBindings(projectId, chapter);
        if (binding != null) {
            Object raw = characterMode ? binding.get("characters") :
                    (binding.get("world_entities") != null ? binding.get("world_entities") : binding.get("world"));
            for (String name : normalizeBindingNameList(raw)) {
                if (isValidSeedEntity(name)) {
                    mentions.add(name);
                }
            }
        }
        if (mentions.size() >= limit) {
            return mentions.stream().limit(limit).toList();
        }
        if (text == null || text.isBlank()) {
            return mentions.stream().limit(limit).toList();
        }
        try {
            List<String> names = characterMode ? cardStorage.listCharacterCards(projectId) : cardStorage.listWorldCards(projectId);
            names.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(this::isValidSeedEntity)
                    .filter(name -> text.contains(name) || loadCardAliases(projectId, name, characterMode).stream().anyMatch(alias -> alias != null && !alias.isBlank() && text.contains(alias)))
                    .sorted((a, b) -> Integer.compare(TextSearchUtils.countTerm(text, b), TextSearchUtils.countTerm(text, a)))
                    .forEach(mentions::add);
        } catch (Exception e) {
            log.warn("deriveDigestMentions 失败: {}", e.getMessage());
        }
        return mentions.stream().limit(limit).toList();
    }

    public Map<String, Object> ensureMemoryPack(String projectId,
                                               String chapter,
                                               boolean forceRefresh,
                                               String source,
                                               String userFeedback,
                                               String chapterTextOverride) {
        if (projectId == null || projectId.isBlank() || chapter == null || chapter.isBlank()) {
            return null;
        }
        String normalizedChapter = canonicalizeChapterId(chapter);
        try {
            Map<String, Object> existing = memoryPackStorage.readPack(projectId, normalizedChapter);
            if (!forceRefresh && existing != null && !existing.isEmpty()) {
                return existing;
            }

            Map<String, Object> pack = buildMemoryPackPayload(projectId, normalizedChapter);
            pack.put("built_at", java.time.Instant.now().toString());
            pack.put("source", (source == null || source.isBlank()) ? "editor" : source);

            if (pack.get("payload") instanceof Map<?, ?> payload) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) payload;
                if (userFeedback != null && !userFeedback.isBlank()) {
                    p.put("userFeedback", userFeedback);
                }
            }

            if (chapterTextOverride != null && !chapterTextOverride.isBlank()) {
                pack.put("chapter_digest", buildChapterDigestFromText(projectId, normalizedChapter, chapterTextOverride));
            }

            memoryPackStorage.writePack(projectId, normalizedChapter, pack);
            return pack;
        } catch (Exception e) {
            log.warn("ensureMemoryPack 失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildChapterDigestFromText(String projectId, String chapter, String text) {
        Map<String, Object> digest = new LinkedHashMap<>(buildChapterDigest(projectId, chapter));
        if (text == null) {
            return digest;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isBlank()) {
            digest.put("tail_excerpt", normalized.length() > 900 ? normalized.substring(normalized.length() - 900) : normalized);
            List<String> topCharacters = deriveDigestMentions(projectId, chapter, normalized, true, 6);
            if (!topCharacters.isEmpty()) {
                digest.put("top_characters", topCharacters);
            }
            List<String> topWorld = deriveDigestMentions(projectId, chapter, normalized, false, 6);
            if (!topWorld.isEmpty()) {
                digest.put("top_world", topWorld);
            }
        }
        return digest;
    }

    private String canonicalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "";
        }
        String normalized = chapterId.trim().toUpperCase();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("V(\\d+)C(\\d+)").matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        return chapterId.trim();
    }
}
