package com.moxu.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.agent.EditorAgent;
import com.moxu.controller.BindingsController;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.DeAiRewriteConfig;
import com.moxu.model.entity.Draft;
import com.moxu.model.entity.SceneBrief;
import com.moxu.orchestrator.OrchestratorPool;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.DeAiRewriteConfigStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import com.moxu.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 草稿管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/drafts")
@RequiredArgsConstructor
public class DraftController {

    private static final String CHAPTER_ORDINAL_TOKEN = "[0-9零〇一二两三四五六七八九十百千万ivxIVX]+";
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("^V(\\d+)C(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_JSON_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_TITLE_LABEL_PATTERN = Pattern.compile("^(?i:title|标题|章节标题|最终标题|建议标题)\\s*[:：-]\\s*");
    private static final Pattern LEADING_EN_CHAPTER_PATTERN = Pattern.compile("^(?i:chapter\\s*[0-9ivx]+\\s*[:：\\-—]?\\s*)");
    private static final Pattern LEADING_CN_CHAPTER_PATTERN = Pattern.compile(
            "^(?:第\\s*" + CHAPTER_ORDINAL_TOKEN + "\\s*[章节回节幕集]\\s*[:：\\-—]?\\s*)");
    private static final Pattern RESIDUAL_CHAPTER_HEADING_PATTERN = Pattern.compile(
            "^(?:(?i:chapter\\s*[0-9ivx]+)|第\\s*" + CHAPTER_ORDINAL_TOKEN + "\\s*[章节回节幕集])(?:\\s*[:：、\\-—]\\s*|\\s+).+");
    private static final Pattern BARE_CHAPTER_HEADING_PATTERN = Pattern.compile(
            "^(?:(?i:chapter\\s*[0-9ivx]+)|第\\s*" + CHAPTER_ORDINAL_TOKEN + "\\s*[章节回节幕集])$");
    private static final int TITLE_EXCERPT_MAX = 2600;
    private static final int TITLE_BATCH_SNIPPET_MAX = 900;
    private static final int TITLE_BATCH_MIN_SIZE = 3;
    private static final int TITLE_BATCH_MAX_SIZE = 8;
    
    private final DraftStorage draftStorage;
    private final EditorAgent editorAgent;
    private final CanonStorage canonStorage;
    private final OrchestratorPool orchestratorPool;
    private final BindingsController bindingsController;
    private final MemoryPackStorage memoryPackStorage;
    private final DeAiRewriteConfigStorage deAiRewriteConfigStorage;
    private final ProjectStorage projectStorage;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @GetMapping("")
    public List<String> listChapters(@PathVariable String projectId) {
        return draftStorage.listChapters(projectId);
    }
    
    @GetMapping("/{chapter}")
    public Draft getLatestDraft(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getLatestDraft(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }
    
    @GetMapping("/{chapter}/final")
    public Map<String, Object> getFinalDraft(@PathVariable String projectId, @PathVariable String chapter) {
        String content = draftStorage.getFinalDraft(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Final draft not found"));
        return Map.of(
                "content", content,
                "word_count", content.length()
        );
    }
    
    @PutMapping("/{chapter}")
    public Draft saveDraft(@PathVariable String projectId, @PathVariable String chapter,
                           @RequestBody DraftSaveRequest request) {
        try {
            return draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content(),
                    request.wordCount(),
                    request.pendingConfirmations(),
                    true
            );
        } catch (IOException e) {
            log.error("保存草稿失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @DeleteMapping("/{chapter}")
    public Map<String, Object> deleteChapter(@PathVariable String projectId, @PathVariable String chapter) {
        try {
            boolean success = draftStorage.deleteChapter(projectId, chapter);
            if (!success) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found");
            }

            Map<String, Object> cascade = new HashMap<>();
            try {
                cascade.put("facts_deleted", canonStorage.deleteFactsByChapter(projectId, chapter));
            } catch (Exception e) {
                log.warn("级联删除 facts 失败: {}", e.getMessage());
            }
            try {
                cascade.put("bindings_deleted", bindingsController.deleteBindingsForChapter(projectId, chapter));
            } catch (Exception e) {
                log.warn("级联删除 bindings 失败: {}", e.getMessage());
            }
            try {
                cascade.put("memory_pack_deleted", memoryPackStorage.deletePack(projectId, chapter));
            } catch (Exception e) {
                log.warn("级联删除 memory pack 失败: {}", e.getMessage());
            }

            return Map.of("success", true, "cascade", cascade);
        } catch (IOException e) {
            log.error("删除章节失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 从某章节开始重制生成产物，但保留章节壳与大纲/卡片等长期资产。
     * 适用于“从这一章开始重新写”。
     */
    @PostMapping("/reset-from/{chapter}")
    public Map<String, Object> resetFromChapter(@PathVariable String projectId,
                                                @PathVariable String chapter) {
        String startChapter = normalizeChapterId(chapter);
        List<String> affectedChapters = collectAffectedChapters(projectId, startChapter);
        if (affectedChapters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No generated chapters found from " + startChapter);
        }

        int draftShellsReset = 0;
        int summariesCleared = 0;
        int summariesDeleted = 0;
        int factsDeleted = 0;
        int timelineDeleted = 0;
        int statesDeleted = 0;
        int bindingsDeleted = 0;
        int memoryPacksDeleted = 0;
        int volumeSummariesDeleted = 0;

        Set<String> affectedVolumes = new LinkedHashSet<>();

        for (String targetChapter : affectedChapters) {
            affectedVolumes.add(extractVolumeId(targetChapter));

            try {
                draftStorage.resetChapterDraftKeepingShell(projectId, targetChapter);
                draftShellsReset++;
            } catch (IOException e) {
                log.warn("重制章节草稿目录失败: {}/{}", projectId, targetChapter, e);
            }

            ChapterSummary existingSummary = draftStorage.getChapterSummary(projectId, targetChapter).orElse(null);
            if (existingSummary != null) {
                try {
                    ChapterSummary cleared = ChapterSummary.builder()
                            .chapter(targetChapter)
                            .volumeId(firstNonBlank(existingSummary.getVolumeId(), extractVolumeId(targetChapter)))
                            .orderIndex(existingSummary.getOrderIndex())
                            .title(firstNonBlank(existingSummary.getTitle(), ""))
                            .wordCount(0)
                            .briefSummary("")
                            .keyEvents(new ArrayList<>())
                            .newFacts(new ArrayList<>())
                            .characterStateChanges(new ArrayList<>())
                            .openLoops(new ArrayList<>())
                            .build();
                    draftStorage.saveChapterSummary(projectId, cleared);
                    summariesCleared++;
                } catch (IOException e) {
                    log.warn("重置章节摘要失败: {}/{}", projectId, targetChapter, e);
                }
            } else if (draftStorage.deleteChapterSummary(projectId, targetChapter)) {
                summariesDeleted++;
            }

            try {
                factsDeleted += canonStorage.deleteFactsByChapter(projectId, targetChapter);
            } catch (Exception e) {
                log.warn("重制时删除 facts 失败: {}/{}", projectId, targetChapter, e);
            }

            try {
                timelineDeleted += canonStorage.deleteTimelineEventsByChapter(projectId, targetChapter);
            } catch (Exception e) {
                log.warn("重制时删除 timeline 失败: {}/{}", projectId, targetChapter, e);
            }

            try {
                statesDeleted += canonStorage.deleteCharacterStatesByChapter(projectId, targetChapter);
            } catch (Exception e) {
                log.warn("重制时删除 character states 失败: {}/{}", projectId, targetChapter, e);
            }

            try {
                if (bindingsController.deleteBindingsForChapter(projectId, targetChapter)) {
                    bindingsDeleted++;
                }
            } catch (Exception e) {
                log.warn("重制时删除 bindings 失败: {}/{}", projectId, targetChapter, e);
            }

            try {
                if (memoryPackStorage.deletePack(projectId, targetChapter)) {
                    memoryPacksDeleted++;
                }
            } catch (Exception e) {
                log.warn("重制时删除 memory pack 失败: {}/{}", projectId, targetChapter, e);
            }
        }

        for (String volumeId : affectedVolumes) {
            try {
                if (draftStorage.deleteVolumeSummary(projectId, volumeId)) {
                    volumeSummariesDeleted++;
                }
            } catch (Exception e) {
                log.warn("重制时删除分卷摘要失败: {}/{}", projectId, volumeId, e);
            }
        }

        Map<String, Object> textChunkMeta = draftStorage.rebuildTextChunkIndex(projectId);

        return Map.of(
                "success", true,
                "start_chapter", startChapter,
                "affected_chapters", affectedChapters,
                "affected_volumes", affectedVolumes,
                "stats", Map.of(
                        "draft_shells_reset", draftShellsReset,
                        "summaries_cleared", summariesCleared,
                        "summaries_deleted", summariesDeleted,
                        "facts_deleted", factsDeleted,
                        "timeline_deleted", timelineDeleted,
                        "character_states_deleted", statesDeleted,
                        "bindings_deleted", bindingsDeleted,
                        "memory_packs_deleted", memoryPacksDeleted,
                        "volume_summaries_deleted", volumeSummariesDeleted,
                        "text_chunk_index_rebuilt", true
                ),
                "text_chunk_meta", textChunkMeta
        );
    }
    
    // ========== 场景简报 ==========
    
    @GetMapping("/{chapter}/scene-brief")
    public SceneBrief getSceneBrief(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getSceneBrief(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scene brief not found"));
    }
    
    @PutMapping("/{chapter}/scene-brief")
    public Map<String, Object> saveSceneBrief(@PathVariable String projectId, @PathVariable String chapter,
                                               @RequestBody SceneBrief brief) {
        try {
            brief.setChapter(chapter);
            draftStorage.saveSceneBrief(projectId, chapter, brief);
            return Map.of("success", true, "message", "Scene brief saved");
        } catch (IOException e) {
            log.error("保存场景简报失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 章节摘要 ==========
    
    @GetMapping("/summaries")
    public List<ChapterSummary> listChapterSummaries(@PathVariable String projectId,
                                                      @RequestParam(name = "volume_id", required = false) String volumeId,
                                                      @RequestParam(name = "volumeId", required = false) String volumeIdAlias) {
        String effectiveVolumeId = volumeId != null && !volumeId.isBlank() ? volumeId : volumeIdAlias;
        return draftStorage.listChapterSummaries(projectId, effectiveVolumeId);
    }
    
    @GetMapping("/{chapter}/summary")
    public ChapterSummary getChapterSummary(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getChapterSummary(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter summary not found"));
    }
    
    @PutMapping("/{chapter}/summary")
    public Map<String, Object> saveChapterSummary(@PathVariable String projectId, @PathVariable String chapter,
                                                   @RequestBody ChapterSummary summary) {
        try {
            summary.setChapter(chapter);
            draftStorage.saveChapterSummary(projectId, summary);
            return Map.of("success", true, "message", "Chapter summary saved");
        } catch (IOException e) {
            log.error("保存章节摘要失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 草稿版本 ==========
    
    @GetMapping("/{chapter}/versions")
    public List<String> listDraftVersions(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.listDraftVersions(projectId, chapter);
    }

    // ========== 内容更新 ==========
    
    @PutMapping("/{chapter}/content")
    public Map<String, Object> updateDraftContent(@PathVariable String projectId, @PathVariable String chapter,
                                                   @RequestBody UpdateContentRequest request) {
        try {
            Draft draft = draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content,
                    request.content.length(),
                    null,
                    true
            );
            
            String canonical = draft.getChapter() != null ? draft.getChapter() : chapter;
            
            if (request.title != null) {
                ChapterSummary summary = draftStorage.getChapterSummary(projectId, canonical).orElse(null);
                if (summary != null) {
                    summary.setTitle(request.title);
                    summary.setWordCount(request.content.length());
                } else {
                    summary = ChapterSummary.builder()
                            .chapter(canonical)
                            .title(request.title)
                            .wordCount(request.content.length())
                            .build();
                }
                draftStorage.saveChapterSummary(projectId, summary);
            }
            try {
                bindingsController.markBindingsStale(projectId, List.of(canonical), "draft_saved", true);
            } catch (Exception e) {
                log.warn("保存后标记 bindings stale 失败: {}", e.getMessage());
            }
            // Empty chapters have nothing to review. Writer context is prepared when generation starts.
            if (!request.content.isBlank()) {
                orchestratorPool.getOrchestrator(projectId).refreshDraftArtifacts(projectId, canonical);
            }
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("version", "current");
            response.put("message", "Content saved");
            response.put("chapter", canonical);
            response.put("title", request.title);
            return response;
        } catch (IOException e) {
            log.error("更新草稿内容失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @PutMapping("/{chapter}/autosave")
    public Map<String, Object> autosaveDraftContent(@PathVariable String projectId, @PathVariable String chapter,
                                                     @RequestBody UpdateContentRequest request) {
        try {
            Draft draft = draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content,
                    request.content.length(),
                    null,
                    false  // 不创建备份
            );
            
            String canonical = draft.getChapter() != null ? draft.getChapter() : chapter;
            
            if (request.title != null) {
                ChapterSummary summary = draftStorage.getChapterSummary(projectId, canonical).orElse(null);
                if (summary != null) {
                    summary.setTitle(request.title);
                    summary.setWordCount(request.content.length());
                } else {
                    summary = ChapterSummary.builder()
                            .chapter(canonical)
                            .title(request.title)
                            .wordCount(request.content.length())
                            .build();
                }
                draftStorage.saveChapterSummary(projectId, summary);
            }
            try {
                bindingsController.markBindingsStale(projectId, List.of(canonical), "draft_autosaved", true);
            } catch (Exception e) {
                log.warn("自动保存后标记 bindings stale 失败: {}", e.getMessage());
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("version", "current");
            response.put("message", "Content autosaved");
            response.put("chapter", canonical);
            response.put("title", request.title);
            return response;
        } catch (IOException e) {
            log.error("自动保存草稿失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{chapter}/generate-title")
    public Map<String, Object> generateChapterTitle(@PathVariable String projectId,
                                                    @PathVariable String chapter,
                                                    @RequestBody(required = false) GenerateTitleRequest request) {
        String normalizedChapter = normalizeChapterId(chapter);
        String projectDisplayName = resolveProjectDisplayName(projectId);
        String providedContent = request != null ? request.content : null;
        String content = providedContent != null && !providedContent.isBlank()
                ? providedContent
                : resolveChapterContent(projectId, normalizedChapter);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前章节暂无可用正文");
        }

        ChapterSummary summary = draftStorage.getChapterSummary(projectId, normalizedChapter).orElse(null);
        String currentTitle = summary != null ? firstNonBlank(summary.getTitle(), "") : "";
        String generatedTitle = "";
        try {
            generatedTitle = generateTitleFromContent(projectId, normalizedChapter, currentTitle, content, request != null ? request.language : null);
        } catch (Exception e) {
            log.warn("根据内容生成章节标题失败，改用兜底/保留逻辑: projectId={}, chapter={}, message={}",
                    projectId, normalizedChapter, e.getMessage());
        }

        String finalTitle = normalizeAcceptedTitle(generatedTitle, projectDisplayName, normalizedChapter, currentTitle);
        boolean fallbackUsed = false;
        if (finalTitle.isBlank()) {
            finalTitle = fallbackOrKeepCurrentTitle(projectDisplayName, normalizedChapter, currentTitle);
            fallbackUsed = !finalTitle.equals(String.valueOf(currentTitle).trim());
        }

        ChapterSummary toSave = summary != null ? summary : ChapterSummary.builder()
                .chapter(normalizedChapter)
                .volumeId(extractVolumeId(normalizedChapter))
                .build();
        toSave.setChapter(normalizedChapter);
        if (toSave.getVolumeId() == null || toSave.getVolumeId().isBlank()) {
            toSave.setVolumeId(extractVolumeId(normalizedChapter));
        }
        toSave.setTitle(finalTitle);
        toSave.setWordCount(content.length());

        try {
            draftStorage.saveChapterSummary(projectId, toSave);
        } catch (IOException e) {
            log.error("保存生成后的章节标题失败: projectId={}, chapter={}", projectId, normalizedChapter, e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        return Map.of(
                "success", true,
                "chapter", normalizedChapter,
                "title", finalTitle,
                "changed", !finalTitle.equals(String.valueOf(currentTitle).trim()),
                "fallback_used", fallbackUsed
        );
    }

    @PostMapping("/generate-titles/batch")
    public Map<String, Object> generateChapterTitlesBatch(@PathVariable String projectId,
                                                          @RequestBody GenerateTitlesBatchRequest request) {
        String normalizedVolumeId = normalizeVolumeId(request != null ? request.volumeId : null);
        List<String> chapters = resolveBatchTitleChapters(projectId, normalizedVolumeId, request != null ? request.chapters : null);
        if (chapters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前分卷下没有可生成标题的章节");
        }

        String projectDisplayName = resolveProjectDisplayName(projectId);
        List<ChapterTitleMaterial> materials = new ArrayList<>();
        for (String chapterId : chapters) {
            String content = resolveChapterContent(projectId, chapterId);
            ChapterSummary summary = draftStorage.getChapterSummary(projectId, chapterId).orElse(null);
            String currentTitle = summary != null ? firstNonBlank(summary.getTitle(), "") : "";
            materials.add(new ChapterTitleMaterial(chapterId, content, currentTitle, summary));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        int batchSize = computeBatchTitleSize(materials.size());
        for (int start = 0; start < materials.size(); start += batchSize) {
            int end = Math.min(materials.size(), start + batchSize);
            List<ChapterTitleMaterial> group = materials.subList(start, end);
            Map<String, String> generatedMap = Map.of();
            try {
                generatedMap = generateTitlesForBatch(projectId, projectDisplayName, group, request != null ? request.language : null);
            } catch (Exception e) {
                log.warn("批量生成章节标题批次失败: projectId={}, volume={}, range={}..{}, message={}",
                        projectId, normalizedVolumeId, start, end - 1, e.getMessage());
            }

            for (ChapterTitleMaterial material : group) {
                String chapterId = material.chapterId();
                String content = material.content();
                String currentTitle = material.currentTitle();
                if (content == null || content.isBlank()) {
                    String fallbackTitle = fallbackOrKeepCurrentTitle(projectDisplayName, chapterId, currentTitle);
                    boolean changed = !fallbackTitle.equals(String.valueOf(currentTitle).trim());
                    if (changed) {
                        try {
                            persistGeneratedTitle(projectId, chapterId, fallbackTitle, content, material.summary());
                            updated++;
                        } catch (IOException e) {
                            log.warn("保存兜底章节标题失败: projectId={}, chapter={}, message={}",
                                    projectId, chapterId, e.getMessage());
                            fallbackTitle = String.valueOf(currentTitle).trim();
                            changed = false;
                            skipped++;
                        }
                    } else {
                        skipped++;
                    }
                    items.add(Map.of(
                            "chapter", chapterId,
                            "success", true,
                            "title", fallbackTitle,
                            "changed", changed,
                            "fallback_used", true,
                            "reason", "no_content"
                    ));
                    continue;
                }

                String candidateTitle = normalizeAcceptedTitle(
                        generatedMap.get(chapterId),
                        projectDisplayName,
                        chapterId,
                        currentTitle
                );
                boolean fallbackUsed = false;
                String finalTitle = candidateTitle;
                if (finalTitle.isBlank()) {
                    finalTitle = fallbackOrKeepCurrentTitle(projectDisplayName, chapterId, currentTitle);
                    fallbackUsed = !finalTitle.equals(String.valueOf(currentTitle).trim());
                }

                boolean changed = !finalTitle.equals(String.valueOf(currentTitle).trim());
                if (changed) {
                    try {
                        persistGeneratedTitle(projectId, chapterId, finalTitle, content, material.summary());
                        updated++;
                    } catch (IOException e) {
                        log.warn("保存批量生成章节标题失败: projectId={}, chapter={}, message={}",
                                projectId, chapterId, e.getMessage());
                        finalTitle = String.valueOf(currentTitle).trim();
                        skipped++;
                        changed = false;
                    }
                } else {
                    skipped++;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chapter", chapterId);
                item.put("success", true);
                item.put("title", finalTitle);
                item.put("changed", changed);
                item.put("fallback_used", fallbackUsed);
                if (fallbackUsed) {
                    item.put("reason", "fallback");
                }
                items.add(item);
            }
        }

        return Map.of(
                "success", true,
                "volume_id", normalizedVolumeId,
                "total", chapters.size(),
                "updated", updated,
                "skipped", skipped,
                "items", items
        );
    }
    
    // ========== 重写和重排 ==========

    @GetMapping("/rewrite-config")
    public DeAiRewriteConfig getRewriteConfig(@PathVariable String projectId) {
        return deAiRewriteConfigStorage.getConfig(projectId);
    }

    @PutMapping("/rewrite-config")
    public Map<String, Object> saveRewriteConfig(@PathVariable String projectId,
                                                 @RequestBody DeAiRewriteConfig config) {
        try {
            DeAiRewriteConfig saved = deAiRewriteConfigStorage.saveConfig(projectId, config);
            return Map.of("success", true, "config", saved);
        } catch (IOException e) {
            log.error("保存去 AI 味配置失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @PostMapping("/rewrite")
    public Map<String, Object> rewriteText(@PathVariable String projectId,
                                            @RequestBody RewriteTextRequest request) {
        if (request == null || request.text == null || request.text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text cannot be empty");
        }
        try {
            var outcome = editorAgent.rewriteTextDetailed(projectId, request.text);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("rewritten", outcome.rewritten());
            response.put("engine", outcome.engine());
            response.put("changed", outcome.changed());
            response.put("debug", outcome.debug());
            return response;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("润色服务失败: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "润色服务暂时不可用，原文未修改");
        }
    }
    
    @PostMapping("/reorder")
    public Map<String, Object> reorderChapters(@PathVariable String projectId,
                                                @RequestBody ReorderChaptersRequest request) {
        try {
            List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, request.volumeId);
            // 按 chapterOrder 重新设置 orderIndex
            for (int i = 0; i < request.chapterOrder.size(); i++) {
                String chapterId = request.chapterOrder.get(i);
                for (ChapterSummary s : summaries) {
                    if (chapterId.equals(s.getChapter())) {
                        s.setOrderIndex(i);
                        try {
                            draftStorage.saveChapterSummary(projectId, s);
                        } catch (IOException e) {
                            log.warn("保存章节顺序失败: {}", chapterId);
                        }
                        break;
                    }
                }
            }
            List<ChapterSummary> updated = draftStorage.listChapterSummaries(projectId, request.volumeId);
            return Map.of("success", true, "updated", updated);
        } catch (Exception e) {
            log.error("章节重排失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 指定版本 ==========
    
    @GetMapping("/{chapter}/{version}")
    public Draft getDraft(@PathVariable String projectId, @PathVariable String chapter,
                          @PathVariable String version) {
        return draftStorage.getDraft(projectId, chapter, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }

    private List<String> collectAffectedChapters(String projectId, String startChapter) {
        Set<String> chapters = new LinkedHashSet<>();
        chapters.addAll(draftStorage.listChapters(projectId));

        for (ChapterSummary summary : draftStorage.listChapterSummaries(projectId, null)) {
            String chapter = normalizeChapterId(summary.getChapter());
            if (!chapter.isBlank()) {
                chapters.add(chapter);
            }
        }

        Path indexDir = draftStorage.getProjectPath(projectId).resolve("index").resolve("chapters");
        if (Files.exists(indexDir)) {
            try (Stream<Path> paths = Files.list(indexDir)) {
                for (Path path : paths.filter(Files::isDirectory).toList()) {
                    String chapter = normalizeChapterId(path.getFileName().toString());
                    if (!chapter.isBlank()) {
                        chapters.add(chapter);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描 index/chapters 失败: {}", e.getMessage());
            }
        }

        Path memoryPackDir = draftStorage.getProjectPath(projectId).resolve("memory_packs");
        if (Files.exists(memoryPackDir)) {
            try (Stream<Path> paths = Files.list(memoryPackDir)) {
                for (Path path : paths
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".json") || name.endsWith(".yaml");
                        })
                        .toList()) {
                    String fileName = path.getFileName().toString();
                    int dotIndex = fileName.lastIndexOf('.');
                    String chapter = normalizeChapterId(dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName);
                    if (!chapter.isBlank()) {
                        chapters.add(chapter);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描 memory_packs 失败: {}", e.getMessage());
            }
        }

        canonStorage.listFacts(projectId).forEach(fact -> {
            String introducedIn = normalizeChapterId(fact.getIntroducedIn());
            if (!introducedIn.isBlank()) {
                chapters.add(introducedIn);
            }
        });

        canonStorage.listTimelineEvents(projectId).forEach(event -> {
            String source = normalizeChapterId(String.valueOf(event.getOrDefault("source", "")).trim());
            if (!source.isBlank()) {
                chapters.add(source);
            }
        });

        canonStorage.listCharacterStates(projectId).forEach(state -> {
            String lastSeen = normalizeChapterId(String.valueOf(state.getOrDefault("last_seen", "")).trim());
            if (!lastSeen.isBlank()) {
                chapters.add(lastSeen);
            }
        });

        return chapters.stream()
                .map(this::normalizeChapterId)
                .filter(ch -> !ch.isBlank())
                .filter(ch -> compareChapterIds(ch, startChapter) >= 0)
                .sorted(this::compareChapterIds)
                .toList();
    }

    private String normalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "";
        }
        String normalized = chapterId.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        return chapterId.trim();
    }

    private int compareChapterIds(String a, String b) {
        Matcher ma = CHAPTER_ID_PATTERN.matcher(normalizeChapterId(a));
        Matcher mb = CHAPTER_ID_PATTERN.matcher(normalizeChapterId(b));

        if (ma.matches() && mb.matches()) {
            int volA = Integer.parseInt(ma.group(1));
            int volB = Integer.parseInt(mb.group(1));
            if (volA != volB) {
                return Integer.compare(volA, volB);
            }

            int chA = Integer.parseInt(ma.group(2));
            int chB = Integer.parseInt(mb.group(2));
            return Integer.compare(chA, chB);
        }

        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private String extractVolumeId(String chapterId) {
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalizeChapterId(chapterId));
        if (matcher.matches()) {
            return "V" + matcher.group(1);
        }
        return "V1";
    }

    private String resolveChapterContent(String projectId, String chapter) {
        return draftStorage.getLatestDraft(projectId, chapter)
                .map(Draft::getContent)
                .filter(text -> text != null && !text.isBlank())
                .or(() -> draftStorage.getFinalDraft(projectId, chapter).filter(text -> text != null && !text.isBlank()))
                .orElse("");
    }

    private List<String> resolveBatchTitleChapters(String projectId, String volumeId, List<String> requestedChapters) {
        if (requestedChapters != null && !requestedChapters.isEmpty()) {
            return requestedChapters.stream()
                    .map(this::normalizeChapterId)
                    .filter(ch -> !ch.isBlank())
                    .distinct()
                    .sorted(this::compareChapterIds)
                    .toList();
        }
        if (volumeId == null || volumeId.isBlank()) {
            return List.of();
        }
        Set<String> chapters = new LinkedHashSet<>();
        draftStorage.listChapters(projectId).stream()
                .map(this::normalizeChapterId)
                .filter(ch -> volumeId.equalsIgnoreCase(extractVolumeId(ch)))
                .sorted(this::compareChapterIds)
                .forEach(chapters::add);
        draftStorage.listChapterSummaries(projectId, volumeId).stream()
                .map(ChapterSummary::getChapter)
                .map(this::normalizeChapterId)
                .filter(ch -> !ch.isBlank())
                .sorted(this::compareChapterIds)
                .forEach(chapters::add);
        return new ArrayList<>(chapters);
    }

    private int computeBatchTitleSize(int total) {
        if (total <= 0) {
            return TITLE_BATCH_MIN_SIZE;
        }
        int preferred = (int) Math.ceil(total / 3.0d);
        return Math.max(TITLE_BATCH_MIN_SIZE, Math.min(TITLE_BATCH_MAX_SIZE, preferred));
    }

    private Map<String, String> generateTitlesForBatch(String projectId,
                                                       String projectDisplayName,
                                                       List<ChapterTitleMaterial> group,
                                                       String language) {
        if (group == null || group.isEmpty()) {
            return Map.of();
        }
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
        StringBuilder material = new StringBuilder();
        for (ChapterTitleMaterial item : group) {
            material.append("章节ID: ").append(item.chapterId()).append('\n')
                    .append("当前标题: ").append(firstNonBlank(item.currentTitle(), "（空）")).append('\n')
                    .append("正文节选:\n")
                    .append(buildBatchTitleExcerpt(item.content()))
                    .append("\n\n");
        }
        String systemPrompt = english
                ? "You are a fiction chapter title assistant. For each chapter excerpt, generate one concise, natural title."
                : "你是小说章节命名助手，请为每个章节节选生成一个简洁、自然、适合网文的章节标题。";
        String userPrompt = english
                ? "Generate titles for the following chapters.\n"
                + "Requirements:\n"
                + "- Keep the same language as the source text\n"
                + "- Chinese titles should usually be 4-16 characters\n"
                + "- Do not output explanations or summaries\n"
                + "- Do not add numbering like Chapter 1\n"
                + "- Strictly output JSON only\n"
                + "Format: {\"items\":[{\"chapter\":\"V1C1\",\"title\":\"...\"}]}\n\n"
                + "Project: " + firstNonBlank(projectDisplayName, projectId) + "\n\n"
                + material
                : "请为以下多个章节节选分别生成章节标题。\n"
                + "要求：\n"
                + "- 保持正文语言\n"
                + "- 中文标题通常控制在 4-16 字\n"
                + "- 不要输出解释、摘要、推荐语\n"
                + "- 不要加“第x章”等编号\n"
                + "- 严格只输出 JSON\n"
                + "格式：{\"items\":[{\"chapter\":\"V1C1\",\"title\":\"标题\"}]}\n\n"
                + "项目：" + firstNonBlank(projectDisplayName, projectId) + "\n\n"
                + material;

        LlmResponse response = llmGateway.chatOnce(
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                llmGateway.getProviderForAgent("extractor"),
                0.3d,
                1200
        );
        return parseBatchTitleResponse(response != null ? response.getContent() : "");
    }

    private Map<String, String> parseBatchTitleResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (JsonNode item : items) {
                String chapter = normalizeChapterId(item.path("chapter").asText(""));
                String title = item.path("title").asText("").trim();
                if (!chapter.isBlank() && !title.isBlank()) {
                    result.put(chapter, title);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析批量章节标题响应失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildBatchTitleExcerpt(String content) {
        String normalized = String.valueOf(content == null ? "" : content)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() <= TITLE_BATCH_SNIPPET_MAX) {
            return normalized;
        }
        return normalized.substring(0, TITLE_BATCH_SNIPPET_MAX) + "...";
    }

    private void persistGeneratedTitle(String projectId,
                                       String chapterId,
                                       String title,
                                       String content,
                                       ChapterSummary existingSummary) throws IOException {
        ChapterSummary toSave = existingSummary != null ? existingSummary : ChapterSummary.builder()
                .chapter(chapterId)
                .volumeId(extractVolumeId(chapterId))
                .build();
        toSave.setChapter(chapterId);
        if (toSave.getVolumeId() == null || toSave.getVolumeId().isBlank()) {
            toSave.setVolumeId(extractVolumeId(chapterId));
        }
        toSave.setTitle(title);
        toSave.setWordCount(content != null ? content.length() : 0);
        draftStorage.saveChapterSummary(projectId, toSave);
    }

    private String generateTitleFromContent(String projectId,
                                            String chapter,
                                            String currentTitle,
                                            String content,
                                            String language) {
        String excerpt = buildTitleGenerationExcerpt(content);
        boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
        String systemPrompt = english
                ? "You are a fiction chapter title assistant. Turn chapter content into one concise, natural, publishable chapter title."
                : "你是小说章节命名助手，擅长把正文内容概括成一个简洁、自然、适合作为网文章节名的标题。";
        String userPrompt = english
                ? "Generate one better chapter title from the content below.\n"
                + "Requirements:\n"
                + "- Keep the same language as the source text\n"
                + "- Prefer 4-16 Chinese characters for Chinese text, or a short natural title for other languages\n"
                + "- Do not add \"Chapter X\" / numbering\n"
                + "- Do not add quotes or explanations\n"
                + "- Strictly output JSON only: {\"title\":\"...\"}\n\n"
                + "Project: " + projectId + "\n"
                + "Chapter: " + chapter + "\n"
                + "Current title (optional reference): " + firstNonBlank(currentTitle, "(none)") + "\n\n"
                + "Content excerpt:\n" + excerpt
                : "请基于以下章节正文，为这一章拟一个更自然、更像网文章节名的标题。\n"
                + "要求：\n"
                + "- 保持正文语言\n"
                + "- 中文标题建议 4-16 字，尽量简洁，有画面感或事件感\n"
                + "- 不要加“第x章”“Chapter x”等编号\n"
                + "- 不要加引号、书名号、解释或多个候选\n"
                + "- 严格只输出 JSON：{\"title\":\"标题\"}\n\n"
                + "项目：" + projectId + "\n"
                + "章节：" + chapter + "\n"
                + "当前标题（可参考，可忽略）：" + firstNonBlank(currentTitle, "无") + "\n\n"
                + "正文节选：\n" + excerpt;

        LlmResponse response = llmGateway.chatOnce(
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                llmGateway.getProviderForAgent("extractor"),
                0.3d,
                160
        );
        String normalized = sanitizeGeneratedTitle(response != null ? response.getContent() : "");
        if (normalized.isBlank()) {
            throw new IllegalStateException("Empty generated title");
        }
        return normalized;
    }

    private String buildTitleGenerationExcerpt(String content) {
        String normalized = String.valueOf(content == null ? "" : content)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() <= TITLE_EXCERPT_MAX) {
            return normalized;
        }
        int head = Math.min(1800, normalized.length());
        int tail = Math.min(700, Math.max(0, normalized.length() - head));
        if (tail <= 0) {
            return normalized.substring(0, TITLE_EXCERPT_MAX) + "...";
        }
        return normalized.substring(0, head) + "\n\n...\n\n" + normalized.substring(normalized.length() - tail);
    }

    private String sanitizeGeneratedTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        String extracted = extractTitleFromJson(text);
        if (!extracted.isBlank()) {
            text = extracted;
        } else {
            List<String> candidates = Arrays.stream(text.split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            String selected = "";
            for (String candidate : candidates) {
                String cleaned = cleanGeneratedTitleCandidate(candidate);
                if (cleaned.isBlank() || looksLikeReasoningLine(cleaned)) {
                    continue;
                }
                selected = cleaned;
                break;
            }
            if (selected.isBlank()) {
                selected = candidates.isEmpty() ? text : cleanGeneratedTitleCandidate(candidates.get(0));
            }
            text = selected;
        }
        text = cleanGeneratedTitleCandidate(text);
        if (text.length() > 32) {
            text = text.substring(0, 32).trim();
        }
        return text;
    }

    private String normalizeAcceptedTitle(String candidate,
                                          String projectDisplayName,
                                          String chapterId,
                                          String currentTitle) {
        String text = sanitizeGeneratedTitle(candidate);
        if (text.isBlank()) {
            return "";
        }
        if (looksLikeSyntheticFallbackTitle(text, projectDisplayName, chapterId)) {
            return "";
        }
        if (!isLikelyGoodTitle(text)) {
            return "";
        }
        if (text.equals(String.valueOf(currentTitle).trim())) {
            return text;
        }
        return text;
    }

    private String fallbackOrKeepCurrentTitle(String projectDisplayName, String chapterId, String currentTitle) {
        String current = sanitizeGeneratedTitle(currentTitle);
        if (isLikelyGoodTitle(current) && !looksLikeSyntheticFallbackTitle(current, projectDisplayName, chapterId)) {
            return current;
        }
        return buildFallbackChapterTitle(projectDisplayName, chapterId);
    }

    private boolean isLikelyGoodTitle(String title) {
        String text = sanitizeLooseTitle(title);
        if (text.isBlank()) {
            return false;
        }
        if (text.length() > 18) {
            return false;
        }
        if (text.contains("，") || text.contains(",") || text.contains("。") || text.contains(".")
                || text.contains("：") || text.contains(":") || text.contains("；") || text.contains(";")
                || text.contains("？") || text.contains("?") || text.contains("！") || text.contains("!")) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> badPhrases = List.of(
                "讲述", "讲的是", "故事开篇", "这一章", "这是一", "内容提要", "剧情", "主要讲", "场景", "片段",
                "我无法", "无法为", "标题建议", "以下标题", "summary", "this chapter", "tells the story",
                "based on", "analysis", "think>", "让我分析", "先分析"
        );
        for (String phrase : badPhrases) {
            if (normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (looksLikeResidualChapterHeading(text)) {
            return false;
        }
        long spaceCount = text.chars().filter(ch -> Character.isWhitespace(ch)).count();
        return spaceCount <= 1;
    }

    private String sanitizeLooseTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeSyntheticFallbackTitle(String title, String projectDisplayName, String chapterId) {
        String text = sanitizeLooseTitle(title);
        String fallback = buildFallbackChapterTitle(projectDisplayName, chapterId);
        return text.equalsIgnoreCase(fallback)
                || text.equalsIgnoreCase(fallback.replace("-", " - "));
    }

    private String buildFallbackChapterTitle(String projectDisplayName, String chapterId) {
        String base = firstNonBlank(projectDisplayName, "章节");
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalizeChapterId(chapterId));
        int index = 1;
        if (matcher.matches()) {
            index = Integer.parseInt(matcher.group(2));
        }
        return base + "-" + index;
    }

    private String resolveProjectDisplayName(String projectId) {
        return projectStorage.getProject(projectId)
                .map(project -> firstNonBlank(project.getName(), project.getId()))
                .filter(name -> !name.isBlank())
                .orElse(firstNonBlank(projectId, "导入书籍"));
    }

    private String extractTitleFromJson(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = TITLE_JSON_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = text.substring(start, end + 1);
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode titleNode = root.path("title");
                if (!titleNode.isMissingNode() && !titleNode.isNull()) {
                    return titleNode.asText("").trim();
                }
            } catch (Exception ignored) {
                // fallback to line-based selection
            }
        }
        return "";
    }

    private String cleanGeneratedTitleCandidate(String text) {
        String cleaned = text == null ? "" : text.trim();
        cleaned = cleaned.replaceFirst("^(?i:think>|analysis>|reasoning>|assistant:|final:)", "");
        cleaned = stripLeadingGeneratedTitleDecorators(cleaned);
        cleaned = cleaned.replaceAll("^[\\s>\"'“”‘’《》【】\\[\\](){}<>：:、，,.-]+", "");
        cleaned = cleaned.replaceAll("[\\s\"'“”‘’《》【】\\[\\](){}<>]+$", "");
        cleaned = stripLeadingGeneratedTitleDecorators(cleaned);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String stripLeadingGeneratedTitleDecorators(String text) {
        String cleaned = text == null ? "" : text.trim();
        for (int i = 0; i < 6; i++) {
            String before = cleaned;
            cleaned = LEADING_TITLE_LABEL_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = LEADING_EN_CHAPTER_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = LEADING_CN_CHAPTER_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = cleaned.trim();
            if (cleaned.equals(before.trim())) {
                break;
            }
        }
        return cleaned;
    }

    private boolean looksLikeResidualChapterHeading(String text) {
        String cleaned = sanitizeLooseTitle(text);
        if (cleaned.isBlank()) {
            return false;
        }
        return BARE_CHAPTER_HEADING_PATTERN.matcher(cleaned).matches()
                || RESIDUAL_CHAPTER_HEADING_PATTERN.matcher(cleaned).matches();
    }

    private boolean looksLikeReasoningLine(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("think>")
                || normalized.startsWith("<think")
                || normalized.startsWith("analysis>")
                || normalized.startsWith("reasoning>")
                || normalized.startsWith("让我分析")
                || normalized.startsWith("先分析")
                || normalized.startsWith("分析这段")
                || normalized.startsWith("我来分析")
                || normalized.startsWith("以下是")
                || normalized.startsWith("好的")
                || normalized.startsWith("当然")
                || normalized.startsWith("let's")
                || normalized.startsWith("i will")
                || normalized.startsWith("i'll")
                || normalized.startsWith("based on")
                || normalized.startsWith("here's")
                || normalized.endsWith("内容：")
                || normalized.endsWith("content:")
                || (normalized.length() > 16 && (normalized.contains("分析") || normalized.contains("summary")));
    }

    private String normalizeVolumeId(String volumeId) {
        if (volumeId == null || volumeId.isBlank()) {
            return "";
        }
        String normalized = volumeId.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("^V\\d+$")) {
            return normalized;
        }
        Matcher matcher = Pattern.compile("^(\\d+)$").matcher(normalized);
        if (matcher.matches()) {
            return "V" + matcher.group(1);
        }
        return normalized;
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private record ChapterTitleMaterial(String chapterId,
                                        String content,
                                        String currentTitle,
                                        ChapterSummary summary) {
    }
    
    // ========== 请求类 ==========
    
    public record DraftSaveRequest(
            String content,
            @JsonProperty("word_count") Integer wordCount,
            @JsonProperty("pending_confirmations") List<String> pendingConfirmations
    ) {}
    
    public static class UpdateContentRequest {
        public String content;
        public String title;
    }
    
    public static class RewriteTextRequest {
        public String text;
    }

    public static class GenerateTitleRequest {
        public String content;
        public String language;
    }

    public static class GenerateTitlesBatchRequest {
        @JsonProperty("volume_id")
        public String volumeId;
        public List<String> chapters;
        public String language;
    }
    
    public static class ReorderChaptersRequest {
        @JsonProperty("volume_id")
        public String volumeId;

        @JsonProperty("chapter_order")
        public List<String> chapterOrder;
    }
}
