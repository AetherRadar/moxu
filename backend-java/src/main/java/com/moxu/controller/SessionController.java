package com.moxu.controller;

import com.moxu.agent.EditorAgent;
import com.moxu.analysis.AnalysisJobRequest;
import com.moxu.analysis.AnalysisJobService;
import com.moxu.analysis.CausalAuditService;
import jakarta.validation.Valid;
import com.moxu.dto.*;
import com.moxu.orchestrator.Orchestrator;
import com.moxu.orchestrator.OrchestratorPool;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话控制器 - 写作会话管理
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/session")
@RequiredArgsConstructor
public class SessionController {
    
    private final OrchestratorPool orchestratorPool;
    private final EditorAgent editorAgent;
    private final DraftStorage draftStorage;
    private final MemoryPackStorage memoryPackStorage;
    private final AnalysisJobService analysisJobs;
    private final com.moxu.analysis.ContinuityService continuity;
    private final CausalAuditService causal;

    @GetMapping("/continuity/{chapter}/causal")
    public Map<String, Object> causalStatus(@PathVariable String projectId, @PathVariable String chapter) throws java.io.IOException {
        return causal.status(projectId, chapter);
    }

    @PostMapping("/continuity/{chapter}/causal/run")
    public Map<String, Object> runCausal(@PathVariable String projectId, @PathVariable String chapter,
            @Valid @RequestBody CausalAuditService.Request request) throws java.io.IOException {
        return causal.run(projectId, chapter, request);
    }

    @PostMapping("/continuity/{chapter}/causal/cancel")
    public Map<String, Object> cancelCausal(@PathVariable String projectId, @PathVariable String chapter) throws java.io.IOException {
        return causal.cancel(projectId, chapter);
    }

    @PostMapping("/continuity/{chapter}/causal/decisions")
    public Map<String, Object> decideCausal(@PathVariable String projectId, @PathVariable String chapter,
            @Valid @RequestBody CausalAuditService.Decision decision) throws java.io.IOException {
        return causal.decide(projectId, chapter, decision);
    }

    @GetMapping("/continuity/{chapter}/causal/graph")
    public Map<String, Object> causalGraph(@PathVariable String projectId, @PathVariable String chapter) throws java.io.IOException {
        return causal.graph(projectId, chapter);
    }

    @GetMapping("/continuity/{chapter}")
    public Map<String, Object> continuityStatus(@PathVariable String projectId, @PathVariable String chapter) throws java.io.IOException {
        return continuity.inspect(projectId, chapter);
    }

    @PostMapping("/continuity/{chapter}/sync")
    public Map<String, Object> syncContinuity(@PathVariable String projectId, @PathVariable String chapter,
            @RequestBody com.moxu.analysis.ContinuityService.Request request) throws java.io.IOException {
        return continuity.sync(projectId, chapter, request);
    }

    @PostMapping("/continuity/{chapter}/acknowledge")
    public Map<String, Object> acknowledgeContinuity(@PathVariable String projectId, @PathVariable String chapter,
            @RequestBody com.moxu.analysis.ContinuityService.Request request) throws java.io.IOException {
        return continuity.acknowledge(projectId, chapter, request.revision());
    }

    @PostMapping("/continuity/{chapter}/clean-removed")
    public Map<String, Object> cleanRemovedContinuity(@PathVariable String projectId, @PathVariable String chapter,
            @RequestBody com.moxu.analysis.ContinuityService.Request request) throws java.io.IOException {
        return continuity.cleanRemoved(projectId, chapter, request.revision());
    }
    
    /**
     * 开始写作会话
     * POST /projects/{projectId}/session/start
     */
    @PostMapping("/start")
    public Map<String, Object> startSession(@PathVariable String projectId,
                                             @RequestBody StartSessionRequest request) {
        log.info("Starting session for project: {}, chapter: {}", projectId, request.getChapter());
        continuity.requireReady(projectId, request.getChapter());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());
        
        return orchestrator.startSession(
                projectId,
                request.getChapter(),
                request.getChapterTitle(),
                request.getChapterGoal(),
                request.getTargetWordCount() != null ? request.getTargetWordCount() : 3000,
                request.getCharacterNames()
        );
    }
    
    /**
     * 获取会话状态
     * GET /projects/{projectId}/session/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(@PathVariable String projectId) {
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        Map<String, Object> status = orchestrator.getStatus();
        
        String statusProjectId = (String) status.get("project_id");
        if (statusProjectId == null || statusProjectId.isEmpty() || !statusProjectId.equals(projectId)) {
            return Map.of("status", "idle", "message", "No active session for this project");
        }
        
        return status;
    }
    
    /**
     * 提交用户反馈
     * POST /projects/{projectId}/session/feedback
     */
    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@PathVariable String projectId,
                                               @RequestBody FeedbackRequest request) {
        log.info("Processing feedback for project: {}, chapter: {}, action: {}",
                projectId, request.getChapter(), request.getAction());
        if ("revise".equals(request.getAction())) continuity.requireReady(projectId, request.getChapter());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        
        return orchestrator.processFeedback(
                projectId,
                request.getChapter(),
                request.getFeedback(),
                request.getAction(),
                request.getRejectedEntities(),
                request.getContinuityRevision()
        );
    }
    
    @GetMapping("/archive/{chapter}")
    public Map<String, Object> archiveStatus(@PathVariable String projectId, @PathVariable String chapter) throws java.io.IOException {
        Map<String, Object> status = new LinkedHashMap<>(draftStorage.getArchiveStatus(projectId, chapter));
        if ("running".equals(status.get("status")) && !orchestratorPool.getOrchestrator(projectId).isArchiving(chapter)) {
            status.put("status", "partial");
            status.put("message", "上一次归档已中断，重试会保留已完成步骤");
        }
        return status;
    }

    @PostMapping("/archive/{chapter}/retry")
    public Map<String, Object> retryArchive(@PathVariable String projectId, @PathVariable String chapter) {
        return orchestratorPool.getOrchestrator(projectId).retryArchive(projectId, chapter);
    }

    /**
     * 编辑建议（差异修订）
     * POST /projects/{projectId}/session/edit-suggest
     */
    @PostMapping("/edit-suggest")
    public Map<String, Object> suggestEdit(@PathVariable String projectId,
                                            @RequestBody EditSuggestRequest request) {
        log.info("Edit suggest for project: {}, chapter: {}", projectId, request.getChapter());

        // 获取原始内容：优先用请求体里的 content，否则从存储读
        String originalContent = request.getContent();
        if (originalContent == null || originalContent.isBlank()) {
            originalContent = draftStorage.getFinalDraft(projectId, request.getChapter())
                    .orElse("");
        }
        if (originalContent.isBlank()) {
            return Map.of("success", false, "error", "No draft content found for chapter: " + request.getChapter());
        }

        String instruction = request.getInstruction();
        if (instruction == null || instruction.isBlank()) {
            return Map.of("success", false, "error", "instruction is required");
        }

        try {
            String revised;
            Map<String, Object> memoryPack = null;
            if (request.getChapter() != null && !request.getChapter().isBlank()) {
                String mode = request.getContextMode() != null ? request.getContextMode().trim().toLowerCase() : "quick";
                boolean forceRefresh = "full".equals(mode);
                Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
                memoryPack = forceRefresh
                        ? orchestrator.ensureMemoryPack(projectId, request.getChapter(), true, "editor",
                        instruction, originalContent)
                        : memoryPackStorage.readPack(projectId, request.getChapter());
            }
            if (request.getSelectionStart() != null && request.getSelectionEnd() != null) {
                // 精确偏移选区编辑
                revised = editorAgent.suggestRevisionSelectionRange(
                        projectId,
                        originalContent,
                        request.getSelectionStart(),
                        request.getSelectionEnd(),
                        request.getSelectionText(),
                        instruction,
                        request.getRejectedEntities(),
                        memoryPack);
            } else if (request.getSelectionText() != null && !request.getSelectionText().isBlank()) {
                // 子串匹配选区编辑
                int occurrence = request.getSelectionOccurrence() != null ? request.getSelectionOccurrence() : 1;
                revised = editorAgent.suggestRevisionSelection(
                        projectId,
                        originalContent,
                        request.getSelectionText(),
                        occurrence,
                        instruction,
                        request.getRejectedEntities(),
                        memoryPack);
            } else {
                // 全文修订
                revised = editorAgent.suggestRevision(
                        projectId, originalContent, instruction, request.getRejectedEntities(), memoryPack);
            }

            // 检测是否产生了实际修改（对齐 Python normalize_for_compare：统一换行 + 去尾部空白）
            String originalNorm = normalizeForCompare(originalContent);
            String revisedNorm = normalizeForCompare(revised);
            if (revisedNorm.equals(originalNorm)) {
                return Map.of("success", false,
                        "error", "未能生成可应用的差异修改：请在指令中复制粘贴要修改的原句/段落，或使用「选区编辑」进行精确定位。");
            }

            return Map.of(
                    "success", true,
                    "revised_content", revised,
                    "word_count", revised.length(),
                    "chapter", request.getChapter() != null ? request.getChapter() : "",
                    "diff_preview", buildDiffPreview(originalContent, revised),
                    "change_stats", buildChangeStats(originalContent, revised)
            );
        } catch (Exception e) {
            log.error("编辑建议失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \t]+$", "")
                .replaceAll("(?m)[ \t]+$", "")
                .stripTrailing();
    }
    
    /**
     * 回答预写问题 / 用户确认场景简报后触发写作
     * POST /projects/{projectId}/session/answer-questions
     */
    @PostMapping("/answer-questions")
    public Map<String, Object> answerQuestions(@PathVariable String projectId,
                                                @RequestBody AnswerQuestionsRequest request) {
        log.info("Answer questions / proceed to write for project: {}, chapter: {}", projectId, request.getChapter());
        continuity.requireReady(projectId, request.getChapter());

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());

        // 将 answers 转为 Map 列表
        List<Map<String, String>> answers = new java.util.ArrayList<>();
        if (request.getAnswers() != null) {
            for (AnswerQuestionsRequest.QuestionAnswer qa : request.getAnswers()) {
                Map<String, String> m = new java.util.HashMap<>();
                if (qa.getType() != null) m.put("type", qa.getType());
                if (qa.getQuestion() != null) m.put("question", qa.getQuestion());
                if (qa.getKey() != null) m.put("key", qa.getKey());
                if (qa.getAnswer() != null) m.put("answer", qa.getAnswer());
                answers.add(m);
            }
        }

        return orchestrator.proceedToWrite(
                projectId,
                request.getChapter(),
                request.getChapterGoal(),
                request.getTargetWordCount() != null ? request.getTargetWordCount() : 3000,
                request.getCharacterNames(),
                answers
        );
    }
    
    /**
     * 取消会话
     * POST /projects/{projectId}/session/cancel
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancelSession(@PathVariable String projectId) {
        log.info("Cancelling session for project: {}", projectId);
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.cancelSession(projectId);
    }
    
    /**
     * 分析章节
     * POST /projects/{projectId}/session/analyze
     */
    @PostMapping("/analyze")
    public Map<String, Object> analyzeChapter(@PathVariable String projectId,
                                               @RequestBody AnalyzeRequest request) {
        log.info("Analyzing chapter for project: {}, chapter: {}", projectId, request.getChapter());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());
        return analysisJobs.analyzeSingle(
                projectId,
                request.getChapter(),
                request.getContent(),
                request.getChapterTitle()
        );
    }
    
    /**
     * 保存分析结果
     * POST /projects/{projectId}/session/save-analysis
     */
    @PostMapping("/save-analysis")
    public Map<String, Object> saveAnalysis(@PathVariable String projectId,
                                             @RequestBody SaveAnalysisRequest request) throws java.io.IOException {
        log.info("Saving analysis for project: {}", projectId);

        String chapter = request.getChapter();
        Map<String, Object> analysis = request.getAnalysis();
        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        Map<String, Object> saved = analysisJobs.saveReviewed(projectId,
                List.of(Map.of("chapter", chapter, "analysis", analysis)), overwrite, false);
        if (saved.get("results") instanceof List<?> results && !results.isEmpty()
                && results.getFirst() instanceof Map<?, ?> result) {
            Map<String, Object> response = new LinkedHashMap<>();
            result.forEach((key, value) -> response.put(key.toString(), value));
            if (Boolean.FALSE.equals(saved.get("success"))) {
                response.put("success", false);
                response.put("error", saved.getOrDefault("error", "Analysis saving is incomplete"));
            }
            return response;
        }
        return saved;
    }
    
    /**
     * 批量分析章节
     * POST /projects/{projectId}/session/analyze-batch
     */
    @PostMapping("/analyze-batch")
    public Map<String, Object> analyzeBatch(@PathVariable String projectId,
                                             @Valid @RequestBody AnalysisJobRequest request) throws java.io.IOException {
        log.info("Batch analyzing chapters for project: {}", projectId);

        List<String> chapters = request.getChapters();
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            orchestrator.setLanguage(request.getLanguage());
        }
        return analysisJobs.run(projectId, request, false);
    }
    
    /**
     * 同步分析（分析并覆盖保存）
     * POST /projects/{projectId}/session/analyze-sync
     */
    @PostMapping("/analyze-sync")
    public Map<String, Object> analyzeSync(@PathVariable String projectId,
                                            @Valid @RequestBody AnalysisJobRequest request) throws java.io.IOException {
        log.info("Sync analyzing chapters for project: {}", projectId);

        List<String> chapters = request.getChapters();
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            orchestrator.setLanguage(request.getLanguage());
        }
        return analysisJobs.run(projectId, request, true);
    }

    @GetMapping("/analysis-jobs/{jobId}")
    public Map<String, Object> analysisJob(@PathVariable String projectId, @PathVariable String jobId) throws java.io.IOException {
        return analysisJobs.status(projectId, jobId);
    }
    @PostMapping("/analysis-jobs/{jobId}/cancel")
    public Map<String, Object> cancelAnalysisJob(@PathVariable String projectId, @PathVariable String jobId) throws java.io.IOException {
        return analysisJobs.cancel(projectId, jobId);
    }
    @GetMapping("/analysis-book-summary")
    public Map<String, Object> bookSummary(@PathVariable String projectId) throws java.io.IOException {
        return analysisJobs.bookSummary(projectId);
    }
    
    /**
     * 批量保存分析结果
     * POST /projects/{projectId}/session/save-analysis-batch
     */
    @PostMapping("/save-analysis-batch")
    public Map<String, Object> saveAnalysisBatch(@PathVariable String projectId,
                                                  @RequestBody SaveAnalysisBatchRequest request) throws java.io.IOException {
        log.info("Batch saving analysis for project: {}", projectId);

        List<Map<String, Object>> items = new ArrayList<>();
        if (request.getItems() != null) {
            for (AnalysisBatchItemRequest item : request.getItems()) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> mapped = new LinkedHashMap<>();
                mapped.put("chapter", item.getChapter());
                mapped.put("analysis", item.getAnalysis());
                items.add(mapped);
            }
        }
        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return analysisJobs.saveReviewed(projectId, items, overwrite);
    }
    
    /**
     * 流式生成草稿（SSE）
     * GET /projects/{projectId}/session/stream-draft
     */
    @GetMapping(value = "/stream-draft", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamDraft(@PathVariable String projectId,
                                     @RequestParam String chapter) {
        log.info("Stream draft for project: {}, chapter: {}", projectId, chapter);
        continuity.requireReady(projectId, chapter);

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);

        // 从存储加载场景简报，并通过 Orchestrator 准备完整上下文
        return orchestrator.prepareAndStreamDraft(projectId, chapter)
                .map(chunk -> "data: " + chunk.replace("\n", "\\n") + "\n\n");
    }

    private Map<String, Object> buildChangeStats(String original, String revised) {
        String originalNorm = original == null ? "" : original.replace("\r\n", "\n").replace('\r', '\n');
        String revisedNorm = revised == null ? "" : revised.replace("\r\n", "\n").replace('\r', '\n');
        String[] originalLines = originalNorm.split("\n", -1);
        String[] revisedLines = revisedNorm.split("\n", -1);

        int changed = 0;
        int max = Math.max(originalLines.length, revisedLines.length);
        for (int i = 0; i < max; i++) {
            String oldLine = i < originalLines.length ? originalLines[i] : "";
            String newLine = i < revisedLines.length ? revisedLines[i] : "";
            if (!oldLine.equals(newLine)) {
                changed++;
            }
        }

        return Map.of(
                "original_lines", originalLines.length,
                "revised_lines", revisedLines.length,
                "changed_lines", changed
        );
    }

    private List<Map<String, Object>> buildDiffPreview(String original, String revised) {
        String originalNorm = original == null ? "" : original.replace("\r\n", "\n").replace('\r', '\n');
        String revisedNorm = revised == null ? "" : revised.replace("\r\n", "\n").replace('\r', '\n');
        String[] originalLines = originalNorm.split("\n", -1);
        String[] revisedLines = revisedNorm.split("\n", -1);

        List<Map<String, Object>> preview = new ArrayList<>();
        int max = Math.max(originalLines.length, revisedLines.length);
        for (int i = 0; i < max; i++) {
            String oldLine = i < originalLines.length ? originalLines[i] : null;
            String newLine = i < revisedLines.length ? revisedLines[i] : null;
            if (Objects.equals(oldLine, newLine)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", i + 1);
            item.put("before", oldLine == null ? "" : oldLine);
            item.put("after", newLine == null ? "" : newLine);
            String type = oldLine == null ? "insert" : (newLine == null ? "delete" : "replace");
            item.put("type", type);
            preview.add(item);
            if (preview.size() >= 12) {
                break;
            }
        }
        return preview;
    }
}
