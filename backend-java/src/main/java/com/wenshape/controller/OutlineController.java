package com.wenshape.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.Project;
import com.wenshape.model.entity.Volume;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.ProjectStorage;
import com.wenshape.storage.VolumeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
/**
 * 大纲控制器 - 独立大纲管理（YAML 存储，与 Python 后端对齐）
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/outline")
@RequiredArgsConstructor
public class OutlineController {

    private final DraftStorage draftStorage;
    private final VolumeStorage volumeStorage;
    private final ProjectStorage projectStorage;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final Map<String, ExplorationSession> explorationSessions = new ConcurrentHashMap<>();

    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME_ID_PATTERN = Pattern.compile("V(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile("^第([一二三四五六七八九十百零两\\d]+)章(?:\\s*[：:、\\-—]\\s*|\\s+)?(.*)$");
    private static final Pattern SCENE_NUMBERED_PATTERN = Pattern.compile("^\\s*(\\d{1,2})[\\.、:：\\)]\\s*(.+)$");
    private static final Pattern SCENE_NAMED_PATTERN = Pattern.compile("^\\s*(?:[-*]\\s*)?(场景\\s*\\d+|Scene\\s*\\d+)\\s*(?:[：:、\\-—]\\s*)?(.*)$", Pattern.CASE_INSENSITIVE);

    // ========== GET 全量大纲 ==========

    @GetMapping
    public Map<String, Object> getProjectOutline(@PathVariable String projectId) {
        Map<String, Object> master = readOutlineYaml(projectId, "master.yaml");

        // volumes
        List<Volume> volumes = volumeStorage.listVolumes(projectId);
        List<Map<String, Object>> volumeItems = new ArrayList<>();
        for (Volume vol : volumes) {
            Map<String, Object> raw = readOutlineYaml(projectId, "volumes/" + vol.getId() + ".yaml");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("volume_id", vol.getId());
            item.put("title", vol.getTitle() != null ? vol.getTitle() : "");
            item.put("content", raw.get("content"));
            item.put("updated_at", raw.get("updated_at"));
            volumeItems.add(item);
        }

        // chapters
        List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, null);
        Map<String, String> titleMap = new HashMap<>();
        Map<String, String> volMap = new HashMap<>();
        for (ChapterSummary s : summaries) {
            String cid = canonicalize(s.getChapter());
            titleMap.put(cid, s.getTitle() != null ? s.getTitle() : "");
            volMap.put(cid, s.getVolumeId() != null ? s.getVolumeId() : extractVolumeId(cid));
        }

        Set<String> chapterIds = new LinkedHashSet<>();
        draftStorage.listChapters(projectId).stream()
                .map(this::canonicalize)
                .filter(value -> !value.isBlank())
                .forEach(chapterIds::add);
        summaries.stream()
                .map(ChapterSummary::getChapter)
                .map(this::canonicalize)
                .filter(value -> !value.isBlank())
                .forEach(chapterIds::add);
        listOutlineChapterIds(projectId).forEach(chapterIds::add);

        List<String> chapters = new ArrayList<>(chapterIds);
        chapters.sort(this::compareChapterIds);

        List<Map<String, Object>> chapterItems = new ArrayList<>();
        for (String ch : chapters) {
            String cid = canonicalize(ch);
            Map<String, Object> raw = readOutlineYaml(projectId, "chapters/" + cid + ".yaml");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapter_id", cid);
            item.put("volume_id", volMap.getOrDefault(cid, extractVolumeId(cid)));
            item.put("title", titleMap.getOrDefault(cid, ""));
            item.put("content", raw.get("content"));
            item.put("updated_at", raw.get("updated_at"));
            chapterItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("master", master);
        result.put("volumes", volumeItems);
        result.put("chapters", chapterItems);
        return result;
    }

    // ========== 保存 ==========

    @PutMapping("/master")
    public Map<String, Object> saveMasterOutline(@PathVariable String projectId,
                                                  @RequestBody Map<String, Object> body) throws IOException {
        String content = (String) body.getOrDefault("content", "");
        return writeOutlineYaml(projectId, "master.yaml", content);
    }

    @PutMapping("/volumes/{volumeId}")
    public Map<String, Object> saveVolumeOutline(@PathVariable String projectId,
                                                  @PathVariable String volumeId,
                                                  @RequestBody Map<String, Object> body) throws IOException {
        String content = (String) body.getOrDefault("content", "");
        Map<String, Object> saved = writeOutlineYaml(projectId, "volumes/" + volumeId + ".yaml", content);
        saved.put("volume_id", volumeId);
        return saved;
    }

    @PutMapping("/chapters/{chapterId}")
    public Map<String, Object> saveChapterOutline(@PathVariable String projectId,
                                                   @PathVariable String chapterId,
                                                   @RequestBody Map<String, Object> body) throws IOException {
        String cid = canonicalize(chapterId);
        String content = (String) body.getOrDefault("content", "");
        Map<String, Object> saved = writeOutlineYaml(projectId, "chapters/" + cid + ".yaml", content);
        saved.put("chapter_id", cid);
        return saved;
    }

    /**
     * 从现有章节正文反向提炼“可执行章节纲”
     * POST /projects/{projectId}/outline/chapters/{chapterId}/reverse-engineer
     */
    @PostMapping("/chapters/{chapterId}/reverse-engineer")
    public Map<String, Object> reverseEngineerChapterOutline(@PathVariable String projectId,
                                                             @PathVariable String chapterId,
                                                             @RequestBody(required = false) Map<String, Object> body) throws IOException {
        String cid = canonicalize(chapterId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();
        boolean save = !Boolean.FALSE.equals(requestBody.get("save"));

        String providedContent = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();
        String chapterContent = !providedContent.isBlank()
                ? providedContent
                : draftStorage.getFinalDraft(projectId, cid).orElse("").trim();
        if (chapterContent.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapter content is empty");
        }

        String volumeId = extractVolumeId(cid);
        String masterContent = Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();
        String volumeContent = Objects.toString(readOutlineYaml(projectId, "volumes/" + volumeId + ".yaml").getOrDefault("content", ""), "").trim();
        String existingChapterOutline = Objects.toString(readOutlineYaml(projectId, "chapters/" + cid + ".yaml").getOrDefault("content", ""), "").trim();

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", buildReverseOutlineSystemPrompt(language)),
                Map.of("role", "user", "content", buildReverseOutlineUserPrompt(
                        language,
                        cid,
                        masterContent,
                        volumeContent,
                        existingChapterOutline,
                        chapterContent,
                        instruction
                ))
        );

        String providerId = llmGateway.getProviderForAgent("writer");
        String generated = llmGateway.chat(messages, providerId, null, 2600).getContent().trim();
        if (generated.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "empty reverse-engineered outline");
        }

        Map<String, Object> saved;
        if (save) {
            saved = writeOutlineYaml(projectId, "chapters/" + cid + ".yaml", generated);
        } else {
            saved = new LinkedHashMap<>();
            saved.put("content", generated);
            saved.put("updated_at", null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chapter_id", cid);
        result.put("content", generated);
        result.put("updated_at", saved.get("updated_at"));
        result.put("saved", save);
        result.put("source_type", "reverse_engineered");
        result.put("spec_level", "executable");
        return result;
    }

    /**
     * 按章节纲重构当前正文，但不直接保存，返回 diff 预览供前端确认
     * POST /projects/{projectId}/outline/chapters/{chapterId}/rewrite-draft
     */
    @PostMapping("/chapters/{chapterId}/rewrite-draft")
    public Map<String, Object> rewriteDraftFromOutline(@PathVariable String projectId,
                                                       @PathVariable String chapterId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        String cid = canonicalize(chapterId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();

        String providedDraft = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();
        String originalContent = !providedDraft.isBlank()
                ? providedDraft
                : draftStorage.getFinalDraft(projectId, cid).orElse("").trim();
        if (originalContent.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "draft content is empty");
        }

        String providedOutline = Objects.toString(requestBody.getOrDefault("outline_content", ""), "").trim();
        String chapterOutline = !providedOutline.isBlank()
                ? providedOutline
                : Objects.toString(readOutlineYaml(projectId, "chapters/" + cid + ".yaml").getOrDefault("content", ""), "").trim();
        if (chapterOutline.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapter outline is empty");
        }

        String masterContent = Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();
        String volumeContent = Objects.toString(
                readOutlineYaml(projectId, "volumes/" + extractVolumeId(cid) + ".yaml").getOrDefault("content", ""),
                ""
        ).trim();

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", buildOutlineRewriteSystemPrompt(language)),
                    Map.of("role", "user", "content", buildOutlineRewriteUserPrompt(
                            language,
                            cid,
                            masterContent,
                            volumeContent,
                            chapterOutline,
                            originalContent,
                            instruction
                    ))
            );

            String providerId = llmGateway.getProviderForAgent("editor");
            String revised = llmGateway.chat(messages, providerId, null, 4200).getContent().trim();
            if (revised.isBlank()) {
                throw new IllegalStateException("empty rewritten draft");
            }

            String originalNorm = normalizeForCompare(originalContent);
            String revisedNorm = normalizeForCompare(revised);
            if (originalNorm.equals(revisedNorm)) {
                return Map.of(
                        "success", false,
                        "error", "未生成可应用的结构性改写：请先手动补充章节纲中的“必须保留 / 必增强 / 结尾落点”，再重试。"
                );
            }

            return Map.of(
                    "success", true,
                    "chapter", cid,
                    "revised_content", revised,
                    "word_count", revised.length(),
                    "diff_preview", buildDiffPreview(originalContent, revised),
                    "change_stats", buildChangeStats(originalContent, revised)
            );
        } catch (Exception e) {
            log.error("按章节纲重构正文失败: project={}, chapter={}", projectId, cid, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 按章节纲中的指定场景重构正文，优先只改该场景及必要衔接，不直接保存
     * POST /projects/{projectId}/outline/chapters/{chapterId}/rewrite-scene-draft
     */
    @PostMapping("/chapters/{chapterId}/rewrite-scene-draft")
    public Map<String, Object> rewriteDraftSceneFromOutline(@PathVariable String projectId,
                                                            @PathVariable String chapterId,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        String cid = canonicalize(chapterId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();

        String providedDraft = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();
        String originalContent = !providedDraft.isBlank()
                ? providedDraft
                : draftStorage.getFinalDraft(projectId, cid).orElse("").trim();
        if (originalContent.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "draft content is empty");
        }

        String providedOutline = Objects.toString(requestBody.getOrDefault("outline_content", ""), "").trim();
        String chapterOutline = !providedOutline.isBlank()
                ? providedOutline
                : Objects.toString(readOutlineYaml(projectId, "chapters/" + cid + ".yaml").getOrDefault("content", ""), "").trim();
        if (chapterOutline.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapter outline is empty");
        }

        List<ParsedScene> scenes = parseOutlineScenes(chapterOutline);
        if (scenes.isEmpty()) {
            return Map.of(
                    "success", false,
                    "error", "当前章节纲里没有可识别的场景拆分，请先补上“场景拆分”后再按场景重构。"
            );
        }

        int sceneIndex = 0;
        Object rawSceneIndex = requestBody.get("scene_index");
        if (rawSceneIndex instanceof Number number) {
            sceneIndex = number.intValue();
        } else if (rawSceneIndex != null) {
            try {
                sceneIndex = Integer.parseInt(String.valueOf(rawSceneIndex).trim());
            } catch (NumberFormatException ignore) {
                sceneIndex = 0;
            }
        }
        if (sceneIndex <= 0 || sceneIndex > scenes.size()) {
            return Map.of(
                    "success", false,
                    "error", "scene_index 无效，请先在场景列表里选择一个有效场景。"
            );
        }

        ParsedScene targetScene = scenes.get(sceneIndex - 1);
        String masterContent = Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();
        String volumeContent = Objects.toString(
                readOutlineYaml(projectId, "volumes/" + extractVolumeId(cid) + ".yaml").getOrDefault("content", ""),
                ""
        ).trim();

        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", buildSceneRewriteSystemPrompt(language)),
                    Map.of("role", "user", "content", buildSceneRewriteUserPrompt(
                            language,
                            cid,
                            masterContent,
                            volumeContent,
                            chapterOutline,
                            originalContent,
                            scenes,
                            targetScene,
                            instruction
                    ))
            );

            String providerId = llmGateway.getProviderForAgent("editor");
            String revised = llmGateway.chat(messages, providerId, null, 3200).getContent().trim();
            if (revised.isBlank()) {
                throw new IllegalStateException("empty rewritten scene draft");
            }

            String originalNorm = normalizeForCompare(originalContent);
            String revisedNorm = normalizeForCompare(revised);
            if (originalNorm.equals(revisedNorm)) {
                return Map.of(
                        "success", false,
                        "error", "未生成可应用的场景重构：请把该场景的目标、冲突或结尾落点写得更具体一点，再重试。"
                );
            }

            return Map.of(
                    "success", true,
                    "chapter", cid,
                    "scene_index", sceneIndex,
                    "scene_title", targetScene.title(),
                    "revised_content", revised,
                    "word_count", revised.length(),
                    "diff_preview", buildDiffPreview(originalContent, revised),
                    "change_stats", buildChangeStats(originalContent, revised)
            );
        } catch (Exception e) {
            log.error("按场景重构正文失败: project={}, chapter={}, scene={}", projectId, cid, sceneIndex, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ========== LLM 生成大纲 ==========

    /**
     * AI 生成章节大纲
     * POST /projects/{projectId}/outline/generate
     */
    @PostMapping("/generate")
    public Map<String, Object> generateOutline(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        String chapterId = (String) body.getOrDefault("chapter_id", "");
        String instruction = (String) body.getOrDefault("instruction", "");
        String language = resolveOutlineLanguage(projectId, (String) body.get("language"));

        if (chapterId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapter_id is required");
        }

        String cid = canonicalize(chapterId);

        // 读取已有大纲作为参考
        Map<String, Object> masterOutline = readOutlineYaml(projectId, "master.yaml");
        String masterContent = (String) masterOutline.getOrDefault("content", "");

        String systemPrompt;
        String userPrompt;
        if ("en".equals(language)) {
            systemPrompt = "You are a professional fiction planning assistant who writes actionable chapter outlines.";
            userPrompt = String.format("""
                    Generate a detailed outline for chapter %s.

                    %s%s

                    Requirements:
                    1. The outline must be concrete and executable, including main scenes and plot beats.
                    2. Mark the purpose of each scene (plot progression, character development, foreshadowing, etc.).
                    3. Keep it around 300-500 words.

                    Output only the outline content, with no extra explanation.
                    """,
                    cid,
                    masterContent.isBlank() ? "" : "Master outline reference:\n" + masterContent.substring(0, Math.min(2000, masterContent.length())) + "\n\n",
                    instruction.isBlank() ? "" : "Instruction: " + instruction);
        } else {
            systemPrompt = "你是一位专业的小说策划助手，擅长为章节生成详细的写作大纲。";
            userPrompt = String.format("""
                    请为章节 %s 生成详细的写作大纲。

                    %s%s

                    要求：
                    1. 大纲要具体可执行，包含主要场景和情节节点
                    2. 标注每个场景的目的（推进情节/塑造人物/铺垫伏笔等）
                    3. 字数控制在 300-500 字

                    直接输出大纲内容，不要加额外说明。
                    """,
                    cid,
                    masterContent.isBlank() ? "" : "总大纲参考：\n" + masterContent.substring(0, Math.min(2000, masterContent.length())) + "\n\n",
                    instruction.isBlank() ? "" : "创作指令：" + instruction);
        }

        var resp = llmGateway.chat(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                llmGateway.getProviderForAgent("writer"), null, 2000);
        String generated = resp.getContent().trim();

        return Map.of(
                "chapter_id", cid,
                "content", generated,
                "success", true
        );
    }

    /**
     * AI 生成总纲候选
     * POST /projects/{projectId}/outline/master/generate-candidates
     */
    @PostMapping("/master/generate-candidates")
    public Map<String, Object> generateMasterOutlineCandidates(@PathVariable String projectId,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();
        String providedContent = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();

        Project project = projectStorage.getProject(projectId).orElse(null);
        String projectName = project != null ? firstNonBlank(project.getName(), projectId) : projectId;
        String projectDescription = project != null ? firstNonBlank(project.getDescription(), "") : "";

        String currentMasterContent = !providedContent.isBlank()
                ? providedContent
                : Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();
        String volumeOverview = buildExistingVolumeOutlineOverview(projectId, language);
        String recentChapterOverview = buildGlobalRecentSummaryContext(projectId, language);

        List<MasterOutlineCandidate> candidates;
        String recommendedRouteId = "";
        try {
            String systemPrompt = buildMasterOutlineCandidateSystemPrompt(language);
            String userPrompt = buildMasterOutlineCandidateUserPrompt(
                    projectName,
                    projectDescription,
                    currentMasterContent,
                    volumeOverview,
                    recentChapterOverview,
                    instruction,
                    language
            );
            var response = llmGateway.chatOnce(
                    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    llmGateway.getProviderForAgent("writer"),
                    0.45,
                    3200
            );
            String raw = response.getContent() != null ? response.getContent().trim() : "";
            MasterOutlineCandidateParseResult parsed = parseMasterOutlineCandidates(
                    projectName,
                    currentMasterContent,
                    volumeOverview,
                    recentChapterOverview,
                    instruction,
                    raw,
                    language
            );
            candidates = parsed.candidates();
            recommendedRouteId = parsed.recommendedRouteId();
        } catch (Exception e) {
            log.warn("生成总纲候选失败，使用回退候选: project={}, reason={}", projectId, e.getMessage());
            candidates = fallbackMasterOutlineCandidates(
                    projectName,
                    currentMasterContent,
                    volumeOverview,
                    recentChapterOverview,
                    instruction,
                    language
            );
            recommendedRouteId = candidates.isEmpty() ? "" : candidates.get(0).routeId();
        }

        if (candidates.isEmpty()) {
            candidates = fallbackMasterOutlineCandidates(
                    projectName,
                    currentMasterContent,
                    volumeOverview,
                    recentChapterOverview,
                    instruction,
                    language
            );
            recommendedRouteId = candidates.isEmpty() ? "" : candidates.get(0).routeId();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("project_id", projectId);
        result.put("project_name", projectName);
        result.put("recommended_route_id", recommendedRouteId);
        result.put("candidates", candidates.stream().map(MasterOutlineCandidate::toMap).toList());
        return result;
    }

    /**
     * AI 生成分卷纲候选
     * POST /projects/{projectId}/outline/volumes/{volumeId}/generate-candidates
     */
    @PostMapping("/volumes/{volumeId}/generate-candidates")
    public Map<String, Object> generateVolumeOutlineCandidates(@PathVariable String projectId,
                                                               @PathVariable String volumeId,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        String canonicalVolumeId = canonicalizeVolumeId(volumeId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();
        String providedContent = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();

        Map<String, Object> masterOutline = readOutlineYaml(projectId, "master.yaml");
        String masterContent = Objects.toString(masterOutline.getOrDefault("content", ""), "").trim();

        Map<String, Object> volumeOutline = readOutlineYaml(projectId, "volumes/" + canonicalVolumeId + ".yaml");
        String currentVolumeContent = !providedContent.isBlank()
                ? providedContent
                : Objects.toString(volumeOutline.getOrDefault("content", ""), "").trim();

        Volume volume = volumeStorage.listVolumes(projectId).stream()
                .filter(item -> canonicalVolumeId.equalsIgnoreCase(item.getId()))
                .findFirst()
                .orElse(null);
        String volumeTitle = volume != null && volume.getTitle() != null && !volume.getTitle().isBlank()
                ? volume.getTitle()
                : canonicalVolumeId;

        String previousVolumeContext = buildPreviousVolumeContext(projectId, canonicalVolumeId, language);
        String recentSummaryContext = buildVolumeRecentSummaryContext(projectId, canonicalVolumeId, language);

        List<VolumeOutlineCandidate> candidates;
        String recommendedRouteId = "";
        try {
            String systemPrompt = buildVolumeOutlineCandidateSystemPrompt(language);
            String userPrompt = buildVolumeOutlineCandidateUserPrompt(
                    canonicalVolumeId,
                    volumeTitle,
                    masterContent,
                    previousVolumeContext,
                    currentVolumeContent,
                    recentSummaryContext,
                    instruction,
                    language
            );
            var response = llmGateway.chatOnce(
                    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    ),
                    llmGateway.getProviderForAgent("writer"),
                    0.45,
                    3200
            );
            String raw = response.getContent() != null ? response.getContent().trim() : "";
            VolumeOutlineCandidateParseResult parsed = parseVolumeOutlineCandidates(
                    canonicalVolumeId,
                    volumeTitle,
                    raw,
                    masterContent,
                    currentVolumeContent,
                    instruction,
                    language
            );
            candidates = parsed.candidates();
            recommendedRouteId = parsed.recommendedRouteId();
        } catch (Exception e) {
            log.warn("生成分卷纲候选失败，使用回退候选: project={}, volumeId={}, reason={}", projectId, canonicalVolumeId, e.getMessage());
            candidates = fallbackVolumeOutlineCandidates(
                    canonicalVolumeId,
                    volumeTitle,
                    masterContent,
                    currentVolumeContent,
                    instruction,
                    language
            );
            recommendedRouteId = candidates.isEmpty() ? "" : candidates.get(0).routeId();
        }

        if (candidates.isEmpty()) {
            candidates = fallbackVolumeOutlineCandidates(
                    canonicalVolumeId,
                    volumeTitle,
                    masterContent,
                    currentVolumeContent,
                    instruction,
                    language
            );
            recommendedRouteId = candidates.isEmpty() ? "" : candidates.get(0).routeId();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("volume_id", canonicalVolumeId);
        result.put("volume_title", volumeTitle);
        result.put("recommended_route_id", recommendedRouteId);
        result.put("candidates", candidates.stream().map(VolumeOutlineCandidate::toMap).toList());
        return result;
    }

    /**
     * AI 自动将分卷纲拆成章节规划
     * POST /projects/{projectId}/outline/volumes/{volumeId}/plan-chapters
     */
    @PostMapping("/volumes/{volumeId}/plan-chapters")
    public Map<String, Object> planVolumeChapters(@PathVariable String projectId,
                                                  @PathVariable String volumeId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        String canonicalVolumeId = canonicalizeVolumeId(volumeId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String providedContent = Objects.toString(requestBody.getOrDefault("content", ""), "").trim();

        Map<String, Object> masterOutline = readOutlineYaml(projectId, "master.yaml");
        String masterContent = Objects.toString(masterOutline.getOrDefault("content", ""), "").trim();

        Map<String, Object> volumeOutline = readOutlineYaml(projectId, "volumes/" + canonicalVolumeId + ".yaml");
        String volumeContent = !providedContent.isBlank()
                ? providedContent
                : Objects.toString(volumeOutline.getOrDefault("content", ""), "").trim();
        if (volumeContent.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "volume outline is empty");
        }

        Volume volume = volumeStorage.listVolumes(projectId).stream()
                .filter(item -> canonicalVolumeId.equalsIgnoreCase(item.getId()))
                .findFirst()
                .orElse(null);
        String volumeTitle = volume != null && volume.getTitle() != null && !volume.getTitle().isBlank()
                ? volume.getTitle()
                : canonicalVolumeId;

        List<PlannedChapter> chapters;
        try {
            String systemPrompt = buildVolumeSplitSystemPrompt(language);
            String userPrompt = buildVolumeSplitUserPrompt(language, canonicalVolumeId, volumeTitle, masterContent, volumeContent);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );
            String rawResponse = requestVolumeSplitResponse(messages);
            chapters = parsePlannedChapters(canonicalVolumeId, rawResponse, volumeContent, language);
        } catch (Exception e) {
            log.warn("分卷自动拆章失败，使用回退解析: project={}, volumeId={}, reason={}", projectId, canonicalVolumeId, e.getMessage());
            chapters = fallbackPlannedChapters(canonicalVolumeId, volumeContent, language);
        }

        if (chapters.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "failed to plan chapters from volume outline");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("volume_id", canonicalVolumeId);
        result.put("volume_title", volumeTitle);
        result.put("chapters", chapters.stream().map(PlannedChapter::toMap).toList());
        return result;
    }

    /**
     * 对卷级章节规划中的单个章节做 AI 拆分预览
     * POST /projects/{projectId}/outline/volumes/{volumeId}/split-planned-chapter
     */
    @PostMapping("/volumes/{volumeId}/split-planned-chapter")
    public Map<String, Object> splitPlannedChapter(@PathVariable String projectId,
                                                   @PathVariable String volumeId,
                                                   @RequestBody(required = false) Map<String, Object> body) throws IOException {
        String canonicalVolumeId = canonicalizeVolumeId(volumeId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String instruction = Objects.toString(requestBody.getOrDefault("instruction", ""), "").trim();
        if (instruction.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "instruction is required");
        }

        JsonNode requestNode = objectMapper.valueToTree(requestBody);
        int desiredSplitCount = clampSplitCount(positiveInt(
                requestNode,
                2,
                "desired_split_count",
                "desiredSplitCount",
                "split_count",
                "splitCount"
        ));

        String providedMasterContent = Objects.toString(
                requestBody.getOrDefault("master_outline_content", requestBody.getOrDefault("master_content", "")),
                ""
        ).trim();
        String masterContent = !providedMasterContent.isBlank()
                ? providedMasterContent
                : Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();

        String providedVolumeContent = Objects.toString(
                requestBody.getOrDefault("volume_outline_content", requestBody.getOrDefault("content", "")),
                ""
        ).trim();
        String volumeContent = !providedVolumeContent.isBlank()
                ? providedVolumeContent
                : Objects.toString(readOutlineYaml(projectId, "volumes/" + canonicalVolumeId + ".yaml").getOrDefault("content", ""), "").trim();

        Volume volume = volumeStorage.listVolumes(projectId).stream()
                .filter(item -> canonicalVolumeId.equalsIgnoreCase(item.getId()))
                .findFirst()
                .orElse(null);
        String requestedVolumeTitle = Objects.toString(requestBody.getOrDefault("volume_title", ""), "").trim();
        String volumeTitle = firstNonBlank(
                requestedVolumeTitle,
                volume != null ? volume.getTitle() : "",
                canonicalVolumeId
        );

        List<PlannedChapter> allChapters = parseChaptersFromRequest(
                canonicalVolumeId,
                requestBody.get("chapters"),
                language
        );

        JsonNode targetNode = objectMapper.valueToTree(requestBody.get("target_chapter"));
        String targetChapterId = canonicalize(firstNonBlank(
                readText(targetNode, "chapter_id", "chapterId"),
                Objects.toString(requestBody.getOrDefault("target_chapter_id", ""), "").trim()
        ));

        int targetIndex = -1;
        PlannedChapter targetChapter = null;
        for (int index = 0; index < allChapters.size(); index++) {
            PlannedChapter chapter = allChapters.get(index);
            if (normalizeForCompare(chapter.chapterId()).equalsIgnoreCase(normalizeForCompare(targetChapterId))) {
                targetIndex = index;
                targetChapter = chapter;
                break;
            }
        }

        if (targetChapter == null && targetNode != null && !targetNode.isNull() && !targetNode.isMissingNode()) {
            int fallbackNo = positiveInt(targetNode, targetIndex >= 0 ? targetIndex + 1 : 1, "chapter_no", "chapterNo", "no", "index");
            targetChapter = parseRequestedPlannedChapter(canonicalVolumeId, targetNode, fallbackNo, language);
            targetChapterId = canonicalize(targetChapter.chapterId());
        }

        if (targetChapter == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "target_chapter is required");
        }

        if (targetIndex < 0) {
            targetIndex = Math.max(0, targetChapter.chapterNo() - 1);
        }

        PlannedChapter previousChapter = targetIndex > 0 && targetIndex - 1 < allChapters.size()
                ? allChapters.get(targetIndex - 1)
                : null;
        PlannedChapter nextChapter = targetIndex >= 0 && targetIndex + 1 < allChapters.size()
                ? allChapters.get(targetIndex + 1)
                : null;

        PlannedChapterSplitResult splitResult;
        try {
            String raw = requestPlannedChapterSplitResponse(List.of(
                    Map.of("role", "system", "content", buildPlannedChapterSplitSystemPrompt(language)),
                    Map.of("role", "user", "content", buildPlannedChapterSplitUserPrompt(
                            language,
                            canonicalVolumeId,
                            volumeTitle,
                            desiredSplitCount,
                            masterContent,
                            volumeContent,
                            allChapters,
                            targetChapter,
                            previousChapter,
                            nextChapter,
                            instruction
                    ))
            ));
            splitResult = parsePlannedChapterSplitResult(
                    canonicalVolumeId,
                    targetChapter.chapterNo(),
                    targetChapter,
                    raw,
                    desiredSplitCount,
                    language
            );
        } catch (Exception e) {
            log.warn("AI 拆分章节规划失败，使用回退结果: project={}, volumeId={}, chapterId={}, reason={}",
                    projectId, canonicalVolumeId, targetChapter.chapterId(), e.getMessage());
            splitResult = fallbackPlannedChapterSplitResult(
                    canonicalVolumeId,
                    targetChapter,
                    desiredSplitCount,
                    language
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("volume_id", canonicalVolumeId);
        result.put("volume_title", volumeTitle);
        result.put("target_chapter_id", canonicalize(targetChapter.chapterId()));
        result.putAll(splitResult.toMap());
        return result;
    }

    private String requestVolumeSplitResponse(List<Map<String, String>> messages) {
        String providerId = llmGateway.getProviderForAgent("writer");
        int promptChars = messages.stream()
                .mapToInt(message -> Objects.toString(message.get("content"), "").length())
                .sum();

        try {
            String streamed = llmGateway.streamChat(messages, providerId, 0.4, 3200)
                    .collectList()
                    .map(parts -> String.join("", parts))
                    .block(Duration.ofMinutes(8));
            if (streamed == null || streamed.isBlank()) {
                throw new IllegalStateException("empty stream response");
            }
            log.info("分卷自动拆章流式完成: provider={}, promptChars={}, outputChars={}",
                    providerId, promptChars, streamed.length());
            return streamed;
        } catch (Exception streamError) {
            log.warn("分卷自动拆章流式失败，回退同步单次请求: provider={}, promptChars={}, reason={}",
                    providerId, promptChars, streamError.getMessage());
        }

        var response = llmGateway.chatOnce(
                messages,
                providerId,
                0.4,
                3200
        );
        String content = response.getContent() != null ? response.getContent().trim() : "";
        if (content.isBlank()) {
            throw new IllegalStateException("empty chapter plan content");
        }
        log.info("分卷自动拆章同步完成: provider={}, promptChars={}, outputChars={}",
                providerId, promptChars, content.length());
        return content;
    }

    private String requestPlannedChapterSplitResponse(List<Map<String, String>> messages) {
        String providerId = llmGateway.getProviderForAgent("writer");
        int promptChars = messages.stream()
                .mapToInt(message -> Objects.toString(message.get("content"), "").length())
                .sum();
        var response = llmGateway.chatOnce(messages, providerId, 0.45, 2600);
        String content = response.getContent() != null ? response.getContent().trim() : "";
        if (content.isBlank()) {
            throw new IllegalStateException("empty planned chapter split content");
        }
        log.info("AI 拆分章节规划完成: provider={}, promptChars={}, outputChars={}",
                providerId, promptChars, content.length());
        return content;
    }

    /**
     * 将章节规划一键应用到资源管理器（创建章节 + 保存章节细纲 + 章节概述）
     * POST /projects/{projectId}/outline/volumes/{volumeId}/apply-chapter-plan
     */
    @PostMapping("/volumes/{volumeId}/apply-chapter-plan")
    public Map<String, Object> applyVolumeChapterPlan(@PathVariable String projectId,
                                                      @PathVariable String volumeId,
                                                      @RequestBody Map<String, Object> body) throws IOException {
        String canonicalVolumeId = canonicalizeVolumeId(volumeId);
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        List<PlannedChapter> chapters = parseChaptersFromRequest(
                canonicalVolumeId,
                requestBody.get("chapters"),
                language
        );
        if (chapters.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapters is required");
        }

        Set<String> existingDraftChapters = new LinkedHashSet<>(draftStorage.listChapters(projectId));
        Set<String> existingSummaryChapters = draftStorage.listChapterSummaries(projectId, canonicalVolumeId).stream()
                .map(ChapterSummary::getChapter)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        // 计算当前分卷中已有的最大 orderIndex，作为新规划章节的起始偏移量
        int nextIndex = 0;
        List<ChapterSummary> existingSummaries = draftStorage.listChapterSummaries(projectId, canonicalVolumeId);
        for (ChapterSummary s : existingSummaries) {
            if (s.getOrderIndex() != null) {
                nextIndex = Math.max(nextIndex, s.getOrderIndex() + 1);
            }
        }

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (int index = 0; index < chapters.size(); index++) {
            PlannedChapter chapter = chapters.get(index);
            String chapterId = canonicalize(chapter.chapterId());
            boolean existed = existingDraftChapters.contains(chapterId) || existingSummaryChapters.contains(chapterId);

            if (!existingDraftChapters.contains(chapterId)) {
                draftStorage.saveCurrentDraft(projectId, chapterId, "", 0, null, false);
                existingDraftChapters.add(chapterId);
            }

            ChapterSummary summary = draftStorage.getChapterSummary(projectId, chapterId)
                    .orElseGet(() -> ChapterSummary.builder().chapter(chapterId).build());
            summary.setChapter(chapterId);
            summary.setVolumeId(canonicalVolumeId);
            if (summary.getOrderIndex() == null) {
                summary.setOrderIndex(nextIndex + index);
            }
            summary.setTitle(chapter.title());
            summary.setBriefSummary(chapter.summary());
            draftStorage.saveChapterSummary(projectId, summary);

            writeOutlineYaml(projectId, "chapters/" + chapterId + ".yaml", chapter.outlineContent());

            if (existed) {
                updated.add(chapterId);
            } else {
                created.add(chapterId);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("volume_id", canonicalVolumeId);
        result.put("created", created);
        result.put("updated", updated);
        result.put("count", chapters.size());
        result.put("chapters", chapters.stream().map(PlannedChapter::toMap).toList());
        return result;
    }

    /**
     * 续写探索：启动一个探索会话（MVP：仅支持“下一章 / 接下来 2~3 章”，默认挂到当前卷）
     * POST /projects/{projectId}/outline/exploration/start
     */
    @PostMapping("/exploration/start")
    public Map<String, Object> startExploration(@PathVariable String projectId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        cleanupExpiredExplorationSessions();
        Map<String, Object> requestBody = body != null ? body : Map.of();
        String language = resolveOutlineLanguage(projectId, (String) requestBody.get("language"));
        String scope = normalizeExplorationScope(Objects.toString(requestBody.getOrDefault("scope", ""), ""));
        String requestedChapterId = canonicalize(Objects.toString(requestBody.getOrDefault("chapter_id", ""), "").trim());
        String requestedVolumeId = canonicalizeVolumeId(Objects.toString(requestBody.getOrDefault("volume_id", ""), "").trim());

        ExplorationSession session = buildExplorationSession(projectId, language, scope, requestedVolumeId, requestedChapterId);
        explorationSessions.put(session.sessionId, session);

        return Map.of(
                "success", true,
                "session", toExplorationSessionPayload(session)
        );
    }

    /**
     * 续写探索：回答问题，生成路线候选
     * POST /projects/{projectId}/outline/exploration/answer
     */
    @PostMapping("/exploration/answer")
    public Map<String, Object> answerExploration(@PathVariable String projectId,
                                                 @RequestBody Map<String, Object> body) {
        cleanupExpiredExplorationSessions();
        String sessionId = Objects.toString(body.getOrDefault("session_id", ""), "").trim();
        if (sessionId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "session_id is required");
        }

        ExplorationSession session = requireExplorationSession(projectId, sessionId);
        List<ExplorationDecision> decisions = parseExplorationDecisions(body.get("answers"), session.questions);
        if (decisions.size() < session.questions.size()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "please answer all exploration questions");
        }

        session.decisions = decisions;
        session.routes = requestExplorationRoutes(session);
        session.stage = "routes";
        session.selectedRouteId = "";
        session.generatedPlan = null;
        session.touch();

        return Map.of(
                "success", true,
                "session", toExplorationSessionPayload(session)
        );
    }

    /**
     * 续写探索：选择某条路线并生成可执行章节规划
     * POST /projects/{projectId}/outline/exploration/select-route
     */
    @PostMapping("/exploration/select-route")
    public Map<String, Object> selectExplorationRoute(@PathVariable String projectId,
                                                      @RequestBody Map<String, Object> body) throws IOException {
        cleanupExpiredExplorationSessions();
        String sessionId = Objects.toString(body.getOrDefault("session_id", ""), "").trim();
        String routeId = Objects.toString(body.getOrDefault("route_id", ""), "").trim();
        if (sessionId.isBlank() || routeId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "session_id and route_id are required");
        }

        ExplorationSession session = requireExplorationSession(projectId, sessionId);
        ExplorationRoute route = session.routes.stream()
                .filter(item -> routeId.equalsIgnoreCase(item.routeId))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "route_id is invalid"));

        List<PlannedChapter> chapters = requestExplorationPlan(session, route);
        Map<String, Object> generatedPlan = new LinkedHashMap<>();
        generatedPlan.put("volume_id", session.volumeId);
        generatedPlan.put("volume_title", session.volumeTitle);
        generatedPlan.put("route_id", route.routeId);
        generatedPlan.put("route_label", route.label);
        generatedPlan.put("chapters", chapters.stream().map(PlannedChapter::toMap).toList());

        session.selectedRouteId = route.routeId;
        session.generatedPlan = generatedPlan;
        session.stage = "generated";
        session.touch();

        return Map.of(
                "success", true,
                "session", toExplorationSessionPayload(session)
        );
    }

    // ========== 私有方法 ==========

    private Path outlineDir(String projectId) {
        return draftStorage.getProjectPath(projectId).resolve("outline");
    }

    private String resolveOutlineLanguage(String projectId, String requestedLanguage) {
        String explicit = normalizeLanguage(requestedLanguage);
        if (!explicit.isBlank()) {
            return explicit;
        }
        return projectStorage.getProject(projectId)
                .map(Project::getLanguage)
                .map(this::normalizeLanguage)
                .filter(value -> !value.isBlank())
                .orElse("zh");
    }

    private String normalizeLanguage(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("en")) {
            return "en";
        }
        if (raw.startsWith("zh")) {
            return "zh";
        }
        return "";
    }

    private String buildExistingVolumeOutlineOverview(String projectId, String language) {
        List<Volume> volumes = new ArrayList<>(volumeStorage.listVolumes(projectId));
        if (volumes.isEmpty()) {
            return "";
        }
        volumes.sort((left, right) -> compareVolumeIds(left.getId(), right.getId()));
        List<String> blocks = new ArrayList<>();
        for (Volume volume : volumes) {
            String volumeId = canonicalizeVolumeId(volume.getId());
            String title = firstNonBlank(volume.getTitle(), volumeId);
            String outline = Objects.toString(
                    readOutlineYaml(projectId, "volumes/" + volumeId + ".yaml").getOrDefault("content", ""),
                    ""
            ).trim();
            if (!outline.isBlank()) {
                blocks.add((language.equals("en") ? "Volume " : "分卷 ")
                        + volumeId + " · " + title + "\n"
                        + clipText(outline, 500));
            } else {
                List<ChapterSummary> summaries = new ArrayList<>(draftStorage.listChapterSummaries(projectId, volumeId));
                summaries.sort((left, right) -> compareChapterIds(left.getChapter(), right.getChapter()));
                if (!summaries.isEmpty()) {
                    String summaryBlock = summaries.stream()
                            .map(summary -> {
                                String chapterId = canonicalize(summary.getChapter());
                                String chapterTitle = firstNonBlank(summary.getTitle(), chapterId);
                                String brief = firstNonBlank(summary.getBriefSummary(), "");
                                return chapterId + " " + chapterTitle + (brief.isBlank() ? "" : "：" + clipText(brief, 80));
                            })
                            .limit(2)
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("");
                    if (!summaryBlock.isBlank()) {
                        blocks.add((language.equals("en") ? "Volume " : "分卷 ")
                                + volumeId + " · " + title + "\n"
                                + summaryBlock);
                    }
                }
            }
            if (blocks.size() >= 4) {
                break;
            }
        }
        return String.join("\n\n", blocks);
    }

    private String buildGlobalRecentSummaryContext(String projectId, String language) {
        List<ChapterSummary> summaries = new ArrayList<>(draftStorage.listChapterSummaries(projectId, null));
        if (summaries.isEmpty()) {
            return "";
        }
        summaries.sort((left, right) -> compareChapterIds(left.getChapter(), right.getChapter()));
        List<ChapterSummary> recent = summaries.size() <= 5
                ? summaries
                : summaries.subList(Math.max(0, summaries.size() - 5), summaries.size());
        StringBuilder sb = new StringBuilder();
        sb.append(language.equals("en") ? "Recent chapter progress:\n" : "最近章节进度：\n");
        for (ChapterSummary summary : recent) {
            String chapterId = canonicalize(summary.getChapter());
            String chapterTitle = firstNonBlank(summary.getTitle(), chapterId);
            String brief = firstNonBlank(summary.getBriefSummary(), "");
            sb.append("- ").append(chapterId).append(" ").append(chapterTitle);
            if (!brief.isBlank()) {
                sb.append("：").append(clipText(brief, 100));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String buildMasterOutlineCandidateSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a professional fiction architect.
                    Generate several clearly different master-outline candidates for a long-form fiction project.
                    Each candidate should feel like a usable global blueprint instead of a vague pitch.

                    Output valid JSON only.
                    """;
        }
        return """
                你是一位擅长连载小说结构设计的总纲策划助手。
                请为项目生成几条明显不同、可直接作为全书总纲使用的“总纲候选”，而不是泛泛简介。

                只输出合法 JSON，不要解释。
                """;
    }

    private String buildMasterOutlineCandidateUserPrompt(String projectName,
                                                         String projectDescription,
                                                         String currentMasterContent,
                                                         String volumeOverview,
                                                         String recentChapterOverview,
                                                         String instruction,
                                                         String language) {
        if ("en".equals(language)) {
            return String.format("""
                    Generate 3 clearly different master-outline candidates for the project "%s".

                    Available context:
                    Project description:
                    %s

                    Current master outline draft:
                    %s

                    Existing volume / arc overview:
                    %s

                    Recent chapter progress:
                    %s

                    Extra instruction:
                    %s

                    Requirements:
                    1. Candidate 1/2/3 must differ in global emphasis, long-term structure, and final destination.
                    2. Each candidate should answer: core premise, main conflict line, protagonist arc, stage-by-stage structure, and what kind of ending the story is moving toward.
                    3. outline_content must be directly usable as a master outline, around 500-900 words.
                    4. If there is existing draft content, treat it as editable reference rather than something to repeat mechanically.
                    5. Keep continuity with existing material; do not contradict already written core facts.

                    Return JSON in this format:
                    {
                      "recommended_route_id": "route_1",
                      "candidates": [
                        {
                          "route_id": "route_1",
                          "label": "route name",
                          "summary": "1-2 sentence positioning",
                          "why_it_fits": "why this route fits current material",
                          "focus_points": ["focus A", "focus B", "focus C"],
                          "planned_volume_count_hint": 4,
                          "outline_content": "final master outline text"
                        }
                      ]
                    }
                    """,
                    projectName,
                    projectDescription.isBlank() ? "(empty)" : clipText(projectDescription, 600),
                    currentMasterContent.isBlank() ? "(empty)" : clipText(currentMasterContent, 1600),
                    volumeOverview.isBlank() ? "(none yet)" : volumeOverview,
                    recentChapterOverview.isBlank() ? "(none yet)" : recentChapterOverview,
                    instruction.isBlank() ? "(none)" : instruction
            );
        }

        return String.format("""
                请为项目《%s》生成 3 条明显不同的“总纲候选”。

                可用上下文：
                项目描述：
                %s

                当前总纲草稿：
                %s

                现有分卷 / 结构概览：
                %s

                最近章节进度：
                %s

                额外要求：
                %s

                要求：
                1. 候选 1/2/3 在“全书主推进线、结构重心、最终落点”上必须有明显区别。
                2. 每条候选都要回答清楚：故事核心 premise、主冲突线、主角成长线、阶段推进结构，以及最终会往什么结局类型收束。
                3. outline_content 必须是可直接落到编辑器里的正式总纲，建议 500~900 字。
                4. 如果当前已有总纲草稿，把它视为“可重构参考”，不是机械复述材料。
                5. 要与现有内容连续，不要硬改已经写出的核心事实。

                严格输出 JSON，格式如下：
                {
                  "recommended_route_id": "route_1",
                  "candidates": [
                    {
                      "route_id": "route_1",
                      "label": "路线名",
                      "summary": "1~2 句定位",
                      "why_it_fits": "为什么适合当前素材",
                      "focus_points": ["重点一", "重点二", "重点三"],
                      "planned_volume_count_hint": 4,
                      "outline_content": "正式总纲正文"
                    }
                  ]
                }
                """,
                projectName,
                projectDescription.isBlank() ? "（空）" : clipText(projectDescription, 600),
                currentMasterContent.isBlank() ? "（空）" : clipText(currentMasterContent, 1600),
                volumeOverview.isBlank() ? "（暂无）" : volumeOverview,
                recentChapterOverview.isBlank() ? "（暂无）" : recentChapterOverview,
                instruction.isBlank() ? "（无）" : instruction
        );
    }

    private MasterOutlineCandidateParseResult parseMasterOutlineCandidates(String projectName,
                                                                           String currentMasterContent,
                                                                           String volumeOverview,
                                                                           String recentChapterOverview,
                                                                           String instruction,
                                                                           String raw,
                                                                           String language) throws IOException {
        if (raw == null || raw.isBlank()) {
            List<MasterOutlineCandidate> fallback = fallbackMasterOutlineCandidates(
                    projectName, currentMasterContent, volumeOverview, recentChapterOverview, instruction, language);
            return new MasterOutlineCandidateParseResult(
                    fallback,
                    fallback.isEmpty() ? "" : fallback.get(0).routeId()
            );
        }

        JsonNode root = extractJsonNode(raw);
        JsonNode candidatesNode = root.path("candidates");
        if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
            List<MasterOutlineCandidate> fallback = fallbackMasterOutlineCandidates(
                    projectName, currentMasterContent, volumeOverview, recentChapterOverview, instruction, language);
            return new MasterOutlineCandidateParseResult(
                    fallback,
                    fallback.isEmpty() ? "" : fallback.get(0).routeId()
            );
        }

        List<MasterOutlineCandidate> candidates = new ArrayList<>();
        int idx = 1;
        for (JsonNode node : candidatesNode) {
            String routeId = readText(node, "route_id", "routeId", "id");
            if (routeId.isBlank()) {
                routeId = "route_" + idx;
            }
            String label = readText(node, "label", "title", "route_label", "routeLabel");
            if (label.isBlank()) {
                label = ("en".equals(language) ? "Route " : "路线 ") + idx;
            }
            String summary = readText(node, "summary", "positioning", "pitch");
            String whyItFits = readText(node, "why_it_fits", "whyItFits", "reason");
            List<String> focusPoints = sanitizeStringList(readStringList(node, "focus_points", "focusPoints", "tags", "keywords"));
            int plannedVolumeCountHint = positiveInt(node, 0, "planned_volume_count_hint", "plannedVolumeCountHint", "volume_count_hint");
            String outlineContent = readText(node, "outline_content", "outlineContent", "outline", "content");
            if (outlineContent.isBlank()) {
                outlineContent = buildMasterOutlineFromCandidate(language, projectName, summary, whyItFits, focusPoints, plannedVolumeCountHint);
            }
            candidates.add(new MasterOutlineCandidate(
                    routeId,
                    label,
                    summary,
                    whyItFits,
                    focusPoints,
                    plannedVolumeCountHint,
                    outlineContent.trim()
            ));
            idx++;
        }

        String recommendedRouteId = readText(root, "recommended_route_id", "recommendedRouteId", "recommended");
        if (recommendedRouteId.isBlank() && !candidates.isEmpty()) {
            recommendedRouteId = candidates.get(0).routeId();
        }
        return new MasterOutlineCandidateParseResult(candidates, recommendedRouteId);
    }

    private String buildMasterOutlineFromCandidate(String language,
                                                   String projectName,
                                                   String summary,
                                                   String whyItFits,
                                                   List<String> focusPoints,
                                                   int plannedVolumeCountHint) {
        if ("en".equals(language)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Project: ").append(projectName).append("\n\n");
            if (!summary.isBlank()) {
                sb.append("Global Positioning:\n").append(summary).append("\n\n");
            }
            if (!whyItFits.isBlank()) {
                sb.append("Why this route fits:\n").append(whyItFits).append("\n\n");
            }
            if (!focusPoints.isEmpty()) {
                sb.append("Focus Points:\n");
                focusPoints.forEach(item -> sb.append("- ").append(item).append("\n"));
                sb.append("\n");
            }
            if (plannedVolumeCountHint > 0) {
                sb.append("Suggested volume count: ").append(plannedVolumeCountHint).append("\n");
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【故事定位】\n").append(firstNonBlank(summary, projectName)).append("\n\n");
        if (!whyItFits.isBlank()) {
            sb.append("【适配原因】\n").append(whyItFits).append("\n\n");
        }
        if (!focusPoints.isEmpty()) {
            sb.append("【核心重点】\n");
            focusPoints.forEach(item -> sb.append("- ").append(item).append("\n"));
            sb.append("\n");
        }
        if (plannedVolumeCountHint > 0) {
            sb.append("【建议卷数】\n约 ").append(plannedVolumeCountHint).append(" 卷\n");
        }
        return sb.toString().trim();
    }

    private List<MasterOutlineCandidate> fallbackMasterOutlineCandidates(String projectName,
                                                                         String currentMasterContent,
                                                                         String volumeOverview,
                                                                         String recentChapterOverview,
                                                                         String instruction,
                                                                         String language) {
        String currentHint = clipText(cleanOutlineText(currentMasterContent), 120);
        String volumeHint = clipText(cleanOutlineText(volumeOverview), 160);
        String recentHint = clipText(cleanOutlineText(recentChapterOverview), 160);
        String extraHint = clipText(cleanOutlineText(instruction), 80);

        List<MasterOutlineCandidate> candidates = new ArrayList<>();
        if ("en".equals(language)) {
            candidates.add(new MasterOutlineCandidate(
                    "route_1",
                    "Mainline / High Tension",
                    "Build the book around a strong core conflict and keep the long-form structure tightly centered on escalation.",
                    "Good when you want the project to feel decisive, plot-forward, and strongly driven by the main premise.",
                    List.of("core conflict", "phase escalation", "high-pressure ending"),
                    4,
                    """
                            Core Premise:
                            %s is built around a high-pressure main conflict that keeps intensifying instead of diffusing.

                            Long Structure:
                            - Early phase: establish the protagonist's immediate trap and primary desire
                            - Middle phase: widen the board, deepen costs, and expose hidden consequences
                            - Late phase: force irreversible choices and converge multiple lines into one decisive crisis

                            Character Arc:
                            The protagonist should not simply become stronger; they should be reshaped by the cost of staying on this path.

                            Ending Direction:
                            Aim for a hard-earned ending where the central conflict is resolved, but only after visible sacrifice.
                            """.formatted(projectName).trim()
            ));
            candidates.add(new MasterOutlineCandidate(
                    "route_2",
                    "Relationship / Character Core",
                    "Use the long-form structure to center loyalty shifts, emotional dependence, betrayal, and evolving human bonds.",
                    "Good when the most memorable engine of the story should come from character chemistry rather than event mechanics alone.",
                    List.of("relationship arcs", "trust shifts", "emotional payoff"),
                    5,
                    """
                            Core Premise:
                            %s should be structured so that the deepest changes come from human ties: alliance, desire, mistrust, protection, or betrayal.

                            Long Structure:
                            - Early phase: build unstable but compelling bonds
                            - Middle phase: apply pressure until those bonds start changing form
                            - Late phase: let the final resolution come from the transformed relationship structure, not plot mechanics alone

                            Reference:
                            %s

                            Ending Direction:
                            Aim for an ending with strong emotional aftertaste, where the final state of the core relationship defines the book's meaning.
                            """.formatted(
                                    projectName,
                                    currentHint.isBlank() ? "If current outline is empty, start from the most unstable core pair." : currentHint
                            ).trim()
            ));
            candidates.add(new MasterOutlineCandidate(
                    "route_3",
                    "Mystery / World Expansion",
                    "Use the master outline to reveal a progressively larger system behind the current events, broadening the scale of the project over time.",
                    "Good when the story needs a stronger sense of hidden rules, deeper layers, and stage-by-stage expansion.",
                    List.of("setting layers", "rule reveal", "larger board"),
                    6,
                    """
                            Core Premise:
                            %s should gradually reveal that the current conflicts are only the surface of a larger hidden structure.

                            Long Structure:
                            - Early phase: present mystery through concrete local danger
                            - Middle phase: connect separate anomalies into one deeper system
                            - Late phase: bring the protagonist into direct contact with the larger board and its rules

                            Existing signals:
                            %s
                            %s

                            Ending Direction:
                            Aim for an ending that resolves the first layer, while opening a larger understanding of the world's hidden architecture.
                            """.formatted(
                                    projectName,
                                    volumeHint.isBlank() ? "No prior volume outline reference." : volumeHint,
                                    recentHint.isBlank() ? (extraHint.isBlank() ? "Keep the pacing grounded in scenes, not exposition." : extraHint)
                                            : recentHint
                            ).trim()
            ));
            return candidates;
        }

        candidates.add(new MasterOutlineCandidate(
                "route_1",
                "主线高压版",
                "把整本书建立在一条持续升压的主冲突线上，让全书结构更集中、更强推进。",
                "适合你希望这本书更像强主线连载，而不是松散片段式推进。",
                List.of("主冲突升级", "阶段推进", "高压收束"),
                4,
                """
                        【故事核心】
                        《%s》建立在一条不断升压的主冲突线上，不靠小事件拼接，而是让每个阶段都把主线往更高风险推进。

                        【长线结构】
                        - 前段：明确主角处境、欲望与核心困局
                        - 中段：扩大代价、揭露更深层后果，让局势不可逆转
                        - 后段：多条线汇拢到同一个决定性危机，让主角必须做出无法回头的选择

                        【人物成长】
                        主角不只是“越来越强”，而是被这条路反过来塑形，付出真实代价。

                        【结局方向】
                        适合走“代价换来的解决”型结局，让主冲突真正收束，但不轻飘。
                        """.formatted(projectName).trim()
        ));
        candidates.add(new MasterOutlineCandidate(
                "route_2",
                "人物关系核心版",
                "把全书真正的推进器放在人与人之间，让信任、背叛、依赖和拉扯成为长线骨架。",
                "适合你想把这本书写得更有人味，靠关系变化带动剧情，而不是纯事件链。",
                List.of("关系长线", "信任变化", "情绪兑现"),
                5,
                """
                        【故事核心】
                        《%s》以人物关系变化为真正的主轴，外部事件只是不断施压，让关系本身承担真正的推进。

                        【长线结构】
                        - 前段：建立不稳定但足够抓人的关系网络
                        - 中段：让外部压力持续作用，逼迫关系发生质变
                        - 后段：最终冲突不只是事件解决，而是人与人之间站位的最终确定

                        【参考基底】
                        %s

                        【结局方向】
                        适合走情绪后劲更强的结局，让读者最后记住的是关系完成了怎样的变化。
                        """.formatted(
                                projectName,
                                currentHint.isBlank() ? "如果当前总纲还是空白，就先围绕最不稳定的核心关系起盘。" : currentHint
                        ).trim()
        ));
        candidates.add(new MasterOutlineCandidate(
                "route_3",
                "谜团扩容版",
                "把整本书设计成逐层揭开更大盘面的结构，让故事从局部异常一步步通向更大的世界与规则。",
                "适合你想让这本书越写越开阔，逐步显露隐藏系统、势力和世界规则。",
                List.of("设定层层揭露", "更大舞台", "规则背后的真相"),
                6,
                """
                        【故事核心】
                        《%s》表面写眼前困局，实则一步步揭露更大的世界、规则与隐藏结构，让故事越往后越显出层次。

                        【长线结构】
                        - 前段：先以局部异样或具体危机抓住读者
                        - 中段：把零散异常串成系统性问题，让读者意识到背后另有盘面
                        - 后段：让主角真正踏入更大舞台，面对一个重新定义前文的真相

                        【现有参考】
                        %s

                        【补充要求】
                        %s

                        【结局方向】
                        适合走“第一层问题解决，但更大真相成立”的结局类型。
                        """.formatted(
                                projectName,
                                volumeHint.isBlank() ? "当前还缺少分卷参考，可以先用设定骨架搭起全书层次。" : volumeHint,
                                extraHint.isBlank() ? (recentHint.isBlank() ? "注意不要写成空泛设定说明，始终要落到人物处境。" : recentHint) : extraHint
                        ).trim()
        ));
        return candidates;
    }

    private String buildPreviousVolumeContext(String projectId, String currentVolumeId, String language) {
        List<Volume> volumes = new ArrayList<>(volumeStorage.listVolumes(projectId));
        if (volumes.isEmpty()) {
            return "";
        }
        volumes.sort((left, right) -> compareVolumeIds(left.getId(), right.getId()));
        List<String> blocks = new ArrayList<>();
        for (Volume volume : volumes) {
            String volumeId = canonicalizeVolumeId(volume.getId());
            if (compareVolumeIds(volumeId, currentVolumeId) >= 0) {
                continue;
            }
            String title = firstNonBlank(volume.getTitle(), volumeId);
            String outline = Objects.toString(
                    readOutlineYaml(projectId, "volumes/" + volumeId + ".yaml").getOrDefault("content", ""),
                    ""
            ).trim();
            if (!outline.isBlank()) {
                blocks.add((language.equals("en") ? "Previous volume " : "前序分卷 ")
                        + volumeId + " · " + title + "\n"
                        + clipText(outline, 700));
                continue;
            }
            List<ChapterSummary> summaries = new ArrayList<>(draftStorage.listChapterSummaries(projectId, volumeId));
            summaries.sort((left, right) -> compareChapterIds(left.getChapter(), right.getChapter()));
            if (!summaries.isEmpty()) {
                String summaryBlock = summaries.stream()
                        .map(summary -> {
                            String chapterId = canonicalize(summary.getChapter());
                            String chapterTitle = firstNonBlank(summary.getTitle(), chapterId);
                            String brief = firstNonBlank(summary.getBriefSummary(), "");
                            return chapterId + " " + chapterTitle + (brief.isBlank() ? "" : "：" + brief);
                        })
                        .limit(3)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                if (!summaryBlock.isBlank()) {
                    blocks.add((language.equals("en") ? "Previous volume " : "前序分卷 ")
                            + volumeId + " · " + title + "\n"
                            + clipText(summaryBlock, 500));
                }
            }
            if (blocks.size() >= 2) {
                break;
            }
        }
        return String.join("\n\n", blocks);
    }

    private String buildVolumeRecentSummaryContext(String projectId, String volumeId, String language) {
        List<ChapterSummary> summaries = new ArrayList<>(draftStorage.listChapterSummaries(projectId, volumeId));
        if (summaries.isEmpty()) {
            return "";
        }
        summaries.sort((left, right) -> compareChapterIds(left.getChapter(), right.getChapter()));
        List<ChapterSummary> recent = summaries.size() <= 3
                ? summaries
                : summaries.subList(Math.max(0, summaries.size() - 3), summaries.size());
        StringBuilder sb = new StringBuilder();
        sb.append(language.equals("en") ? "Recent progress in this volume:\n" : "本卷最近进度：\n");
        for (ChapterSummary summary : recent) {
            String chapterId = canonicalize(summary.getChapter());
            String chapterTitle = firstNonBlank(summary.getTitle(), chapterId);
            String brief = firstNonBlank(summary.getBriefSummary(), "");
            sb.append("- ").append(chapterId).append(" ").append(chapterTitle);
            if (!brief.isBlank()) {
                sb.append("：").append(clipText(brief, 100));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String buildVolumeOutlineCandidateSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a professional long-form fiction planning assistant.
                    Your task is to generate several clearly different volume-outline candidates for the selected volume.
                    Each candidate must feel like a real, writable volume plan instead of a vague blurb.

                    Output valid JSON only.
                    """;
        }
        return """
                你是一位擅长连载小说结构设计的策划助手。
                你的任务是：为当前分卷生成几条明显不同、可直接落稿的“卷纲候选”，而不是泛泛摘要。

                只输出合法 JSON，不要解释。
                """;
    }

    private String buildVolumeOutlineCandidateUserPrompt(String volumeId,
                                                         String volumeTitle,
                                                         String masterContent,
                                                         String previousVolumeContext,
                                                         String currentVolumeContent,
                                                         String recentSummaryContext,
                                                         String instruction,
                                                         String language) {
        if ("en".equals(language)) {
            return String.format("""
                    Generate 3 clearly different volume-outline candidates for %s (%s).

                    Available context:
                    %s
                    %s
                    %s
                    %s

                    Requirements:
                    1. Candidate 1/2/3 must differ in emphasis, pacing, and ending shape.
                    2. Each candidate should answer: what this volume is mainly about, what conflict it pushes, what character change it completes, where the midpoint turns, and what hook it ends on.
                    3. The outline_content must be directly usable as a volume outline, around 350-700 words.
                    4. Keep continuity with the existing project context; do not overwrite established facts.
                    5. If current volume content already exists, treat it as editable reference rather than something to repeat mechanically.

                    Return JSON in this format:
                    {
                      "recommended_route_id": "route_1",
                      "candidates": [
                        {
                          "route_id": "route_1",
                          "label": "short route name",
                          "summary": "1-2 sentence positioning",
                          "why_it_fits": "why it fits current project state",
                          "focus_points": ["focus A", "focus B", "focus C"],
                          "chapter_count_hint": 8,
                          "outline_content": "final volume outline text"
                        }
                      ]
                    }
                    """,
                    volumeId,
                    volumeTitle,
                    masterContent.isBlank() ? "Master outline: (empty)" : "Master outline:\n" + clipText(masterContent, 1800),
                    previousVolumeContext.isBlank() ? "Previous volume context: (none)" : previousVolumeContext,
                    currentVolumeContent.isBlank() ? "Current volume outline draft: (empty)" : "Current volume outline draft:\n" + clipText(currentVolumeContent, 1400),
                    recentSummaryContext.isBlank()
                            ? (instruction.isBlank() ? "Extra instruction: (none)" : "Extra instruction:\n" + instruction)
                            : recentSummaryContext + "\n\n" + (instruction.isBlank() ? "Extra instruction: (none)" : "Extra instruction:\n" + instruction)
            );
        }

        return String.format("""
                请为分卷 %s（%s）生成 3 条明显不同的“卷纲候选”。

                可用上下文：
                %s

                %s

                %s

                %s

                要求：
                1. 候选 1/2/3 在“主推进线、节奏、卷尾落点”上必须有明显区别，不能只是同一份卷纲换句话说。
                2. 每条候选都要回答清楚：本卷主要写什么、推进哪组冲突、完成哪些人物变化、中段如何转向、卷尾钩子落在哪。
                3. outline_content 必须是可直接落到编辑器里的正式卷纲，建议 350~700 字。
                4. 要和现有项目上下文连续，不要乱改已确定事实。
                5. 如果当前卷纲已经有内容，把它视为“可重构参考”，不是机械复述材料。

                严格输出 JSON，格式如下：
                {
                  "recommended_route_id": "route_1",
                  "candidates": [
                    {
                      "route_id": "route_1",
                      "label": "路线短名",
                      "summary": "1~2 句定位",
                      "why_it_fits": "为什么适合当前项目进度",
                      "focus_points": ["重点一", "重点二", "重点三"],
                      "chapter_count_hint": 8,
                      "outline_content": "正式卷纲正文"
                    }
                  ]
                }
                """,
                volumeId,
                volumeTitle,
                masterContent.isBlank() ? "总纲：（空）" : "总纲：\n" + clipText(masterContent, 1800),
                previousVolumeContext.isBlank() ? "前序分卷参考：（无）" : previousVolumeContext,
                currentVolumeContent.isBlank() ? "当前卷纲草稿：（空）" : "当前卷纲草稿：\n" + clipText(currentVolumeContent, 1400),
                recentSummaryContext.isBlank()
                        ? (instruction.isBlank() ? "额外要求：（无）" : "额外要求：\n" + instruction)
                        : recentSummaryContext + "\n\n" + (instruction.isBlank() ? "额外要求：（无）" : "额外要求：\n" + instruction)
        );
    }

    private VolumeOutlineCandidateParseResult parseVolumeOutlineCandidates(String volumeId,
                                                                           String volumeTitle,
                                                                           String raw,
                                                                           String masterContent,
                                                                           String currentVolumeContent,
                                                                           String instruction,
                                                                           String language) throws IOException {
        if (raw == null || raw.isBlank()) {
            List<VolumeOutlineCandidate> fallback = fallbackVolumeOutlineCandidates(
                    volumeId, volumeTitle, masterContent, currentVolumeContent, instruction, language);
            return new VolumeOutlineCandidateParseResult(
                    fallback,
                    fallback.isEmpty() ? "" : fallback.get(0).routeId()
            );
        }

        JsonNode root = extractJsonNode(raw);
        JsonNode candidatesNode = root.path("candidates");
        if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
            List<VolumeOutlineCandidate> fallback = fallbackVolumeOutlineCandidates(
                    volumeId, volumeTitle, masterContent, currentVolumeContent, instruction, language);
            return new VolumeOutlineCandidateParseResult(
                    fallback,
                    fallback.isEmpty() ? "" : fallback.get(0).routeId()
            );
        }

        List<VolumeOutlineCandidate> candidates = new ArrayList<>();
        int idx = 1;
        for (JsonNode node : candidatesNode) {
            String routeId = readText(node, "route_id", "routeId", "id");
            if (routeId.isBlank()) {
                routeId = "route_" + idx;
            }
            String label = readText(node, "label", "title", "route_label", "routeLabel");
            if (label.isBlank()) {
                label = ("en".equals(language) ? "Route " : "路线 ") + idx;
            }
            String summary = readText(node, "summary", "positioning", "pitch");
            String whyItFits = readText(node, "why_it_fits", "whyItFits", "reason");
            List<String> focusPoints = sanitizeStringList(readStringList(node, "focus_points", "focusPoints", "tags", "keywords"));
            int chapterCountHint = positiveInt(node, 0, "chapter_count_hint", "chapterCountHint", "chapter_count");
            String outlineContent = readText(node, "outline_content", "outlineContent", "outline", "content");
            if (outlineContent.isBlank()) {
                outlineContent = buildVolumeOutlineFromCandidate(language, volumeTitle, summary, whyItFits, focusPoints, chapterCountHint);
            }
            candidates.add(new VolumeOutlineCandidate(
                    routeId,
                    label,
                    summary,
                    whyItFits,
                    focusPoints,
                    chapterCountHint,
                    outlineContent.trim()
            ));
            idx++;
        }

        String recommendedRouteId = readText(root, "recommended_route_id", "recommendedRouteId", "recommended");
        if (recommendedRouteId.isBlank() && !candidates.isEmpty()) {
            recommendedRouteId = candidates.get(0).routeId();
        }
        return new VolumeOutlineCandidateParseResult(candidates, recommendedRouteId);
    }

    private String buildVolumeOutlineFromCandidate(String language,
                                                   String volumeTitle,
                                                   String summary,
                                                   String whyItFits,
                                                   List<String> focusPoints,
                                                   int chapterCountHint) {
        if ("en".equals(language)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Volume Title: ").append(volumeTitle).append("\n\n");
            if (!summary.isBlank()) {
                sb.append("Positioning:\n").append(summary).append("\n\n");
            }
            if (!whyItFits.isBlank()) {
                sb.append("Why this route fits:\n").append(whyItFits).append("\n\n");
            }
            if (!focusPoints.isEmpty()) {
                sb.append("Focus Points:\n");
                focusPoints.forEach(item -> sb.append("- ").append(item).append("\n"));
                sb.append("\n");
            }
            if (chapterCountHint > 0) {
                sb.append("Suggested chapter count: ").append(chapterCountHint).append("\n");
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【本卷定位】\n").append(firstNonBlank(summary, volumeTitle)).append("\n\n");
        if (!whyItFits.isBlank()) {
            sb.append("【适配原因】\n").append(whyItFits).append("\n\n");
        }
        if (!focusPoints.isEmpty()) {
            sb.append("【本卷重点】\n");
            focusPoints.forEach(item -> sb.append("- ").append(item).append("\n"));
            sb.append("\n");
        }
        if (chapterCountHint > 0) {
            sb.append("【建议章节数】\n约 ").append(chapterCountHint).append(" 章\n");
        }
        return sb.toString().trim();
    }

    private List<VolumeOutlineCandidate> fallbackVolumeOutlineCandidates(String volumeId,
                                                                         String volumeTitle,
                                                                         String masterContent,
                                                                         String currentVolumeContent,
                                                                         String instruction,
                                                                         String language) {
        String masterHint = clipText(cleanOutlineText(masterContent), 120);
        String currentHint = clipText(cleanOutlineText(currentVolumeContent), 120);
        String extraHint = clipText(cleanOutlineText(instruction), 80);

        List<VolumeOutlineCandidate> candidates = new ArrayList<>();
        if ("en".equals(language)) {
            candidates.add(new VolumeOutlineCandidate(
                    "route_1",
                    "Mainline Push",
                    "Keep this volume centered on the main conflict and move the project to the next structural milestone.",
                    "Good when you need this volume to feel decisive and tightly connected to the core premise.",
                    List.of("main conflict", "midpoint escalation", "ending hook"),
                    8,
                    """
                            Volume Positioning:
                            Keep %s focused on the mainline. Use the opening third to restate the immediate pressure, the middle to force a costly turn, and the ending to expose a larger consequence.

                            Core Progress:
                            - Reconnect with the main project direction%s
                            - Move one major conflict from tension into open confrontation
                            - Let the protagonist pay a visible cost before the volume closes

                            Character Movement:
                            - Push at least one key relationship into a new phase
                            - Make the protagonist's choice harder, not easier

                            Volume Ending:
                            End on a hook that naturally opens the next volume rather than neatly wrapping everything up.
                            """.formatted(
                            volumeTitle,
                            masterHint.isBlank() ? "" : " (" + masterHint + ")"
                    ).trim()
            ));
            candidates.add(new VolumeOutlineCandidate(
                    "route_2",
                    "Relationship-Centered",
                    "Use this volume to center emotional tension, loyalty shifts, and the cost of intimacy or alliance.",
                    "Good when the book has strong character chemistry and the next big step should come from human decisions.",
                    List.of("relationship tension", "trust rupture", "emotional payoff"),
                    7,
                    """
                            Volume Positioning:
                            Build %s around relationship evolution. Let plot events keep pressing, but make the real turning points come from trust, betrayal, protection, or confession.

                            Core Progress:
                            - Use external pressure to force closeness or rupture
                            - Reveal hidden motives gradually
                            - Let the volume ending change how the key pair sees each other

                            Reference:
                            %s

                            Volume Ending:
                            Finish with an emotionally sharp hook that changes the direction of the next arc.
                            """.formatted(
                            volumeTitle,
                            currentHint.isBlank() ? "Keep the current trajectory but deepen the relational stakes." : currentHint
                    ).trim()
            ));
            candidates.add(new VolumeOutlineCandidate(
                    "route_3",
                    "World / Mystery Expansion",
                    "Use this volume to widen the setting, expose hidden rules, or introduce a larger stage.",
                    "Good when the story needs fresh scale, new factions, or a stronger sense of hidden structure.",
                    List.of("world expansion", "rule reveal", "larger stage"),
                    9,
                    """
                            Volume Positioning:
                            Make %s the volume where the world opens up. Add new layers of mystery, institutions, or rules, but keep every reveal tied to current danger.

                            Core Progress:
                            - Introduce one larger system or faction
                            - Reframe earlier events with new information
                            - Leave the protagonist facing a bigger board than before

                            Extra note:
                            %s
                            """.formatted(
                            volumeTitle,
                            extraHint.isBlank() ? "Balance exposition with concrete conflict scenes." : extraHint
                    ).trim()
            ));
            return candidates;
        }

        candidates.add(new VolumeOutlineCandidate(
                "route_1",
                "主线推进卷",
                "这一卷集中推进主冲突，把故事从当前阶段推到下一个结构节点。",
                "适合你希望这一卷更扎实、更像真正的主线推进，而不是单纯过渡。",
                List.of("主线冲突升级", "中段反转", "卷尾钩子"),
                8,
                """
                        【本卷定位】
                        这一卷以主线推进为核心，把当前局面真正往前推一格，不做虚晃的过渡卷。

                        【核心推进】
                        - 回扣总纲当前最重要的压力点%s
                        - 把一组潜在冲突推到台面上，形成不可回避的正面对撞
                        - 中段安排一次代价明确的转折，让主角不可能再按原路线走下去

                        【人物变化】
                        - 让至少一组关键关系进入新阶段
                        - 让主角在选择中失去点什么，而不是无痛升级

                        【卷尾钩子】
                        卷尾不要把问题收死，而是抛出更大的后果，让下一卷自然接上。
                        """.formatted(masterHint.isBlank() ? "" : "（参考总纲：" + masterHint + "）").trim()
        ));
        candidates.add(new VolumeOutlineCandidate(
                "route_2",
                "关系拉扯卷",
                "这一卷把重点放在人物关系、信任变化和情感拉扯上，让推进更多来自人和人之间。",
                "适合你想让这一卷更有人味、更强调角色之间的拉力与反作用。",
                List.of("关系推进", "信任裂缝", "情绪兑现"),
                7,
                """
                        【本卷定位】
                        这一卷以关系变化为主，把外部事件当成压力，让人物之间的靠近、试探、误解或背离成为真正的推进器。

                        【核心推进】
                        - 外部局势继续压迫，但关键节点由人物选择触发
                        - 逐步揭开隐藏动机，不一次说尽
                        - 让卷尾真正改写一组核心关系的站位

                        【参考基底】
                        %s

                        【卷尾钩子】
                        卷尾落在情绪最锋利的位置，让下一卷一开始就带着后劲。
                        """.formatted(currentHint.isBlank() ? "如果当前卷纲还是空白，就先围绕最关键的人物关系建立本卷矛盾。" : currentHint).trim()
        ));
        candidates.add(new VolumeOutlineCandidate(
                "route_3",
                "世界扩容卷",
                "这一卷重点做设定扩容、规则揭露或势力上场，让故事舞台明显变大。",
                "适合你觉得眼下剧情需要换空气、换维度，或者把隐藏的大盘先掀开一角。",
                List.of("设定扩容", "规则揭露", "更大舞台"),
                9,
                """
                        【本卷定位】
                        这一卷负责把舞台拉开，不只是把当前矛盾继续写下去，而是让读者意识到故事背后还有更大的盘面。

                        【核心推进】
                        - 引入新的规则、势力或舞台层级
                        - 用新信息反照前文，让旧事件显出另一层意思
                        - 保证每次设定展开都伴随具体冲突，而不是空讲背景

                        【补充要求】
                        %s

                        【卷尾钩子】
                        卷尾让主角正式站到一个更大的局里，为后续卷做空间升级。
                        """.formatted(extraHint.isBlank() ? "注意不要写成纯设定说明，要让设定与人物处境绑定。" : extraHint).trim()
        ));
        return candidates;
    }

    private int compareVolumeIds(String left, String right) {
        String a = canonicalizeVolumeId(left);
        String b = canonicalizeVolumeId(right);
        Matcher am = VOLUME_ID_PATTERN.matcher(a);
        Matcher bm = VOLUME_ID_PATTERN.matcher(b);
        if (am.matches() && bm.matches()) {
            return Integer.compare(Integer.parseInt(am.group(1)), Integer.parseInt(bm.group(1)));
        }
        return a.compareToIgnoreCase(b);
    }

    private String clipText(String text, int maxChars) {
        String normalized = cleanOutlineText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars)).trim() + "…";
    }

    /**
     * 读取大纲 YAML 文件，返回 {content, updated_at}
     */
    private Map<String, Object> readOutlineYaml(String projectId, String subPath) {
        Path path = outlineDir(projectId).resolve(subPath);
        if (Files.exists(path)) {
            try {
                Map<String, Object> data = draftStorage.readYamlAsMap(path);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("content", data.getOrDefault("content", "").toString());
                Object updatedAt = data.get("updated_at");
                result.put("updated_at", updatedAt != null ? updatedAt.toString() : null);
                return result;
            } catch (IOException e) {
                log.error("读取大纲失败: {}", path, e);
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("content", "");
        empty.put("updated_at", null);
        return empty;
    }

    /**
     * 写入大纲 YAML 文件
     */
    private Map<String, Object> writeOutlineYaml(String projectId, String subPath, String content) throws IOException {
        Path path = outlineDir(projectId).resolve(subPath);

        if (content == null || content.isBlank()) {
            // 空内容 = 删除
            Files.deleteIfExists(path);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", "");
            result.put("updated_at", null);
            return result;
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("updated_at", now);

        draftStorage.writeYaml(path, payload);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("updated_at", now);
        return result;
    }

    private String canonicalize(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) return "";
        String upper = chapterId.trim().toUpperCase();
        Matcher m = CHAPTER_ID_PATTERN.matcher(upper);
        if (m.matches()) {
            return "V" + Integer.parseInt(m.group(1)) + "C" + Integer.parseInt(m.group(2));
        }
        return chapterId.trim();
    }

    private String canonicalizeVolumeId(String volumeId) {
        if (volumeId == null || volumeId.isBlank()) return "V1";
        Matcher matcher = VOLUME_ID_PATTERN.matcher(volumeId.trim().toUpperCase(Locale.ROOT));
        if (matcher.matches()) {
            return "V" + Integer.parseInt(matcher.group(1));
        }
        return volumeId.trim().toUpperCase(Locale.ROOT);
    }

    private String extractVolumeId(String chapterId) {
        Matcher m = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (m.matches()) return "V" + m.group(1);
        return "V1";
    }

    private List<String> listOutlineChapterIds(String projectId) {
        Path chapterDir = outlineDir(projectId).resolve("chapters");
        if (!Files.isDirectory(chapterDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(chapterDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.replaceFirst("\\.ya?ml$", ""))
                    .map(this::canonicalize)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (IOException e) {
            log.warn("列出章节大纲失败: project={}, reason={}", projectId, e.getMessage());
            return List.of();
        }
    }

    private int compareChapterIds(String left, String right) {
        String a = canonicalize(left);
        String b = canonicalize(right);
        Matcher am = CHAPTER_ID_PATTERN.matcher(a);
        Matcher bm = CHAPTER_ID_PATTERN.matcher(b);
        if (am.matches() && bm.matches()) {
            int av = Integer.parseInt(am.group(1));
            int bv = Integer.parseInt(bm.group(1));
            if (av != bv) {
                return Integer.compare(av, bv);
            }
            int ac = Integer.parseInt(am.group(2));
            int bc = Integer.parseInt(bm.group(2));
            if (ac != bc) {
                return Integer.compare(ac, bc);
            }
        }
        return a.compareToIgnoreCase(b);
    }

    private ExplorationSession requireExplorationSession(String projectId, String sessionId) {
        ExplorationSession session = explorationSessions.get(sessionId);
        if (session == null || !Objects.equals(session.projectId, projectId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "exploration session not found");
        }
        return session;
    }

    private void cleanupExpiredExplorationSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(6);
        explorationSessions.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().updatedAt.isBefore(cutoff));
    }

    private String normalizeExplorationScope(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.contains("2") || value.contains("3") || value.contains("multi")) {
            return "next_multi";
        }
        return "next_chapter";
    }

    private ExplorationSession buildExplorationSession(String projectId,
                                                       String language,
                                                       String scope,
                                                       String requestedVolumeId,
                                                       String requestedChapterId) {
        String volumeId = resolveExplorationVolumeId(projectId, requestedVolumeId, requestedChapterId);
        String volumeTitle = volumeStorage.listVolumes(projectId).stream()
                .filter(item -> volumeId.equalsIgnoreCase(item.getId()))
                .map(Volume::getTitle)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .findFirst()
                .orElse(volumeId);

        String anchorChapterId = resolveExplorationAnchorChapterId(projectId, volumeId, requestedChapterId);
        int anchorChapterNo = resolveExplorationAnchorChapterNo(projectId, volumeId, anchorChapterId);
        List<String> targetChapterIds = buildExplorationTargetChapterIds(volumeId, anchorChapterNo, scope);

        String masterContent = Objects.toString(readOutlineYaml(projectId, "master.yaml").getOrDefault("content", ""), "").trim();
        String volumeContent = Objects.toString(readOutlineYaml(projectId, "volumes/" + volumeId + ".yaml").getOrDefault("content", ""), "").trim();
        String anchorOutline = anchorChapterId.isBlank()
                ? ""
                : Objects.toString(readOutlineYaml(projectId, "chapters/" + anchorChapterId + ".yaml").getOrDefault("content", ""), "").trim();
        String anchorDraft = anchorChapterId.isBlank()
                ? ""
                : draftStorage.getFinalDraft(projectId, anchorChapterId).orElse("").trim();
        List<ChapterSummary> recentSummaries = resolveExplorationRecentSummaries(projectId, volumeId, anchorChapterId);
        String anchorTitle = resolveExplorationAnchorTitle(projectId, anchorChapterId);
        String anchorSummary = resolveExplorationAnchorSummary(projectId, anchorChapterId, recentSummaries, anchorOutline, anchorDraft);

        ExplorationSession session = new ExplorationSession();
        session.sessionId = UUID.randomUUID().toString();
        session.projectId = projectId;
        session.language = language;
        session.scope = scope;
        session.volumeId = volumeId;
        session.volumeTitle = volumeTitle;
        session.anchorChapterId = anchorChapterId;
        session.anchorChapterTitle = anchorTitle;
        session.anchorSummary = anchorSummary;
        session.targetChapterIds = targetChapterIds;
        session.masterContent = masterContent;
        session.volumeContent = volumeContent;
        session.anchorOutline = anchorOutline;
        session.anchorDraft = anchorDraft;
        session.recentSummaries = recentSummaries;
        session.questions = buildExplorationQuestions(language, scope);
        session.decisions = List.of();
        session.routes = List.of();
        session.generatedPlan = null;
        session.selectedRouteId = "";
        session.stage = "questions";
        session.touch();
        return session;
    }

    private String resolveExplorationVolumeId(String projectId, String requestedVolumeId, String requestedChapterId) {
        String chapterId = canonicalize(requestedChapterId);
        if (!chapterId.isBlank()) {
            return extractVolumeId(chapterId);
        }
        String volumeId = canonicalizeVolumeId(requestedVolumeId);
        if (!volumeId.isBlank() && !"V1".equals(volumeId)) {
            return volumeId;
        }
        List<Volume> volumes = volumeStorage.listVolumes(projectId);
        if (!volumes.isEmpty()) {
            return canonicalizeVolumeId(volumes.get(0).getId());
        }
        return volumeId == null || volumeId.isBlank() ? "V1" : volumeId;
    }

    private String resolveExplorationAnchorChapterId(String projectId, String volumeId, String requestedChapterId) {
        String chapterId = canonicalize(requestedChapterId);
        if (!chapterId.isBlank()) {
            return chapterId;
        }

        List<String> candidates = new ArrayList<>();
        draftStorage.listChapters(projectId).stream()
                .map(this::canonicalize)
                .filter(item -> volumeId.equalsIgnoreCase(extractVolumeId(item)))
                .forEach(candidates::add);
        draftStorage.listChapterSummaries(projectId, volumeId).stream()
                .map(ChapterSummary::getChapter)
                .map(this::canonicalize)
                .filter(item -> !item.isBlank())
                .forEach(candidates::add);
        listOutlineChapterIds(projectId).stream()
                .filter(item -> volumeId.equalsIgnoreCase(extractVolumeId(item)))
                .forEach(candidates::add);

        return candidates.stream()
                .filter(item -> !item.isBlank())
                .distinct()
                .sorted(this::compareChapterIds)
                .reduce((first, second) -> second)
                .orElse("");
    }

    private int resolveExplorationAnchorChapterNo(String projectId, String volumeId, String anchorChapterId) {
        String chapterId = canonicalize(anchorChapterId);
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(2));
        }

        String fallbackAnchor = resolveExplorationAnchorChapterId(projectId, volumeId, "");
        Matcher fallbackMatcher = CHAPTER_ID_PATTERN.matcher(fallbackAnchor);
        if (fallbackMatcher.matches()) {
            return Integer.parseInt(fallbackMatcher.group(2));
        }
        return 0;
    }

    private List<String> buildExplorationTargetChapterIds(String volumeId, int anchorChapterNo, String scope) {
        int desiredCount = "next_multi".equals(scope) ? 3 : 1;
        int start = Math.max(0, anchorChapterNo);
        List<String> result = new ArrayList<>();
        for (int index = 1; index <= desiredCount; index++) {
            result.add(volumeId + "C" + (start + index));
        }
        return result;
    }

    private List<ChapterSummary> resolveExplorationRecentSummaries(String projectId, String volumeId, String anchorChapterId) {
        List<ChapterSummary> summaries = new ArrayList<>(draftStorage.listChapterSummaries(projectId, volumeId));
        summaries.sort((left, right) -> compareChapterIds(left.getChapter(), right.getChapter()));
        if (summaries.isEmpty()) {
            return List.of();
        }

        if (anchorChapterId == null || anchorChapterId.isBlank()) {
            return summaries.size() <= 2 ? summaries : summaries.subList(Math.max(0, summaries.size() - 2), summaries.size());
        }

        List<ChapterSummary> upToAnchor = new ArrayList<>();
        for (ChapterSummary summary : summaries) {
            if (compareChapterIds(summary.getChapter(), anchorChapterId) <= 0) {
                upToAnchor.add(summary);
            }
        }
        if (upToAnchor.isEmpty()) {
            return summaries.size() <= 2 ? summaries : summaries.subList(Math.max(0, summaries.size() - 2), summaries.size());
        }
        return upToAnchor.size() <= 2 ? upToAnchor : upToAnchor.subList(Math.max(0, upToAnchor.size() - 2), upToAnchor.size());
    }

    private String resolveExplorationAnchorTitle(String projectId, String anchorChapterId) {
        if (anchorChapterId == null || anchorChapterId.isBlank()) {
            return "";
        }
        return draftStorage.getChapterSummary(projectId, anchorChapterId)
                .map(summary -> firstNonBlank(summary.getTitle(), summary.getBriefSummary()))
                .orElse("");
    }

    private String resolveExplorationAnchorSummary(String projectId,
                                                   String anchorChapterId,
                                                   List<ChapterSummary> recentSummaries,
                                                   String anchorOutline,
                                                   String anchorDraft) {
        if (anchorChapterId != null && !anchorChapterId.isBlank()) {
            Optional<ChapterSummary> summary = draftStorage.getChapterSummary(projectId, anchorChapterId);
            if (summary.isPresent()) {
                ChapterSummary item = summary.get();
                return firstNonBlank(item.getBriefSummary(), joinTopItems(item.getKeyEvents(), 3), joinTopItems(item.getOpenLoops(), 3));
            }
        }
        if (recentSummaries != null && !recentSummaries.isEmpty()) {
            ChapterSummary last = recentSummaries.get(recentSummaries.size() - 1);
            return firstNonBlank(last.getBriefSummary(), joinTopItems(last.getKeyEvents(), 3), joinTopItems(last.getOpenLoops(), 3));
        }
        if (anchorOutline != null && !anchorOutline.isBlank()) {
            return trimForPrompt(cleanOutlineText(anchorOutline), 180);
        }
        if (anchorDraft != null && !anchorDraft.isBlank()) {
            return trimForPrompt(cleanOutlineText(anchorDraft), 180);
        }
        return "";
    }

    private List<ExplorationQuestion> buildExplorationQuestions(String language, String scope) {
        if ("en".equals(language)) {
            List<ExplorationQuestion> questions = new ArrayList<>();
            questions.add(new ExplorationQuestion(
                    "continuation_goal",
                    "What should the continuation prioritize first?",
                    "Pick the main direction before generating route options.",
                    true,
                    List.of(
                            new ExplorationQuestionOption("mainline", "Push the main plot", "Move the central problem forward in a visible way."),
                            new ExplorationQuestionOption("relationship", "Shift relationships", "Force key characters into a new emotional or strategic state."),
                            new ExplorationQuestionOption("reveal", "Reveal information", "Expose a secret, misunderstanding, or hidden layer."),
                            new ExplorationQuestionOption("pressure", "Add pressure", "Make the situation harder and the choices narrower.")
                    )
            ));
            questions.add(new ExplorationQuestion(
                    "opening_force",
                    "What kind of opening energy do you want?",
                    "This affects the first scene feel of the continuation.",
                    true,
                    List.of(
                            new ExplorationQuestionOption("conflict", "Immediate conflict", "Open with confrontation, refusal, or direct friction."),
                            new ExplorationQuestionOption("abnormal", "Anomaly first", "Open with a strange event, bad news, or disruption."),
                            new ExplorationQuestionOption("quiet", "Quiet pressure", "Open with stillness, details, or subtle dislocation."),
                            new ExplorationQuestionOption("buffer", "Breathing room", "Give the characters a short calm stretch before turning the screw.")
                    )
            ));
            questions.add(new ExplorationQuestion(
                    "next_multi".equals(scope) ? "arc_pacing" : "ending_shape",
                    "next_multi".equals(scope) ? "How should the next 2-3 chapters pace out?" : "How should the ending of the next chapter land?",
                    "next_multi".equals(scope)
                            ? "Choose the rhythm you want across the small chapter pack."
                            : "Choose the type of ending hook the next chapter should leave.",
                    true,
                    "next_multi".equals(scope)
                            ? List.of(
                            new ExplorationQuestionOption("steady", "Steady climb", "Advance in measured steps and keep tightening."),
                            new ExplorationQuestionOption("fast_escalation", "Fast escalation", "Raise the stakes quickly within 2-3 chapters."),
                            new ExplorationQuestionOption("collect_then_burst", "Collect then burst", "Spend one chapter gathering pressure, then trigger a sharp turn."),
                            new ExplorationQuestionOption("mistake_chain", "Mistake chain", "Let one bad choice drag the next two chapters forward.")
                    )
                            : List.of(
                            new ExplorationQuestionOption("hook", "Leave a hook", "End on a discovery, invitation, or unresolved opening."),
                            new ExplorationQuestionOption("small_win", "Small win", "Give the characters a short-term gain with future cost."),
                            new ExplorationQuestionOption("backfire", "Immediate backfire", "Let the chapter end with the plan turning against them."),
                            new ExplorationQuestionOption("misjudge", "Wrong judgment", "End with a confident but mistaken conclusion.")
                    )
            ));
            return questions;
        }

        List<ExplorationQuestion> questions = new ArrayList<>();
        questions.add(new ExplorationQuestion(
                "continuation_goal",
                "这一段续写最该优先推进什么？",
                "先把主要推进方向定下来，后面路线才不会发散。",
                true,
                List.of(
                        new ExplorationQuestionOption("mainline", "主线推进", "让核心问题或当前任务明显往前走一步。"),
                        new ExplorationQuestionOption("relationship", "关系变化", "把关键人物关系推到新的情绪或利益状态。"),
                        new ExplorationQuestionOption("reveal", "信息揭示", "揭开误会、秘密，或者掀出新的信息层。"),
                        new ExplorationQuestionOption("pressure", "危机加压", "让局势更紧，让人物更难选。")
                )
        ));
        questions.add(new ExplorationQuestion(
                "opening_force",
                "你更想让续写从什么手感切进去？",
                "这会决定下一段开场是先撞上去，还是先蓄压。",
                true,
                List.of(
                        new ExplorationQuestionOption("conflict", "上来就冲突", "开场就有对峙、拒绝、碰撞。"),
                        new ExplorationQuestionOption("abnormal", "先抛异常", "先给一个反常事件、消息或突发状况。"),
                        new ExplorationQuestionOption("quiet", "先压气氛", "先用安静场面、细节或错位感铺开。"),
                        new ExplorationQuestionOption("buffer", "先给缓冲", "先给人物一点喘息，再把问题拧回来。")
                )
        ));
        questions.add(new ExplorationQuestion(
                "next_multi".equals(scope) ? "arc_pacing" : "ending_shape",
                "next_multi".equals(scope) ? "接下来 2~3 章更偏哪种推进节奏？" : "你希望下一章结尾怎么落？",
                "next_multi".equals(scope)
                        ? "决定小章节包是稳推、快推，还是先压后炸。"
                        : "决定下一章最后给读者留下什么感觉。",
                true,
                "next_multi".equals(scope)
                        ? List.of(
                        new ExplorationQuestionOption("steady", "稳步抬升", "每章推进一点，但压力持续变重。"),
                        new ExplorationQuestionOption("fast_escalation", "快速抬压", "2~3章内迅速抬高事态级别。"),
                        new ExplorationQuestionOption("collect_then_burst", "先收后炸", "先收信息、收情绪，再来一次硬转折。"),
                        new ExplorationQuestionOption("mistake_chain", "误判连锁", "让一次错误选择把后面两章都拖进去。")
                )
                        : List.of(
                        new ExplorationQuestionOption("hook", "留钩子", "以发现、邀请、悬念或新的入口收尾。"),
                        new ExplorationQuestionOption("small_win", "小胜一下", "给角色一个短期成果，但暗埋代价。"),
                        new ExplorationQuestionOption("backfire", "当场反噬", "让计划或试探在结尾反过来咬人。"),
                        new ExplorationQuestionOption("misjudge", "判断出错", "让角色带着自信但错误的判断离场。")
                )
        ));
        return questions;
    }

    private Map<String, Object> toExplorationSessionPayload(ExplorationSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.sessionId);
        payload.put("stage", session.stage);
        payload.put("scope", session.scope);
        payload.put("volume_id", session.volumeId);
        payload.put("volume_title", session.volumeTitle);
        payload.put("anchor_chapter_id", session.anchorChapterId);
        payload.put("anchor_chapter_title", session.anchorChapterTitle);
        payload.put("anchor_summary", session.anchorSummary);
        payload.put("target_chapter_ids", session.targetChapterIds);
        payload.put("questions", session.questions.stream().map(ExplorationQuestion::toMap).toList());
        payload.put("decisions", session.decisions.stream().map(ExplorationDecision::toMap).toList());
        payload.put("route_candidates", session.routes.stream().map(ExplorationRoute::toMap).toList());
        payload.put("selected_route_id", session.selectedRouteId);
        payload.put("generated_plan", session.generatedPlan);
        payload.put("updated_at", session.updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return payload;
    }

    private List<ExplorationDecision> parseExplorationDecisions(Object rawAnswers,
                                                                List<ExplorationQuestion> questions) {
        JsonNode answersNode = objectMapper.valueToTree(rawAnswers);
        if (!answersNode.isArray()) {
            return List.of();
        }

        Map<String, JsonNode> answerMap = new LinkedHashMap<>();
        for (JsonNode answerNode : answersNode) {
            String questionId = readText(answerNode, "question_id", "questionId", "id");
            if (!questionId.isBlank()) {
                answerMap.put(questionId, answerNode);
            }
        }

        List<ExplorationDecision> result = new ArrayList<>();
        for (ExplorationQuestion question : questions) {
            JsonNode answerNode = answerMap.get(question.id);
            if (answerNode == null || answerNode.isNull()) {
                continue;
            }
            List<String> optionIds = readStringList(answerNode, "option_ids", "optionIds", "selected_option_ids", "selectedOptionIds");
            String customNote = readText(answerNode, "custom_note", "customNote", "note");

            Map<String, ExplorationQuestionOption> optionMap = new LinkedHashMap<>();
            for (ExplorationQuestionOption option : question.options) {
                optionMap.put(option.id, option);
            }

            List<String> normalizedOptionIds = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            for (String optionId : optionIds) {
                String normalized = optionId == null ? "" : optionId.trim();
                if (normalized.isBlank()) continue;
                ExplorationQuestionOption option = optionMap.get(normalized);
                if (option != null) {
                    normalizedOptionIds.add(option.id);
                    summaries.add(option.label + "：" + option.description);
                }
            }
            if (normalizedOptionIds.isEmpty() && customNote.isBlank()) {
                continue;
            }

            String answerSummary = summaries.isEmpty() ? customNote.trim() : String.join("；", summaries);
            if (!customNote.isBlank()) {
                answerSummary = answerSummary.isBlank()
                        ? "补充偏好：" + customNote.trim()
                        : answerSummary + "｜补充偏好：" + customNote.trim();
            }
            result.add(new ExplorationDecision(
                    question.id,
                    question.title,
                    normalizedOptionIds,
                    answerSummary,
                    customNote.trim()
            ));
        }
        return result;
    }

    private List<ExplorationRoute> requestExplorationRoutes(ExplorationSession session) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", buildExplorationRoutesSystemPrompt(session.language)),
                    Map.of("role", "user", "content", buildExplorationRoutesUserPrompt(session))
            );
            String providerId = llmGateway.getProviderForAgent("writer");
            String raw = llmGateway.chat(messages, providerId, 0.45, 2600).getContent().trim();
            List<ExplorationRoute> routes = parseExplorationRoutes(raw, session);
            if (!routes.isEmpty()) {
                return routes;
            }
        } catch (Exception e) {
            log.warn("续写探索路线生成失败，使用回退路线: project={}, session={}, reason={}",
                    session.projectId, session.sessionId, e.getMessage());
        }
        return fallbackExplorationRoutes(session);
    }

    private List<ExplorationRoute> parseExplorationRoutes(String raw, ExplorationSession session) throws IOException {
        JsonNode root = extractJsonNode(raw);
        JsonNode routesNode = root.path("routes");
        if (!routesNode.isArray() || routesNode.isEmpty()) {
            return List.of();
        }

        List<ExplorationRoute> routes = new ArrayList<>();
        int fallbackIndex = 0;
        for (JsonNode routeNode : routesNode) {
            fallbackIndex++;
            String routeId = readText(routeNode, "route_id", "routeId", "id");
            if (routeId.isBlank()) {
                routeId = "route_" + (char) ('a' + fallbackIndex - 1);
            }
            String label = readText(routeNode, "label", "title", "name");
            String summary = readText(routeNode, "summary", "pitch", "core");
            String whyItFits = readText(routeNode, "why_it_fits", "whyItFits", "rationale");
            List<String> risks = readStringList(routeNode, "risks", "risk_notes", "riskNotes");
            List<String> beats = readStringList(routeNode, "beats", "key_beats", "keyBeats");
            List<ExplorationChapterTarget> chapterTargets = parseExplorationChapterTargets(routeNode.get("chapter_targets"), session);
            if (chapterTargets.isEmpty()) {
                chapterTargets = parseExplorationChapterTargets(routeNode.get("chapters"), session);
            }

            if (label.isBlank()) {
                label = "路线 " + fallbackIndex;
            }
            if (summary.isBlank()) {
                summary = firstNonBlank(whyItFits, joinTopItems(beats, 2), label);
            }
            if (chapterTargets.isEmpty()) {
                chapterTargets = fallbackRouteChapterTargets(session, fallbackIndex, summary, beats);
            }

            routes.add(new ExplorationRoute(
                    routeId,
                    label,
                    summary,
                    whyItFits,
                    sanitizeStringList(beats),
                    sanitizeStringList(risks),
                    chapterTargets
            ));
            if (routes.size() >= 3) {
                break;
            }
        }
        return routes;
    }

    private List<ExplorationChapterTarget> parseExplorationChapterTargets(JsonNode chaptersNode,
                                                                          ExplorationSession session) {
        if (chaptersNode == null || !chaptersNode.isArray() || chaptersNode.isEmpty()) {
            return List.of();
        }
        int maxCount = "next_multi".equals(session.scope) ? 3 : 1;
        List<ExplorationChapterTarget> targets = new ArrayList<>();
        for (int index = 0; index < chaptersNode.size() && targets.size() < maxCount; index++) {
            JsonNode chapterNode = chaptersNode.get(index);
            String chapterId = readText(chapterNode, "chapter_id", "chapterId", "id");
            if (chapterId.isBlank()) {
                chapterId = session.targetChapterIds.get(Math.min(index, session.targetChapterIds.size() - 1));
            }
            String title = readText(chapterNode, "title", "chapter_title", "chapterTitle");
            String summary = readText(chapterNode, "summary", "brief_summary", "briefSummary");
            String hook = readText(chapterNode, "hook", "ending_hook", "endingHook");
            targets.add(new ExplorationChapterTarget(
                    canonicalize(chapterId),
                    title.isBlank() ? defaultChapterTitle(session.language, extractChapterNo(chapterId, index + 1)) : title,
                    firstNonBlank(summary, hook, title),
                    firstNonBlank(hook, summary)
            ));
        }
        return targets;
    }

    private List<ExplorationRoute> fallbackExplorationRoutes(ExplorationSession session) {
        List<String> targetIds = session.targetChapterIds;
        List<String> decisionIds = session.decisions.stream()
                .flatMap(decision -> decision.selectedOptionIds.stream())
                .toList();

        String firstRouteLabel = decisionIds.contains("mainline")
                ? ("en".equals(session.language) ? "Route A: Push the core thread" : "路线 A：主线直推")
                : ("en".equals(session.language) ? "Route A: Put the cast on the board" : "路线 A：先把人推上牌桌");
        String secondRouteLabel = "en".equals(session.language)
                ? "Route B: Use relationship strain as the lever"
                : "路线 B：用关系摩擦带动推进";
        String thirdRouteLabel = "en".equals(session.language)
                ? "Route C: Throw in an anomaly first"
                : "路线 C：先抛异常再逼选择";

        return List.of(
                new ExplorationRoute(
                        "route_a",
                        firstRouteLabel,
                        "en".equals(session.language)
                                ? "Advance the current problem directly and make the next turn inevitable."
                                : "直接推进当前问题，让下一步选择变成不得不做的事。",
                        "en".equals(session.language)
                                ? "Best when you want momentum and clear consequence."
                                : "适合你想要更强推进感、让情节立刻往前挪的时候。",
                        List.of(
                                "en".equals(session.language) ? "Open from the unresolved pressure left by the previous chapter." : "从上一章遗留压力切入。",
                                "en".equals(session.language) ? "Force the protagonist to make an immediate choice." : "逼主角做一个马上要负责的决定。",
                                "en".equals(session.language) ? "End with a turn that locks in the next move." : "以一个锁死后续动作的转折收尾。"
                        ),
                        List.of(
                                "en".equals(session.language) ? "May feel too fast if you still need emotional settling." : "如果人物情绪还没沉下去，可能会显得太快。"
                        ),
                        fallbackRouteChapterTargets(session, 1, "", List.of())
                ),
                new ExplorationRoute(
                        "route_b",
                        secondRouteLabel,
                        "en".equals(session.language)
                                ? "Let character friction carry the continuation, then fold the plot in."
                                : "先让人物关系磨起来，再把主线问题折进来。",
                        "en".equals(session.language)
                                ? "Fits when the current material already has emotional tension to cash in."
                                : "适合当前已经积出人物张力，想借情绪推进剧情的时候。",
                        List.of(
                                "en".equals(session.language) ? "Use a dialogue or standoff as the first lever." : "先用一次对话或对峙把关系撬开。",
                                "en".equals(session.language) ? "Expose a mismatch in trust, desire, or information." : "掀出信任、欲望或信息的不对称。",
                                "en".equals(session.language) ? "Turn the emotional crack into a practical consequence." : "把情绪裂缝转成现实后果。"
                        ),
                        List.of(
                                "en".equals(session.language) ? "Needs strong scene chemistry, otherwise it may feel soft." : "如果人物戏不够强，这条会显得偏软。"
                        ),
                        fallbackRouteChapterTargets(session, 2, "", List.of())
                ),
                new ExplorationRoute(
                        "route_c",
                        thirdRouteLabel,
                        "en".equals(session.language)
                                ? "Start from an abnormal event, then reveal why it matters."
                                : "先抛一个不对劲的事件，再慢慢显出它为什么麻烦。",
                        "en".equals(session.language)
                                ? "Useful when you want freshness without losing current continuity."
                                : "适合想要新鲜入口，但又不想断开当前上下文的时候。",
                        List.of(
                                "en".equals(session.language) ? "Open on a strange sign, message, or interruption." : "开场给一个反常信号、消息或打断。",
                                "en".equals(session.language) ? "Make the cast misread it once." : "让人物先误判一次。",
                                "en".equals(session.language) ? "Let the truth surface as the ending hook." : "把真正的问题留到结尾再翻出来。"
                        ),
                        List.of(
                                "en".equals(session.language) ? "If the anomaly is too detached, it may look gimmicky." : "如果异常和主线扣得不够紧，容易显得像技巧。"
                        ),
                        fallbackRouteChapterTargets(session, 3, "", List.of())
                )
        );
    }

    private List<ExplorationChapterTarget> fallbackRouteChapterTargets(ExplorationSession session,
                                                                       int routeIndex,
                                                                       String summary,
                                                                       List<String> beats) {
        int desiredCount = "next_multi".equals(session.scope) ? 2 : 1;
        List<ExplorationChapterTarget> targets = new ArrayList<>();
        for (int index = 0; index < Math.min(desiredCount, session.targetChapterIds.size()); index++) {
            String chapterId = session.targetChapterIds.get(index);
            int chapterNo = extractChapterNo(chapterId, index + 1);
            String title;
            if ("en".equals(session.language)) {
                title = switch (routeIndex) {
                    case 2 -> index == 0 ? "The Strain Shows" : "The Cost of Speaking";
                    case 3 -> index == 0 ? "A Wrong Sign" : "The Sign Turns Real";
                    default -> index == 0 ? "The Next Move" : "The Price of Moving";
                };
            } else {
                title = switch (routeIndex) {
                    case 2 -> index == 0 ? "裂缝露出来" : "说破后的代价";
                    case 3 -> index == 0 ? "不对劲的开端" : "异常坐实";
                    default -> index == 0 ? "下一步" : "推进后的代价";
                };
            }
            String targetSummary = !summary.isBlank()
                    ? summary
                    : firstNonBlank(joinTopItems(beats, 2), defaultChapterTitle(session.language, chapterNo));
            String hook = "en".equals(session.language)
                    ? "Leave a consequence that forces the following chapter."
                    : "留下一个会逼出下一章动作的后果。";
            targets.add(new ExplorationChapterTarget(chapterId, title, targetSummary, hook));
        }
        return targets;
    }

    private List<PlannedChapter> requestExplorationPlan(ExplorationSession session,
                                                        ExplorationRoute route) throws IOException {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", buildExplorationPlanSystemPrompt(session.language)),
                    Map.of("role", "user", "content", buildExplorationPlanUserPrompt(session, route))
            );
            String providerId = llmGateway.getProviderForAgent("writer");
            String raw = llmGateway.chat(messages, providerId, 0.4, 3200).getContent().trim();
            JsonNode root = extractJsonNode(raw);
            List<PlannedChapter> chapters = parseChaptersFromRequest(session.volumeId, root.path("chapters"), session.language);
            if (!chapters.isEmpty()) {
                return chapters;
            }
        } catch (Exception e) {
            log.warn("续写探索章节规划生成失败，使用回退规划: project={}, session={}, route={}, reason={}",
                    session.projectId, session.sessionId, route.routeId, e.getMessage());
        }
        return fallbackExplorationPlan(session, route);
    }

    private List<PlannedChapter> fallbackExplorationPlan(ExplorationSession session,
                                                         ExplorationRoute route) {
        List<PlannedChapter> chapters = new ArrayList<>();
        List<ExplorationChapterTarget> targets = route.chapterTargets.isEmpty()
                ? fallbackRouteChapterTargets(session, 1, route.summary, route.beats)
                : route.chapterTargets;
        int maxCount = "next_multi".equals(session.scope) ? Math.min(3, targets.size()) : 1;
        List<String> routeBeats = route.beats.isEmpty()
                ? List.of("en".equals(session.language) ? "Push the continuation with a concrete choice." : "用一个具体选择把续写往前推。")
                : route.beats;

        for (int index = 0; index < maxCount; index++) {
            ExplorationChapterTarget target = targets.get(index);
            int chapterNo = extractChapterNo(target.chapterId, index + 1);
            List<String> chapterBeats = new ArrayList<>();
            chapterBeats.add(routeBeats.get(Math.min(index, routeBeats.size() - 1)));
            if (routeBeats.size() > index + 1) {
                chapterBeats.add(routeBeats.get(index + 1));
            }
            chapterBeats.add("en".equals(session.language)
                    ? "Land the chapter on a consequence, not a summary."
                    : "章节结尾落在后果上，不落在总结上。");
            List<String> mustNotInclude = session.decisions.isEmpty()
                    ? List.of()
                    : List.of("en".equals(session.language)
                    ? "Do not skip the gradual buildup required by the chosen route."
                    : "不要跳过当前路线需要的铺垫过程。");
            chapters.add(normalizePlannedChapter(
                    session.volumeId,
                    chapterNo,
                    target.chapterId,
                    firstNonBlank(target.title, defaultChapterTitle(session.language, chapterNo)),
                    firstNonBlank(target.summary, route.summary, route.label),
                    "en".equals(session.language)
                            ? "Advance the chosen route while preserving continuity."
                            : "在保持连续性的前提下，把选中的路线向前推进。",
                    chapterBeats,
                    firstNonBlank(target.hook, route.summary, route.label),
                    mustNotInclude,
                    buildExplorationFallbackOutline(session, route, target, chapterBeats, mustNotInclude),
                    session.language
            ));
        }
        return chapters;
    }

    private String buildExplorationFallbackOutline(ExplorationSession session,
                                                   ExplorationRoute route,
                                                   ExplorationChapterTarget target,
                                                   List<String> chapterBeats,
                                                   List<String> mustNotInclude) {
        if ("en".equals(session.language)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Title: ").append(firstNonBlank(target.title, route.label)).append("\n\n");
            if (!session.anchorSummary.isBlank()) {
                sb.append("Starting Point:\n").append(session.anchorSummary).append("\n\n");
            }
            sb.append("Route Intent:\n").append(firstNonBlank(route.summary, route.label)).append("\n\n");
            sb.append("Chapter Goal:\nAdvance the selected continuation route without breaking existing continuity.\n\n");
            sb.append("Key Beats:\n");
            for (String beat : chapterBeats) {
                sb.append("- ").append(beat).append("\n");
            }
            sb.append("\nEnding Hook:\n").append(firstNonBlank(target.hook, route.summary)).append("\n");
            if (!mustNotInclude.isEmpty()) {
                sb.append("\nDo Not Expand Yet:\n");
                for (String item : mustNotInclude) {
                    sb.append("- ").append(item).append("\n");
                }
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("章节标题：").append(firstNonBlank(target.title, route.label)).append("\n\n");
        if (!session.anchorSummary.isBlank()) {
            sb.append("【续写起点】\n").append(session.anchorSummary).append("\n\n");
        }
        sb.append("【路线意图】\n").append(firstNonBlank(route.summary, route.label)).append("\n\n");
        sb.append("【章节目标】\n在不破坏现有连续性的前提下，把选中的续写路线向前推进。\n\n");
        sb.append("【关键节拍】\n");
        for (String beat : chapterBeats) {
            sb.append("- ").append(beat).append("\n");
        }
        sb.append("\n【结尾钩子】\n").append(firstNonBlank(target.hook, route.summary)).append("\n");
        if (!mustNotInclude.isEmpty()) {
            sb.append("\n【本章不要提前展开】\n");
            for (String item : mustNotInclude) {
                sb.append("- ").append(item).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildExplorationRoutesSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction continuation planner.
                    Your task is to generate three clearly different continuation routes for the next chapter or the next 2-3 chapters inside the current volume.

                    Hard rules:
                    - Stay inside the current volume and respect established facts.
                    - The routes must differ in structural logic, not just wording.
                    - Output JSON only.
                    """;
        }
        return """
                你是一位小说“续写探索”策划助手。
                你的任务是：基于现有总纲/分卷纲/当前进度，为“下一章”或“接下来 2~3 章”生成 3 条明显不同的续写路线。

                硬性规则：
                - 只在当前卷内继续，不开新卷
                - 忠于已有事实、设定、人物关系与因果
                - 三条路线必须在结构逻辑上明显不同，不是同义改写
                - 只输出 JSON
                """;
    }

    private String buildExplorationRoutesUserPrompt(ExplorationSession session) {
        String decisionBlock = buildExplorationDecisionBlock(session);
        String recentSummaryBlock = buildExplorationRecentSummaryBlock(session);
        String targetBlock = String.join(", ", session.targetChapterIds);
        if ("en".equals(session.language)) {
            return String.format("""
                    Generate 3 continuation routes for the current volume.

                    Output JSON in this format:
                    {
                      "routes": [
                        {
                          "route_id": "route_a",
                          "label": "Route A: ...",
                          "summary": "40-90 word route pitch",
                          "why_it_fits": "Why this route matches the current context and choices",
                          "risks": ["risk 1", "risk 2"],
                          "beats": ["beat 1", "beat 2", "beat 3"],
                          "chapter_targets": [
                            {
                              "chapter_id": "%s",
                              "title": "Chapter title",
                              "summary": "What happens in this chapter",
                              "hook": "Chapter ending hook"
                            }
                          ]
                        }
                      ]
                    }

                    Scope: %s
                    Current volume: %s / %s
                    Continuation anchor: %s %s
                    Reserved chapter slots: %s

                    Requirements:
                    - If scope is next_chapter, each route must contain exactly 1 chapter target.
                    - If scope is next_multi, each route must contain 2 or 3 chapter targets using the reserved chapter slots in order, without skipping numbers.
                    - Make each route useful for user selection, not just stylistic variants.

                    %s%s%s%s%s
                    """,
                    session.targetChapterIds.isEmpty() ? "V1C1" : session.targetChapterIds.get(0),
                    session.scope,
                    session.volumeId,
                    session.volumeTitle,
                    firstNonBlank(session.anchorChapterId, "none"),
                    session.anchorChapterTitle.isBlank() ? "" : ("/ " + session.anchorChapterTitle),
                    targetBlock,
                    decisionBlock.isBlank() ? "" : "[User Choices]\n" + decisionBlock + "\n\n",
                    session.masterContent.isBlank() ? "" : "[Master Outline]\n" + trimForPrompt(session.masterContent, 2600) + "\n\n",
                    session.volumeContent.isBlank() ? "" : "[Volume Outline]\n" + trimForPrompt(session.volumeContent, 4200) + "\n\n",
                    recentSummaryBlock.isBlank() ? "" : "[Recent Progress]\n" + recentSummaryBlock + "\n\n",
                    buildExplorationAnchorBlock(session)
            );
        }
        return String.format("""
                请基于下面的上下文，为当前卷生成 3 条明显不同的续写路线。

                只输出 JSON，格式如下：
                {
                  "routes": [
                    {
                      "route_id": "route_a",
                      "label": "路线 A：……",
                      "summary": "40-90字路线概述",
                      "why_it_fits": "为什么这条路线适合当前上下文和用户选择",
                      "risks": ["这条路线的风险 1", "风险 2"],
                      "beats": ["关键推进1", "关键推进2", "关键推进3"],
                      "chapter_targets": [
                        {
                          "chapter_id": "%s",
                          "title": "章节标题",
                          "summary": "这一章主要发生什么",
                          "hook": "本章结尾钩子"
                        }
                      ]
                    }
                  ]
                }

                当前任务范围：%s
                当前卷：%s / %s
                续写起点：%s %s
                预留章节号：%s

                要求：
                - 如果范围是 next_chapter，每条路线只给 1 个 chapter_targets
                - 如果范围是 next_multi，每条路线给 2 或 3 个 chapter_targets，必须连续使用上述预留章节号，不得跳号
                - 三条路线要给用户真正可选的结构差异，不要只是语气不同

                %s%s%s%s%s
                """,
                session.targetChapterIds.isEmpty() ? "V1C1" : session.targetChapterIds.get(0),
                "next_multi".equals(session.scope) ? "接下来 2~3 章" : "下一章",
                session.volumeId,
                session.volumeTitle,
                firstNonBlank(session.anchorChapterId, "暂无"),
                session.anchorChapterTitle.isBlank() ? "" : ("/ " + session.anchorChapterTitle),
                targetBlock,
                decisionBlock.isBlank() ? "" : "【用户选择】\n" + decisionBlock + "\n\n",
                session.masterContent.isBlank() ? "" : "【总纲】\n" + trimForPrompt(session.masterContent, 2600) + "\n\n",
                session.volumeContent.isBlank() ? "" : "【分卷纲】\n" + trimForPrompt(session.volumeContent, 4200) + "\n\n",
                recentSummaryBlock.isBlank() ? "" : "【最近进度】\n" + recentSummaryBlock + "\n\n",
                buildExplorationAnchorBlock(session)
        );
    }

    private String buildExplorationPlanSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction planner converting one chosen continuation route into executable chapter plans.

                    Hard rules:
                    - Stay inside the current volume.
                    - Preserve continuity with the existing outline and chapter state.
                    - Output JSON only.
                    """;
        }
        return """
                你是一位小说章节策划助手。
                任务：把用户选中的“续写路线”展开成可执行章节规划。

                硬性规则：
                - 只在当前卷内继续
                - 保持与现有总纲、分卷纲、当前进度连续
                - 输出必须可直接给主笔使用
                - 只输出 JSON
                """;
    }

    private String buildExplorationPlanUserPrompt(ExplorationSession session,
                                                  ExplorationRoute route) {
        String decisionBlock = buildExplorationDecisionBlock(session);
        String recentSummaryBlock = buildExplorationRecentSummaryBlock(session);
        String routeBlock = buildExplorationRouteBlock(route);
        if ("en".equals(session.language)) {
            return String.format("""
                    Expand the chosen continuation route into executable chapter plans.

                    Output JSON in this format:
                    {
                      "chapters": [
                        {
                          "chapter_id": "V1C10",
                          "chapter_no": 10,
                          "title": "Chapter title",
                          "summary": "80-160 word concise summary",
                          "goal": "What this chapter must accomplish",
                          "key_beats": ["beat 1", "beat 2", "beat 3"],
                          "ending_hook": "Ending hook",
                          "must_not_include": ["later event 1", "later event 2"],
                          "outline_content": "A practical chapter outline"
                        }
                      ]
                    }

                    Scope: %s
                    Current volume: %s / %s
                    Required chapter slots: %s

                    Requirements:
                    - If scope is next_chapter, output exactly 1 chapter.
                    - If scope is next_multi, output 2 or 3 chapters using the required chapter slots in order.
                    - Each outline_content must be concrete enough for draft writing.
                    - Respect the user choices and the selected route structure.

                    %s%s%s%s%s%s
                    """,
                    session.scope,
                    session.volumeId,
                    session.volumeTitle,
                    String.join(", ", session.targetChapterIds),
                    decisionBlock.isBlank() ? "" : "[User Choices]\n" + decisionBlock + "\n\n",
                    session.masterContent.isBlank() ? "" : "[Master Outline]\n" + trimForPrompt(session.masterContent, 2400) + "\n\n",
                    session.volumeContent.isBlank() ? "" : "[Volume Outline]\n" + trimForPrompt(session.volumeContent, 3600) + "\n\n",
                    recentSummaryBlock.isBlank() ? "" : "[Recent Progress]\n" + recentSummaryBlock + "\n\n",
                    buildExplorationAnchorBlock(session),
                    "[Selected Route]\n" + routeBlock
            );
        }
        return String.format("""
                请把下面选中的“续写路线”展开成可执行章节规划。

                只输出 JSON，格式如下：
                {
                  "chapters": [
                    {
                      "chapter_id": "V1C10",
                      "chapter_no": 10,
                      "title": "章节标题",
                      "summary": "80-160字章节概述",
                      "goal": "这一章必须完成什么",
                      "key_beats": ["节拍1", "节拍2", "节拍3"],
                      "ending_hook": "结尾钩子",
                      "must_not_include": ["本章不要提前展开的内容1", "内容2"],
                      "outline_content": "可直接给主笔使用的章节细纲"
                    }
                  ]
                }

                当前任务范围：%s
                当前卷：%s / %s
                必须使用的章节号：%s

                要求：
                - 如果范围是 next_chapter，必须只输出 1 章
                - 如果范围是 next_multi，输出 2 或 3 章，并按顺序使用这些章节号
                - 每章都要有：标题、概述、目标、3-6 个关键节拍、结尾钩子、不要提前展开、章节细纲
                - outline_content 必须具体到能直接写正文
                - 要服从用户选择与所选路线的结构逻辑

                %s%s%s%s%s%s
                """,
                "next_multi".equals(session.scope) ? "接下来 2~3 章" : "下一章",
                session.volumeId,
                session.volumeTitle,
                String.join(", ", session.targetChapterIds),
                decisionBlock.isBlank() ? "" : "【用户选择】\n" + decisionBlock + "\n\n",
                session.masterContent.isBlank() ? "" : "【总纲】\n" + trimForPrompt(session.masterContent, 2400) + "\n\n",
                session.volumeContent.isBlank() ? "" : "【分卷纲】\n" + trimForPrompt(session.volumeContent, 3600) + "\n\n",
                recentSummaryBlock.isBlank() ? "" : "【最近进度】\n" + recentSummaryBlock + "\n\n",
                buildExplorationAnchorBlock(session),
                "【已选路线】\n" + routeBlock
        );
    }

    private String buildExplorationDecisionBlock(ExplorationSession session) {
        if (session.decisions == null || session.decisions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ExplorationDecision decision : session.decisions) {
            sb.append("- ").append(decision.questionTitle).append("：").append(decision.answerSummary).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildExplorationRecentSummaryBlock(ExplorationSession session) {
        if (session.recentSummaries == null || session.recentSummaries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChapterSummary summary : session.recentSummaries) {
            String chapterId = canonicalize(summary.getChapter());
            String title = firstNonBlank(summary.getTitle(), summary.getBriefSummary(), chapterId);
            String brief = firstNonBlank(summary.getBriefSummary(), joinTopItems(summary.getKeyEvents(), 3), joinTopItems(summary.getOpenLoops(), 2));
            sb.append("- ").append(chapterId);
            if (!title.isBlank()) {
                sb.append(" / ").append(title);
            }
            if (!brief.isBlank()) {
                sb.append("：").append(brief);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String buildExplorationAnchorBlock(ExplorationSession session) {
        StringBuilder sb = new StringBuilder();
        if (session.anchorChapterId != null && !session.anchorChapterId.isBlank()) {
            if ("en".equals(session.language)) {
                sb.append("[Continuation Anchor]\n");
                sb.append("Anchor chapter: ").append(session.anchorChapterId);
                if (!session.anchorChapterTitle.isBlank()) {
                    sb.append(" / ").append(session.anchorChapterTitle);
                }
                sb.append("\n");
                if (!session.anchorSummary.isBlank()) {
                    sb.append("Anchor summary: ").append(trimForPrompt(session.anchorSummary, 280)).append("\n");
                }
                if (!session.anchorOutline.isBlank()) {
                    sb.append("Anchor outline excerpt:\n").append(trimForPrompt(session.anchorOutline, 1200)).append("\n");
                }
            } else {
                sb.append("【续写起点】\n");
                sb.append("起点章节：").append(session.anchorChapterId);
                if (!session.anchorChapterTitle.isBlank()) {
                    sb.append(" / ").append(session.anchorChapterTitle);
                }
                sb.append("\n");
                if (!session.anchorSummary.isBlank()) {
                    sb.append("起点概述：").append(trimForPrompt(session.anchorSummary, 280)).append("\n");
                }
                if (!session.anchorOutline.isBlank()) {
                    sb.append("起点章节纲摘录：\n").append(trimForPrompt(session.anchorOutline, 1200)).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String buildExplorationRouteBlock(ExplorationRoute route) {
        StringBuilder sb = new StringBuilder();
        sb.append(route.label).append("\n");
        if (!route.summary.isBlank()) {
            sb.append(route.summary).append("\n");
        }
        if (!route.whyItFits.isBlank()) {
            sb.append("Why / 原因：").append(route.whyItFits).append("\n");
        }
        if (!route.beats.isEmpty()) {
            sb.append("Key Beats / 关键推进：\n");
            for (String beat : route.beats) {
                sb.append("- ").append(beat).append("\n");
            }
        }
        if (!route.chapterTargets.isEmpty()) {
            sb.append("Chapter Targets / 章节目标：\n");
            for (ExplorationChapterTarget target : route.chapterTargets) {
                sb.append("- ").append(target.chapterId).append(" / ").append(target.title)
                        .append("：").append(firstNonBlank(target.summary, target.hook)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String joinTopItems(List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(Math.max(1, limit))
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private int extractChapterNo(String chapterId, int fallback) {
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(canonicalize(chapterId));
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(2));
        }
        return Math.max(1, fallback);
    }

    private String buildVolumeSplitSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a professional long-form fiction planner.
                    Your task is to convert a volume outline into executable chapter plans.

                    Rules:
                    1. Split by chapter responsibilities, not by arbitrary length.
                    2. Each chapter must have a clear title, a concise summary, 3-6 key beats, and an ending hook.
                    3. Do not let Chapter 1 leak major events that belong to later chapters.
                    4. Preserve the original plot order and factual constraints.
                    5. Output JSON only.
                    """;
        }
        return """
                你是一位专业的长篇小说章节策划编辑。
                任务：把“分卷纲”拆成可执行的章节规划。

                规则：
                1. 按章节职责拆分，不按字数平均切。
                2. 每章必须有：标题、章节概述、章节目标、3-6 个关键节拍、结尾钩子。
                3. 第一章不要提前展开属于后续章节的大事件。
                4. 保持原有剧情顺序、设定和因果。
                5. 如果原文已经显式写了“第一章/第二章……”，优先尊重现有章节边界，再做适度整理。
                6. 只输出 JSON，不要解释。
                """;
    }

    private String buildVolumeSplitUserPrompt(String language,
                                              String volumeId,
                                              String volumeTitle,
                                              String masterContent,
                                              String volumeContent) {
        if ("en".equals(language)) {
            return String.format("""
                    Please turn the following volume outline into chapter plans.

                    Output JSON in this format:
                    {
                      "chapters": [
                        {
                          "chapter_no": 1,
                          "title": "Chapter title",
                          "summary": "80-160 word concise summary",
                          "goal": "What this chapter must accomplish",
                          "key_beats": ["beat 1", "beat 2", "beat 3"],
                          "ending_hook": "Ending hook",
                          "must_not_include": ["later event 1", "later event 2"],
                          "outline_content": "A compact actionable chapter outline"
                        }
                      ]
                    }

                    Volume ID: %s
                    Volume title: %s

                    %s
                    [Volume Outline]
                    %s
                    """,
                    volumeId,
                    volumeTitle,
                    masterContent.isBlank() ? "" : "[Master Outline]\n" + trimForPrompt(masterContent, 4000) + "\n\n",
                    trimForPrompt(volumeContent, 6000));
        }
        return String.format("""
                请把下面这份分卷纲拆成章节规划。

                只输出 JSON，格式如下：
                {
                  "chapters": [
                    {
                      "chapter_no": 1,
                      "title": "章节标题",
                      "summary": "80-160字章节概述",
                      "goal": "这一章必须完成什么",
                      "key_beats": ["节拍1", "节拍2", "节拍3"],
                      "ending_hook": "本章结尾钩子",
                      "must_not_include": ["本章不该提前展开的后续内容1", "后续内容2"],
                      "outline_content": "可直接给主笔使用的章节细纲"
                    }
                  ]
                }

                要求：
                - 若分卷纲已经显式写了“第一章/第二章……”，优先按现有章节边界整理输出
                - 若某章明显过粗，可以合理细分，但不要乱改主线顺序
                - 概述要写“这一章发生了什么、推进到哪里”
                - 章节目标要写“本章必须完成什么”
                - 结尾钩子要具体，不要空泛
                - 章节细纲要可执行，便于后续写正文

                分卷ID：%s
                分卷标题：%s

                %s
                【分卷纲】
                %s
                """,
                volumeId,
                volumeTitle,
                masterContent.isBlank() ? "" : "【总纲参考】\n" + trimForPrompt(masterContent, 4000) + "\n\n",
                trimForPrompt(volumeContent, 6000));
    }

    private String buildPlannedChapterSplitSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction chapter-planning editor.
                    Your task is to split one overly dense planned chapter into 2-4 cleaner, more executable chapters.

                    Hard rules:
                    - Obey the author's explicit split intent first.
                    - Respect existing continuity with the surrounding chapter plan.
                    - Do not steal responsibilities from the previous or next planned chapter.
                    - Do not invent unsupported major twists, settings, or characters.
                    - Each split chapter must have a distinct responsibility, clear progression, and usable ending hook.
                    - Output valid JSON only.
                    """;
        }
        return """
                你是一位小说章节策划编辑。
                你的任务是把“一个过于拥挤的章节规划”拆成 2~4 个更可执行的小章节。

                硬性规则：
                - 先服从作者显式补充意图
                - 必须尊重上下章边界与当前卷的连续性
                - 不要偷走上一章或下一章本该负责的内容
                - 不得凭空新增原规划没有根据的重要设定、角色或反转
                - 拆分后每一章都要职责明确、推进清楚、结尾可挂钩
                - 只输出合法 JSON，不要解释
                """;
    }

    private String buildPlannedChapterSplitUserPrompt(String language,
                                                      String volumeId,
                                                      String volumeTitle,
                                                      int desiredSplitCount,
                                                      String masterContent,
                                                      String volumeContent,
                                                      List<PlannedChapter> allChapters,
                                                      PlannedChapter targetChapter,
                                                      PlannedChapter previousChapter,
                                                      PlannedChapter nextChapter,
                                                      String instruction) {
        String planContext = buildPlannedChapterSequenceContext(allChapters, targetChapter.chapterId(), language);
        String previousBlock = buildSinglePlannedChapterContext(previousChapter, language, true);
        String targetBlock = buildSinglePlannedChapterContext(targetChapter, language, false);
        String nextBlock = buildSinglePlannedChapterContext(nextChapter, language, true);

        if ("en".equals(language)) {
            return String.format("""
                    Please split one planned chapter inside volume %s (%s) into cleaner executable chapters.

                    Author intent (highest priority):
                    %s

                    Desired split count:
                    %d

                    Requirements:
                    1. Split only the target chapter, not the entire volume plan.
                    2. The first split chapter must connect naturally from the previous chapter; the last split chapter must hand off cleanly to the next chapter.
                    3. Keep the original plot order and factual constraints.
                    4. Each split chapter must feel necessary, with a distinct responsibility, not just arbitrary cutting.
                    5. Keep the result between 2 and 4 chapters. If the author's desired count is unsuitable, adjust it slightly and explain why.

                    Return JSON in this format:
                    {
                      "split_required": true,
                      "recommended_split_count": 3,
                      "split_reasons": ["reason 1", "reason 2"],
                      "split_strategy_summary": "one-paragraph split logic",
                      "chapters": [
                        {
                          "chapter_no": %d,
                          "title": "chapter title",
                          "summary": "what happens in this subchapter",
                          "goal": "what this subchapter must accomplish",
                          "key_beats": ["beat 1", "beat 2", "beat 3"],
                          "ending_hook": "ending hook",
                          "must_not_include": ["later material that should stay out"],
                          "outline_content": "actionable outline"
                        }
                      ]
                    }

                    %s%s
                    [Current Volume Planned Chapter Overview]
                    %s

                    [Previous Planned Chapter]
                    %s

                    [Target Planned Chapter]
                    %s

                    [Next Planned Chapter]
                    %s
                    """,
                    volumeId,
                    volumeTitle,
                    instruction,
                    desiredSplitCount,
                    targetChapter.chapterNo(),
                    masterContent.isBlank() ? "" : "[Master Outline Reference]\n" + trimForPrompt(masterContent, 1800) + "\n\n",
                    volumeContent.isBlank() ? "" : "[Volume Outline Reference]\n" + trimForPrompt(volumeContent, 2200) + "\n\n",
                    planContext,
                    previousBlock,
                    targetBlock,
                    nextBlock
            );
        }

        return String.format("""
                现在要把分卷 %s（%s）里的一个“章节规划”拆成更可执行的小章节。

                【作者补充意图（最高优先级）】
                %s

                【期望拆分章数】
                %d

                要求：
                1. 只拆目标章节，不要顺手改写整卷章节职责。
                2. 第一小章要自然承接上一章，最后一小章要自然交给下一章。
                3. 保持原有剧情顺序、设定边界和因果，不要把后续章节的内容提前偷跑。
                4. 每个拆出的章节都必须有独立职责，不能只是把原文硬切几段。
                5. 最终结果控制在 2~4 章；如果作者期望章数不合理，可以微调，但要在 split_reasons 里体现原因。

                严格输出 JSON，格式如下：
                {
                  "split_required": true,
                  "recommended_split_count": 3,
                  "split_reasons": ["原因1", "原因2"],
                  "split_strategy_summary": "一句话概括拆分逻辑",
                  "chapters": [
                    {
                      "chapter_no": %d,
                      "title": "章节标题",
                      "summary": "这一小章发生了什么",
                      "goal": "这一小章必须完成什么",
                      "key_beats": ["节拍1", "节拍2", "节拍3"],
                      "ending_hook": "本章结尾钩子",
                      "must_not_include": ["此章不该提前展开的后续内容"],
                      "outline_content": "可直接执行的小章节细纲"
                    }
                  ]
                }

                %s%s
                【当前卷章节规划概览】
                %s

                【上一章】
                %s

                【目标章节（待拆分）】
                %s

                【下一章】
                %s
                """,
                volumeId,
                volumeTitle,
                instruction,
                desiredSplitCount,
                targetChapter.chapterNo(),
                masterContent.isBlank() ? "" : "【总纲参考】\n" + trimForPrompt(masterContent, 1800) + "\n\n",
                volumeContent.isBlank() ? "" : "【分卷纲参考】\n" + trimForPrompt(volumeContent, 2200) + "\n\n",
                planContext,
                previousBlock,
                targetBlock,
                nextBlock
        );
    }

    private String buildPlannedChapterSequenceContext(List<PlannedChapter> chapters,
                                                      String targetChapterId,
                                                      String language) {
        if (chapters == null || chapters.isEmpty()) {
            return "en".equals(language) ? "(only the target chapter is available)" : "（当前只有目标章节可供参考）";
        }
        int targetIndex = 0;
        for (int index = 0; index < chapters.size(); index++) {
            if (canonicalize(chapters.get(index).chapterId()).equalsIgnoreCase(canonicalize(targetChapterId))) {
                targetIndex = index;
                break;
            }
        }
        int start = Math.max(0, targetIndex - 3);
        int end = Math.min(chapters.size(), targetIndex + 4);
        StringBuilder sb = new StringBuilder();
        if (chapters.size() > (end - start)) {
            sb.append("en".equals(language)
                    ? "Showing nearby planned chapters only:\n"
                    : "以下只展示目标章节附近的规划：\n");
        }
        for (int index = start; index < end; index++) {
            PlannedChapter chapter = chapters.get(index);
            boolean target = canonicalize(chapter.chapterId()).equalsIgnoreCase(canonicalize(targetChapterId));
            if (target) {
                sb.append(">>> ");
            } else {
                sb.append("- ");
            }
            sb.append(chapter.chapterId()).append(" ").append(firstNonBlank(chapter.title(), defaultChapterTitle(language, chapter.chapterNo())));
            String summary = clipText(firstNonBlank(chapter.summary(), chapter.goal(), chapter.endingHook()), 90);
            if (!summary.isBlank()) {
                sb.append("：").append(summary);
            }
            if (target) {
                sb.append("  ").append("en".equals(language) ? "[TARGET]" : "【待拆分】");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String buildSinglePlannedChapterContext(PlannedChapter chapter,
                                                    String language,
                                                    boolean allowEmptyPlaceholder) {
        if (chapter == null) {
            return allowEmptyPlaceholder
                    ? ("en".equals(language) ? "(none)" : "（无）")
                    : "";
        }

        if ("en".equals(language)) {
            StringBuilder sb = new StringBuilder();
            sb.append("ID: ").append(chapter.chapterId()).append("\n");
            sb.append("Title: ").append(chapter.title()).append("\n");
            if (!chapter.summary().isBlank()) {
                sb.append("Summary: ").append(clipText(chapter.summary(), 220)).append("\n");
            }
            if (!chapter.goal().isBlank()) {
                sb.append("Goal: ").append(clipText(chapter.goal(), 180)).append("\n");
            }
            if (!chapter.keyBeats().isEmpty()) {
                sb.append("Key Beats:\n");
                chapter.keyBeats().stream().limit(5).forEach(item -> sb.append("- ").append(item).append("\n"));
            }
            if (!chapter.endingHook().isBlank()) {
                sb.append("Ending Hook: ").append(clipText(chapter.endingHook(), 140)).append("\n");
            }
            if (!chapter.mustNotInclude().isEmpty()) {
                sb.append("Do Not Expand Yet:\n");
                chapter.mustNotInclude().stream().limit(4).forEach(item -> sb.append("- ").append(item).append("\n"));
            }
            if (!chapter.outlineContent().isBlank()) {
                sb.append("Outline:\n").append(trimForPrompt(chapter.outlineContent(), 800)).append("\n");
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("章节ID：").append(chapter.chapterId()).append("\n");
        sb.append("标题：").append(chapter.title()).append("\n");
        if (!chapter.summary().isBlank()) {
            sb.append("概述：").append(clipText(chapter.summary(), 220)).append("\n");
        }
        if (!chapter.goal().isBlank()) {
            sb.append("目标：").append(clipText(chapter.goal(), 180)).append("\n");
        }
        if (!chapter.keyBeats().isEmpty()) {
            sb.append("关键节拍：\n");
            chapter.keyBeats().stream().limit(5).forEach(item -> sb.append("- ").append(item).append("\n"));
        }
        if (!chapter.endingHook().isBlank()) {
            sb.append("结尾钩子：").append(clipText(chapter.endingHook(), 140)).append("\n");
        }
        if (!chapter.mustNotInclude().isEmpty()) {
            sb.append("不要提前展开：\n");
            chapter.mustNotInclude().stream().limit(4).forEach(item -> sb.append("- ").append(item).append("\n"));
        }
        if (!chapter.outlineContent().isBlank()) {
            sb.append("章节细纲：\n").append(trimForPrompt(chapter.outlineContent(), 800)).append("\n");
        }
        return sb.toString().trim();
    }

    private PlannedChapterSplitResult parsePlannedChapterSplitResult(String volumeId,
                                                                     int startChapterNo,
                                                                     PlannedChapter targetChapter,
                                                                     String raw,
                                                                     int desiredSplitCount,
                                                                     String language) throws IOException {
        if (raw == null || raw.isBlank()) {
            return fallbackPlannedChapterSplitResult(volumeId, targetChapter, desiredSplitCount, language);
        }

        JsonNode root = extractJsonNode(raw);
        List<String> splitReasons = sanitizeStringList(readStringList(root, "split_reasons", "splitReasons", "reasons", "why_split"));
        String strategySummary = readText(root, "split_strategy_summary", "splitStrategySummary", "strategy_summary", "strategy", "summary");
        boolean splitRequired = !root.has("split_required") || root.path("split_required").asBoolean(true);
        int recommendedSplitCount = clampSplitCount(positiveInt(
                root,
                desiredSplitCount,
                "recommended_split_count",
                "recommendedSplitCount",
                "split_count",
                "splitCount"
        ));

        JsonNode chaptersNode = root.path("chapters");
        List<PlannedChapter> chapters = chaptersNode.isArray()
                ? parseChaptersFromRequest(volumeId, chaptersNode, language)
                : List.of();

        if (chapters.size() < 2) {
            return fallbackPlannedChapterSplitResult(volumeId, targetChapter, desiredSplitCount, language);
        }

        if (chapters.size() > 4) {
            chapters = new ArrayList<>(chapters.subList(0, 4));
        }

        List<PlannedChapter> resequenced = resequencePlannedChapters(volumeId, chapters, startChapterNo, language);
        if (splitReasons.isEmpty()) {
            splitReasons = buildDefaultSplitReasons(targetChapter, resequenced.size(), language);
        }
        if (strategySummary.isBlank()) {
            strategySummary = defaultSplitStrategySummary(resequenced.size(), language);
        }

        return new PlannedChapterSplitResult(
                splitRequired,
                Math.max(recommendedSplitCount, resequenced.size()),
                splitReasons,
                strategySummary,
                resequenced
        );
    }

    private PlannedChapterSplitResult fallbackPlannedChapterSplitResult(String volumeId,
                                                                        PlannedChapter targetChapter,
                                                                        int desiredSplitCount,
                                                                        String language) {
        int splitCount = clampSplitCount(desiredSplitCount);
        List<String> summaryParts = splitTextIntoParts(firstNonBlank(targetChapter.summary(), targetChapter.goal(), targetChapter.title()), splitCount);
        List<String> goalParts = splitTextIntoParts(firstNonBlank(targetChapter.goal(), targetChapter.summary()), splitCount);
        List<String> hookParts = splitTextIntoParts(firstNonBlank(targetChapter.endingHook(), targetChapter.summary()), splitCount);
        List<String> outlineParts = splitTextIntoParts(firstNonBlank(targetChapter.outlineContent(), targetChapter.summary(), targetChapter.goal()), splitCount);
        List<List<String>> beatParts = splitListIntoParts(targetChapter.keyBeats(), splitCount);
        List<List<String>> mustNotParts = splitListIntoParts(targetChapter.mustNotInclude(), splitCount);

        List<PlannedChapter> chapters = new ArrayList<>();
        for (int index = 0; index < splitCount; index++) {
            String title = decorateSplitChapterTitle(targetChapter.title(), splitCount, index, language);
            String summary = firstNonBlank(summaryParts.get(index), targetChapter.summary(), title);
            String goal = firstNonBlank(goalParts.get(index), targetChapter.goal(), summary);
            String hook = firstNonBlank(hookParts.get(index), summary);
            List<String> beats = beatParts.get(index);
            List<String> mustNotInclude = mustNotParts.get(index);
            String outline = firstNonBlank(
                    outlineParts.get(index),
                    buildOutlineContent(language, title, summary, goal, beats, hook, mustNotInclude)
            );
            chapters.add(normalizePlannedChapter(
                    volumeId,
                    targetChapter.chapterNo() + index,
                    volumeId + "C" + (targetChapter.chapterNo() + index),
                    title,
                    summary,
                    goal,
                    beats,
                    hook,
                    mustNotInclude,
                    outline,
                    language
            ));
        }

        return new PlannedChapterSplitResult(
                true,
                splitCount,
                buildDefaultSplitReasons(targetChapter, splitCount, language),
                defaultSplitStrategySummary(splitCount, language),
                chapters
        );
    }

    private PlannedChapter parseRequestedPlannedChapter(String volumeId,
                                                        JsonNode node,
                                                        int fallbackNo,
                                                        String language) {
        return normalizePlannedChapter(
                volumeId,
                positiveInt(node, fallbackNo, "chapter_no", "chapterNo", "no", "index"),
                readText(node, "chapter_id", "chapterId"),
                readText(node, "title", "chapter_title", "chapterTitle"),
                readText(node, "summary", "brief_summary", "briefSummary"),
                readText(node, "goal", "chapter_goal", "chapterGoal"),
                readStringList(node, "key_beats", "keyBeats", "beats"),
                readText(node, "ending_hook", "endingHook", "hook"),
                readStringList(node, "must_not_include", "mustNotInclude", "forbidden"),
                readText(node, "outline_content", "outlineContent", "content", "outline"),
                language
        );
    }

    private List<PlannedChapter> resequencePlannedChapters(String volumeId,
                                                           List<PlannedChapter> chapters,
                                                           int startChapterNo,
                                                           String language) {
        List<PlannedChapter> resequenced = new ArrayList<>();
        for (int index = 0; index < chapters.size(); index++) {
            PlannedChapter chapter = chapters.get(index);
            int chapterNo = Math.max(1, startChapterNo + index);
            resequenced.add(normalizePlannedChapter(
                    volumeId,
                    chapterNo,
                    volumeId + "C" + chapterNo,
                    chapter.title(),
                    chapter.summary(),
                    chapter.goal(),
                    chapter.keyBeats(),
                    chapter.endingHook(),
                    chapter.mustNotInclude(),
                    chapter.outlineContent(),
                    language
            ));
        }
        return resequenced;
    }

    private int clampSplitCount(int value) {
        return Math.max(2, Math.min(4, value));
    }

    private List<String> buildDefaultSplitReasons(PlannedChapter targetChapter,
                                                  int splitCount,
                                                  String language) {
        boolean english = "en".equals(language);
        List<String> reasons = new ArrayList<>();
        if (targetChapter.keyBeats().size() >= 5) {
            reasons.add(english
                    ? "The target chapter is carrying too many beats for a single clean responsibility."
                    : "目标章节承载的关键节拍过多，单章职责不够聚焦。");
        }
        if (cleanOutlineText(targetChapter.outlineContent()).length() >= 280 || cleanOutlineText(targetChapter.summary()).length() >= 140) {
            reasons.add(english
                    ? "Its summary / outline already implies multiple consecutive movement stages."
                    : "章节概述或细纲本身已经包含多个连续推进阶段。");
        }
        if (reasons.isEmpty()) {
            reasons.add(english
                    ? "A smaller chapter chain can make scene execution and pacing clearer."
                    : "拆成更小的章节链后，执行层场景与节奏会更清楚。");
        }
        reasons.add(english
                ? "Splitting into " + splitCount + " chapters helps keep each landing point more distinct."
                : "拆成 " + splitCount + " 章后，更容易让每一章都形成明确落点。");
        return sanitizeStringList(reasons);
    }

    private String defaultSplitStrategySummary(int splitCount, String language) {
        if ("en".equals(language)) {
            return switch (splitCount) {
                case 2 -> "Split the chapter into a setup / escalation part and a payoff / handoff part.";
                case 3 -> "Split it into setup, pressure increase, and landing / handoff.";
                default -> "Split it into a step-by-step progression so each chapter carries one clear movement and one clear handoff.";
            };
        }
        return switch (splitCount) {
            case 2 -> "按“进入与铺垫 / 推进与交棒”两段职责拆开。";
            case 3 -> "按“进入 / 加压 / 落点”三段推进拆开。";
            default -> "按连续推进链拆开，让每一章各自负责一个清晰动作与落点。";
        };
    }

    private String decorateSplitChapterTitle(String title,
                                             int splitCount,
                                             int index,
                                             String language) {
        String safeTitle = firstNonBlank(title, defaultChapterTitle(language, index + 1));
        if ("en".equals(language)) {
            return safeTitle + " (Part " + (index + 1) + ")";
        }
        if (splitCount == 2) {
            return safeTitle + (index == 0 ? "（上）" : "（下）");
        }
        if (splitCount == 3) {
            return switch (index) {
                case 0 -> safeTitle + "（上）";
                case 1 -> safeTitle + "（中）";
                default -> safeTitle + "（下）";
            };
        }
        String[] numerals = {"一", "二", "三", "四"};
        return safeTitle + "（" + numerals[Math.min(index, numerals.length - 1)] + "）";
    }

    private List<String> splitTextIntoParts(String text, int parts) {
        int safeParts = clampSplitCount(parts);
        String value = String.valueOf(text == null ? "" : text).trim();
        if (value.isBlank()) {
            return new ArrayList<>(Collections.nCopies(safeParts, ""));
        }

        List<String> segments = Arrays.stream(value.split("\\n{2,}"))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (segments.size() < safeParts) {
            segments = Arrays.stream(value.split("(?<=[。！？!?；;])"))
                    .map(String::trim)
                    .filter(segment -> !segment.isBlank())
                    .toList();
        }
        if (segments.isEmpty()) {
            segments = List.of(value);
        }

        List<String> result = new ArrayList<>();
        for (int index = 0; index < safeParts; index++) {
            int start = Math.round(index * segments.size() / (float) safeParts);
            int end = Math.round((index + 1) * segments.size() / (float) safeParts);
            if (start >= end) {
                result.add("");
                continue;
            }
            String joiner = value.contains("\n\n") ? "\n\n" : "";
            result.add(String.join(joiner, segments.subList(start, end)).trim());
        }

        if (result.stream().allMatch(String::isBlank)) {
            int sliceLength = Math.max(1, value.length() / safeParts);
            result.clear();
            for (int index = 0; index < safeParts; index++) {
                int start = Math.min(value.length(), index * sliceLength);
                int end = index == safeParts - 1 ? value.length() : Math.min(value.length(), (index + 1) * sliceLength);
                result.add(value.substring(start, end).trim());
            }
        }
        return result;
    }

    private List<List<String>> splitListIntoParts(List<String> items, int parts) {
        int safeParts = clampSplitCount(parts);
        List<String> list = items == null ? List.of() : items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();

        List<List<String>> result = new ArrayList<>();
        if (list.isEmpty()) {
            for (int index = 0; index < safeParts; index++) {
                result.add(List.of());
            }
            return result;
        }

        for (int index = 0; index < safeParts; index++) {
            int start = Math.round(index * list.size() / (float) safeParts);
            int end = Math.round((index + 1) * list.size() / (float) safeParts);
            if (start >= end) {
                result.add(List.of());
                continue;
            }
            result.add(new ArrayList<>(list.subList(start, end)));
        }
        return result;
    }

    private String trimForPrompt(String text, int maxChars) {
        if (text == null) return "";
        String normalized = text.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n...(truncated)";
    }

    private String buildReverseOutlineSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction restructuring editor.
                    Your job is to reverse-engineer an executable chapter outline from an existing chapter draft.
                    This is not a loose summary. It must be a practical restructuring spec that can guide rewriting.

                    Rules:
                    - Stay faithful to the chapter's actual facts, character relationships, and ending state.
                    - Do not invent new settings, twists, or characters not supported by the draft.
                    - Prefer concrete scene functions, conflict progression, and ending landing points.
                    - Output plain text only.
                    """;
        }
        return """
                你是一位小说重构编辑。
                你的任务是从现有章节正文中，反向提炼出一份“可执行章节纲”。
                这不是泛泛总结，而是能直接指导重构/重写的执行规格。

                规则：
                - 忠于正文里已经出现的事实、人物关系和结尾状态
                - 不得凭空补新设定、新角色、新反转
                - 尽量写清场景功能、冲突推进和结尾落点
                - 直接输出纯文本，不要解释
                """;
    }

    private String buildReverseOutlineUserPrompt(String language,
                                                 String chapterId,
                                                 String masterContent,
                                                 String volumeContent,
                                                 String existingChapterOutline,
                                                 String chapterContent,
                                                 String instruction) {
        if ("en".equals(language)) {
            return String.format("""
                    Please reverse-engineer an executable chapter spec for %s from the draft below.

                    Output using this structure:
                    [Chapter Positioning]
                    - Chapter function:
                    - POV:
                    - Core conflict:
                    - Emotional curve:
                    - Ending landing point:

                    [Scene Breakdown]
                    1.
                    2.
                    3.

                    [Must Preserve]
                    - ...

                    [Can Adjust]
                    - ...

                    [Restructure Notes]
                    - Which parts are suitable for trimming, reordering, frontloading, or strengthening.

                    %s%s%s%s
                    [Draft]
                    %s
                    """,
                    chapterId,
                    instruction.isBlank() ? "" : "[Extra Intent]\n" + trimForPrompt(instruction, 1200) + "\n\n",
                    masterContent.isBlank() ? "" : "[Master Outline Reference]\n" + trimForPrompt(masterContent, 2200) + "\n\n",
                    volumeContent.isBlank() ? "" : "[Volume Outline Reference]\n" + trimForPrompt(volumeContent, 2200) + "\n\n",
                    existingChapterOutline.isBlank() ? "" : "[Existing Chapter Outline]\n" + trimForPrompt(existingChapterOutline, 1800) + "\n\n",
                    trimForPrompt(chapterContent, 10000)
            );
        }
        return String.format("""
                请根据下面的章节正文，反向提炼出 %s 的“可执行章节纲”。

                请严格使用下面结构输出：
                【本章定位】
                - 本章功能：
                - 主视角：
                - 核心冲突：
                - 情绪走向：
                - 结尾落点：

                【场景拆分】
                1.
                2.
                3.

                【必须保留】
                - ...

                【允许调整】
                - ...

                【重构建议】
                - 哪些部分适合删减、前置、后置、合并、强化

                %s%s%s%s
                【章节正文】
                %s
                """,
                chapterId,
                instruction.isBlank() ? "" : "【额外意图】\n" + trimForPrompt(instruction, 1200) + "\n\n",
                masterContent.isBlank() ? "" : "【总纲参考】\n" + trimForPrompt(masterContent, 2200) + "\n\n",
                volumeContent.isBlank() ? "" : "【分卷纲参考】\n" + trimForPrompt(volumeContent, 2200) + "\n\n",
                existingChapterOutline.isBlank() ? "" : "【现有章节纲】\n" + trimForPrompt(existingChapterOutline, 1800) + "\n\n",
                trimForPrompt(chapterContent, 10000)
        );
    }

    private String buildOutlineRewriteSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction restructuring editor.
                    Rewrite the chapter draft according to the executable chapter outline.

                    Hard rules:
                    - Obey explicit user intent first, then the chapter outline.
                    - Preserve established facts, names, world setting, and important relationship states unless explicitly changed.
                    - You may tighten pacing, reorder beats, rewrite transitions, and strengthen conflict.
                    - Do not append explanations or notes.
                    - Output only the rewritten chapter text.
                    """;
        }
        return """
                你是一位小说重构编辑。
                请按照“可执行章节纲”重写章节正文。

                硬性规则：
                - 优先服从显式用户意图，其次服从章节纲
                - 默认保留已建立的事实、专名、设定、人物关系和关键结尾状态，除非明确要求改动
                - 可以调整节奏、顺序、衔接和冲突强度
                - 不要追加说明、注释或分析
                - 只输出重写后的正文
                """;
    }

    private String buildOutlineRewriteUserPrompt(String language,
                                                 String chapterId,
                                                 String masterContent,
                                                 String volumeContent,
                                                 String chapterOutline,
                                                 String originalContent,
                                                 String instruction) {
        if ("en".equals(language)) {
            return String.format("""
                    Please rewrite chapter %s based on the executable chapter outline below.

                    Priorities:
                    1. Explicit user restructuring intent
                    2. Chapter outline constraints and ending landing point
                    3. Existing draft facts and names

                    Requirements:
                    - Preserve must-keep items in the outline
                    - If the outline calls for restructuring, do not merely polish sentences
                    - Keep the chapter coherent and self-contained
                    - Do not introduce unsupported new setting facts

                    %s%s
                    [Executable Chapter Outline]
                    %s

                    [Original Draft]
                    %s
                    """,
                    chapterId,
                    instruction.isBlank() ? "" : "[Explicit Restructure Intent]\n" + trimForPrompt(instruction, 1200) + "\n\n",
                    masterContent.isBlank() && volumeContent.isBlank() ? "" :
                            ((masterContent.isBlank() ? "" : "[Master Outline Reference]\n" + trimForPrompt(masterContent, 1800) + "\n\n")
                                    + (volumeContent.isBlank() ? "" : "[Volume Outline Reference]\n" + trimForPrompt(volumeContent, 1800) + "\n\n")),
                    trimForPrompt(chapterOutline, 5000),
                    trimForPrompt(originalContent, 12000)
            );
        }
        return String.format("""
                请按下面的“可执行章节纲”重构章节 %s 的正文。

                优先级：
                1. 用户显式重构意图
                2. 章节纲里的结构要求、必须保留项、结尾落点
                3. 原正文里的既有事实、专名和设定

                要求：
                - 章节纲中写了“必须保留”的内容必须保留
                - 如果章节纲要求重构，就不要只做表面润色
                - 允许调整顺序、压缩拖沓段、强化冲突和衔接
                - 不要新增正文里没有根据的重要设定或角色
                - 只输出最终正文

                %s%s
                【可执行章节纲】
                %s

                【原正文】
                %s
                """,
                chapterId,
                instruction.isBlank() ? "" : "【用户显式重构意图】\n" + trimForPrompt(instruction, 1200) + "\n\n",
                masterContent.isBlank() && volumeContent.isBlank() ? "" :
                        ((masterContent.isBlank() ? "" : "【总纲参考】\n" + trimForPrompt(masterContent, 1800) + "\n\n")
                                + (volumeContent.isBlank() ? "" : "【分卷纲参考】\n" + trimForPrompt(volumeContent, 1800) + "\n\n")),
                trimForPrompt(chapterOutline, 5000),
                trimForPrompt(originalContent, 12000)
        );
    }

    private String buildSceneRewriteSystemPrompt(String language) {
        if ("en".equals(language)) {
            return """
                    You are a fiction restructuring editor focused on scene-level rewrites.
                    Your task is to revise one target scene inside a chapter while keeping the rest of the chapter as stable as possible.

                    Hard rules:
                    - Focus on the target scene first; only change adjacent transitions when necessary.
                    - Preserve facts, names, setting details, and ending state unless the target-scene instructions explicitly require adjustment.
                    - Do not rewrite the whole chapter just because you can.
                    - Output only the rewritten full chapter text.
                    """;
        }
        return """
                你是一位专做“场景级重构”的小说编辑。
                你的任务是在一章正文里，重点重构一个指定场景，并尽量保持其他部分稳定。

                硬性规则：
                - 优先只改目标场景，只有前后衔接必须调整时才动相邻内容
                - 默认保留既有事实、专名、设定和整体结尾状态，除非目标场景要求改动
                - 不要借机把整章都顺手重写
                - 只输出重写后的完整正文
                """;
    }

    private String buildSceneRewriteUserPrompt(String language,
                                               String chapterId,
                                               String masterContent,
                                               String volumeContent,
                                               String chapterOutline,
                                               String originalContent,
                                               List<ParsedScene> scenes,
                                               ParsedScene targetScene,
                                               String instruction) {
        String scenesBlock = buildScenesReferenceBlock(scenes, targetScene);
        if ("en".equals(language)) {
            return String.format("""
                    Please rewrite only the target scene inside chapter %s according to the outline below.

                    Priority:
                    1. Target-scene objective and conflict
                    2. Must-keep facts and chapter continuity
                    3. Keep non-target scenes as unchanged as possible

                    Requirements:
                    - The target scene should become more executable, clearer, or stronger according to the outline
                    - Keep surrounding transitions coherent
                    - Avoid unnecessary edits outside the target scene

                    %s%s
                    [Executable Chapter Outline]
                    %s

                    [Scene List]
                    %s

                    [Target Scene]
                    %d. %s
                    %s

                    [Original Draft]
                    %s
                    """,
                    chapterId,
                    instruction.isBlank() ? "" : "[Explicit Target-Scene Intent]\n" + trimForPrompt(instruction, 1200) + "\n\n",
                    masterContent.isBlank() && volumeContent.isBlank() ? "" :
                            ((masterContent.isBlank() ? "" : "[Master Outline Reference]\n" + trimForPrompt(masterContent, 1400) + "\n\n")
                                    + (volumeContent.isBlank() ? "" : "[Volume Outline Reference]\n" + trimForPrompt(volumeContent, 1400) + "\n\n")),
                    trimForPrompt(chapterOutline, 4200),
                    scenesBlock,
                    targetScene.index(),
                    targetScene.title(),
                    trimForPrompt(targetScene.raw(), 1200),
                    trimForPrompt(originalContent, 12000)
            );
        }
        return String.format("""
                请只针对章节 %s 里的目标场景做重构，其他场景尽量保持稳定。

                优先级：
                1. 目标场景的目标、冲突和推进作用
                2. 必须保留的事实与全章连续性
                3. 非目标场景尽量少动

                要求：
                - 目标场景要更清楚、更可执行，或者冲突更强
                - 只在必要时调整前后衔接
                - 不要无意义改动非目标场景

                %s%s
                【可执行章节纲】
                %s

                【场景列表】
                %s

                【目标场景】
                %d. %s
                %s

                【原正文】
                %s
                """,
                chapterId,
                instruction.isBlank() ? "" : "【目标场景额外意图】\n" + trimForPrompt(instruction, 1200) + "\n\n",
                masterContent.isBlank() && volumeContent.isBlank() ? "" :
                        ((masterContent.isBlank() ? "" : "【总纲参考】\n" + trimForPrompt(masterContent, 1400) + "\n\n")
                                + (volumeContent.isBlank() ? "" : "【分卷纲参考】\n" + trimForPrompt(volumeContent, 1400) + "\n\n")),
                trimForPrompt(chapterOutline, 4200),
                scenesBlock,
                targetScene.index(),
                targetScene.title(),
                trimForPrompt(targetScene.raw(), 1200),
                trimForPrompt(originalContent, 12000)
        );
    }

    private String buildScenesReferenceBlock(List<ParsedScene> scenes, ParsedScene targetScene) {
        StringBuilder sb = new StringBuilder();
        for (ParsedScene scene : scenes) {
            if (scene.index() == targetScene.index()) {
                sb.append("* ");
            }
            sb.append(scene.index()).append(". ").append(scene.title());
            if (scene.raw() != null && !scene.raw().isBlank()) {
                sb.append(" —— ").append(trimForPrompt(scene.raw(), 220));
            }
            if (scene.index() == targetScene.index()) {
                sb.append("  [TARGET]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<ParsedScene> parseOutlineScenes(String outlineText) {
        if (outlineText == null || outlineText.isBlank()) {
            return List.of();
        }
        List<ParsedScene> scenes = new ArrayList<>();
        String[] lines = outlineText.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean inSceneSection = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.contains("【场景拆分】") || line.contains("【场景】")
                    || lower.contains("[scene breakdown]") || lower.equals("[scenes]") || lower.contains("[scene list]")) {
                inSceneSection = true;
                continue;
            }
            if (inSceneSection && (line.startsWith("【") || line.startsWith("["))
                    && !line.contains("场景") && !lower.contains("scene")) {
                break;
            }

            ParsedScene parsed = parseSceneLine(line, scenes.size() + 1);
            if (parsed != null && (inSceneSection || line.startsWith("1") || line.toLowerCase(Locale.ROOT).startsWith("scene 1") || line.startsWith("场景1"))) {
                scenes.add(parsed);
            } else if (inSceneSection && line.startsWith("-") && !scenes.isEmpty()) {
                ParsedScene last = scenes.get(scenes.size() - 1);
                scenes.set(scenes.size() - 1, new ParsedScene(last.index(), last.title(), (last.raw() + " " + line).trim()));
            }
        }

        if (!scenes.isEmpty()) {
            return scenes.size() > 12 ? scenes.subList(0, 12) : scenes;
        }

        List<ParsedScene> fallback = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) continue;
            ParsedScene parsed = parseSceneLine(line, fallback.size() + 1);
            if (parsed != null) {
                fallback.add(parsed);
            }
            if (fallback.size() >= 12) break;
        }
        return fallback;
    }

    private ParsedScene parseSceneLine(String line, int fallbackIndex) {
        Matcher numbered = SCENE_NUMBERED_PATTERN.matcher(line);
        if (numbered.matches()) {
            int index = Integer.parseInt(numbered.group(1));
            String raw = numbered.group(2).trim();
            return new ParsedScene(index, shortSceneTitle(raw, index), raw);
        }

        Matcher named = SCENE_NAMED_PATTERN.matcher(line);
        if (named.matches()) {
            String sceneName = named.group(1).trim();
            String rest = named.group(2) == null ? "" : named.group(2).trim();
            String raw = rest.isBlank() ? sceneName : (sceneName + "：" + rest);
            return new ParsedScene(fallbackIndex, shortSceneTitle(raw, fallbackIndex), raw);
        }
        return null;
    }

    private String shortSceneTitle(String raw, int index) {
        if (raw == null || raw.isBlank()) {
            return "场景 " + index;
        }
        String compact = raw.replaceAll("^[-*]\\s*", "").trim();
        if (compact.length() <= 28) {
            return compact;
        }
        return compact.substring(0, 28) + "…";
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
            if (!Objects.equals(oldLine, newLine)) {
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
            item.put("type", oldLine == null ? "insert" : (newLine == null ? "delete" : "replace"));
            preview.add(item);
            if (preview.size() >= 12) {
                break;
            }
        }
        return preview;
    }

    private List<PlannedChapter> parsePlannedChapters(String volumeId,
                                                      String raw,
                                                      String volumeContent,
                                                      String language) throws IOException {
        if (raw == null || raw.isBlank()) {
            return fallbackPlannedChapters(volumeId, volumeContent, language);
        }
        JsonNode root = extractJsonNode(raw);
        JsonNode chaptersNode = root.path("chapters");
        if (!chaptersNode.isArray() || chaptersNode.isEmpty()) {
            return fallbackPlannedChapters(volumeId, volumeContent, language);
        }

        List<PlannedChapter> chapters = new ArrayList<>();
        int idx = 1;
        for (JsonNode node : chaptersNode) {
            int chapterNo = positiveInt(node, idx, "chapter_no", "chapterNo", "no", "index");
            String chapterId = readText(node, "chapter_id", "chapterId");
            if (chapterId.isBlank()) {
                chapterId = volumeId + "C" + chapterNo;
            }
            String title = readText(node, "title", "chapter_title", "chapterTitle");
            if (title.isBlank()) {
                title = defaultChapterTitle(language, chapterNo);
            }
            String summary = readText(node, "summary", "brief_summary", "briefSummary");
            String goal = readText(node, "goal", "chapter_goal", "chapterGoal");
            List<String> keyBeats = readStringList(node, "key_beats", "keyBeats", "beats");
            String endingHook = readText(node, "ending_hook", "endingHook", "hook");
            List<String> mustNotInclude = readStringList(node, "must_not_include", "mustNotInclude", "forbidden");
            String outlineContent = readText(node, "outline_content", "outlineContent", "content", "outline");
            PlannedChapter chapter = normalizePlannedChapter(
                    volumeId,
                    chapterNo,
                    chapterId,
                    title,
                    summary,
                    goal,
                    keyBeats,
                    endingHook,
                    mustNotInclude,
                    outlineContent,
                    language
            );
            chapters.add(chapter);
            idx++;
        }
        chapters.sort(Comparator.comparingInt(PlannedChapter::chapterNo));
        return chapters;
    }

    private List<PlannedChapter> parseChaptersFromRequest(String volumeId,
                                                          Object rawChapters,
                                                          String language) throws IOException {
        if (rawChapters == null) {
            return List.of();
        }
        JsonNode chaptersNode = objectMapper.valueToTree(rawChapters);
        if (!chaptersNode.isArray() || chaptersNode.isEmpty()) {
            return List.of();
        }
        List<PlannedChapter> chapters = new ArrayList<>();
        int idx = 1;
        for (JsonNode node : chaptersNode) {
            PlannedChapter chapter = normalizePlannedChapter(
                    volumeId,
                    positiveInt(node, idx, "chapter_no", "chapterNo", "no", "index"),
                    readText(node, "chapter_id", "chapterId"),
                    readText(node, "title", "chapter_title", "chapterTitle"),
                    readText(node, "summary", "brief_summary", "briefSummary"),
                    readText(node, "goal", "chapter_goal", "chapterGoal"),
                    readStringList(node, "key_beats", "keyBeats", "beats"),
                    readText(node, "ending_hook", "endingHook", "hook"),
                    readStringList(node, "must_not_include", "mustNotInclude", "forbidden"),
                    readText(node, "outline_content", "outlineContent", "content", "outline"),
                    language
            );
            chapters.add(chapter);
            idx++;
        }
        chapters.sort(Comparator.comparingInt(PlannedChapter::chapterNo));
        return chapters;
    }

    private PlannedChapter normalizePlannedChapter(String volumeId,
                                                   int chapterNo,
                                                   String chapterId,
                                                   String title,
                                                   String summary,
                                                   String goal,
                                                   List<String> keyBeats,
                                                   String endingHook,
                                                   List<String> mustNotInclude,
                                                   String outlineContent,
                                                   String language) {
        int safeNo = Math.max(1, chapterNo);
        String canonicalChapterId = canonicalize(chapterId == null || chapterId.isBlank() ? volumeId + "C" + safeNo : chapterId);
        String safeTitle = title == null || title.isBlank() ? defaultChapterTitle(language, safeNo) : title.trim();
        String safeSummary = firstNonBlank(summary, goal, safeTitle);
        List<String> safeBeats = sanitizeStringList(keyBeats);
        List<String> safeMustNotInclude = sanitizeStringList(mustNotInclude);
        String safeHook = firstNonBlank(endingHook, safeSummary);
        String safeOutline = outlineContent == null || outlineContent.isBlank()
                ? buildOutlineContent(language, safeTitle, safeSummary, goal, safeBeats, safeHook, safeMustNotInclude)
                : outlineContent.trim();
        return new PlannedChapter(
                canonicalChapterId,
                safeNo,
                safeTitle,
                safeSummary,
                goal == null ? "" : goal.trim(),
                safeBeats,
                safeHook,
                safeMustNotInclude,
                safeOutline
        );
    }

    private String buildOutlineContent(String language,
                                       String title,
                                       String summary,
                                       String goal,
                                       List<String> keyBeats,
                                       String endingHook,
                                       List<String> mustNotInclude) {
        if ("en".equals(language)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Title: ").append(title).append("\n\n");
            if (!goal.isBlank()) sb.append("Goal:\n").append(goal).append("\n\n");
            if (!summary.isBlank()) sb.append("Summary:\n").append(summary).append("\n\n");
            if (!keyBeats.isEmpty()) {
                sb.append("Key Beats:\n");
                for (String beat : keyBeats) sb.append("- ").append(beat).append("\n");
                sb.append("\n");
            }
            if (!endingHook.isBlank()) sb.append("Ending Hook:\n").append(endingHook).append("\n\n");
            if (!mustNotInclude.isEmpty()) {
                sb.append("Do Not Expand Yet:\n");
                for (String item : mustNotInclude) sb.append("- ").append(item).append("\n");
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("章节标题：").append(title).append("\n\n");
        if (!goal.isBlank()) sb.append("【章节目标】\n").append(goal).append("\n\n");
        if (!summary.isBlank()) sb.append("【章节概述】\n").append(summary).append("\n\n");
        if (!keyBeats.isEmpty()) {
            sb.append("【关键节拍】\n");
            for (String beat : keyBeats) sb.append("- ").append(beat).append("\n");
            sb.append("\n");
        }
        if (!endingHook.isBlank()) sb.append("【结尾钩子】\n").append(endingHook).append("\n\n");
        if (!mustNotInclude.isEmpty()) {
            sb.append("【本章不要提前展开】\n");
            for (String item : mustNotInclude) sb.append("- ").append(item).append("\n");
        }
        return sb.toString().trim();
    }

    private String defaultChapterTitle(String language, int chapterNo) {
        if ("en".equals(language)) {
            return "Chapter " + chapterNo;
        }
        return "第" + chapterNo + "章";
    }

    private List<PlannedChapter> fallbackPlannedChapters(String volumeId, String volumeContent, String language) {
        List<ChapterSection> sections = parseChapterSections(volumeContent);
        List<PlannedChapter> chapters = new ArrayList<>();
        if (!sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                ChapterSection section = sections.get(i);
                String summary = summarizeFallbackSection(section.body(), section.title());
                chapters.add(normalizePlannedChapter(
                        volumeId,
                        i + 1,
                        volumeId + "C" + (i + 1),
                        section.title().isBlank() ? defaultChapterTitle(language, i + 1) : section.title(),
                        summary,
                        summary,
                        extractFallbackBeats(section.body()),
                        summary,
                        List.of(),
                        section.raw().isBlank() ? buildOutlineContent(language, section.title(), summary, summary, List.of(), summary, List.of()) : section.raw(),
                        language
                ));
            }
            return chapters;
        }

        String summary = summarizeFallbackSection(volumeContent, defaultChapterTitle(language, 1));
        return List.of(normalizePlannedChapter(
                volumeId,
                1,
                volumeId + "C1",
                defaultChapterTitle(language, 1),
                summary,
                summary,
                extractFallbackBeats(volumeContent),
                summary,
                List.of(),
                buildOutlineContent(language, defaultChapterTitle(language, 1), summary, summary, extractFallbackBeats(volumeContent), summary, List.of()),
                language
        ));
    }

    private List<ChapterSection> parseChapterSections(String volumeContent) {
        List<ChapterSection> sections = new ArrayList<>();
        if (volumeContent == null || volumeContent.isBlank()) {
            return sections;
        }
        String[] lines = volumeContent.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        String currentTitle = "";
        StringBuilder currentBody = new StringBuilder();
        StringBuilder currentRaw = new StringBuilder();

        for (String line : lines) {
            Matcher matcher = CHAPTER_HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                if (currentRaw.length() > 0) {
                    sections.add(new ChapterSection(currentTitle, currentBody.toString().trim(), currentRaw.toString().trim()));
                    currentBody.setLength(0);
                    currentRaw.setLength(0);
                }
                currentTitle = matcher.group(2) == null ? "" : matcher.group(2).trim();
                currentRaw.append(line).append("\n");
                continue;
            }
            if (currentRaw.length() > 0) {
                currentBody.append(line).append("\n");
                currentRaw.append(line).append("\n");
            }
        }

        if (currentRaw.length() > 0) {
            sections.add(new ChapterSection(currentTitle, currentBody.toString().trim(), currentRaw.toString().trim()));
        }
        return sections;
    }

    private String summarizeFallbackSection(String body, String fallbackTitle) {
        String text = cleanOutlineText(body);
        if (text.isBlank()) {
            return fallbackTitle == null ? "" : fallbackTitle;
        }
        if (text.length() <= 120) {
            return text;
        }
        return text.substring(0, 120).trim() + "…";
    }

    private List<String> extractFallbackBeats(String body) {
        String normalized = cleanOutlineText(body);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> beats = new ArrayList<>();
        for (String piece : normalized.split("[。；;\\n]")) {
            String item = piece.trim();
            if (item.length() >= 6) {
                beats.add(item.length() > 36 ? item.substring(0, 36).trim() + "…" : item);
            }
            if (beats.size() >= 5) {
                break;
            }
        }
        return beats;
    }

    private String cleanOutlineText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private JsonNode extractJsonNode(String raw) throws IOException {
        String content = raw == null ? "" : raw.trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        return objectMapper.readTree(content);
    }

    private String readText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) continue;
            JsonNode child = node.get(fieldName);
            if (child != null && !child.isNull()) {
                String value = child.asText("").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private int positiveInt(JsonNode node, int fallback, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode child = node.get(fieldName);
            if (child != null && child.canConvertToInt()) {
                int value = child.asInt();
                if (value > 0) {
                    return value;
                }
            }
            if (child != null && child.isTextual()) {
                try {
                    int value = Integer.parseInt(child.asText().trim());
                    if (value > 0) return value;
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return Math.max(1, fallback);
    }

    private List<String> readStringList(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode child = node.get(fieldName);
            if (child == null || child.isNull()) continue;
            if (child.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode item : child) {
                    String value = item.asText("").trim();
                    if (!value.isBlank()) {
                        result.add(value);
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
            if (child.isTextual()) {
                String text = child.asText("");
                List<String> result = new ArrayList<>();
                for (String piece : text.split("[\\n；;]")) {
                    String value = piece.trim();
                    if (!value.isBlank()) result.add(value);
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return List.of();
    }

    private List<String> sanitizeStringList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                values.add(item.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ParsedScene(int index, String title, String raw) {}

    private record ChapterSection(String title, String body, String raw) {}

    private static final class ExplorationSession {
        String sessionId;
        String projectId;
        String language;
        String scope;
        String volumeId;
        String volumeTitle;
        String anchorChapterId;
        String anchorChapterTitle;
        String anchorSummary;
        List<String> targetChapterIds = List.of();
        String masterContent = "";
        String volumeContent = "";
        String anchorOutline = "";
        String anchorDraft = "";
        List<ChapterSummary> recentSummaries = List.of();
        List<ExplorationQuestion> questions = List.of();
        List<ExplorationDecision> decisions = List.of();
        List<ExplorationRoute> routes = List.of();
        String selectedRouteId = "";
        Map<String, Object> generatedPlan;
        String stage = "questions";
        LocalDateTime updatedAt = LocalDateTime.now();

        void touch() {
            updatedAt = LocalDateTime.now();
        }
    }

    private record ExplorationQuestionOption(String id, String label, String description) {
        Map<String, Object> toMap() {
            return Map.of(
                    "id", id,
                    "label", label,
                    "description", description
            );
        }
    }

    private record ExplorationQuestion(String id,
                                       String title,
                                       String prompt,
                                       boolean allowCustom,
                                       List<ExplorationQuestionOption> options) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("title", title);
            map.put("prompt", prompt);
            map.put("allow_custom", allowCustom);
            map.put("options", options.stream().map(ExplorationQuestionOption::toMap).toList());
            return map;
        }
    }

    private record ExplorationDecision(String questionId,
                                       String questionTitle,
                                       List<String> selectedOptionIds,
                                       String answerSummary,
                                       String customNote) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("question_id", questionId);
            map.put("question_title", questionTitle);
            map.put("selected_option_ids", selectedOptionIds);
            map.put("answer_summary", answerSummary);
            map.put("custom_note", customNote);
            return map;
        }
    }

    private record ExplorationChapterTarget(String chapterId,
                                            String title,
                                            String summary,
                                            String hook) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chapter_id", chapterId);
            map.put("title", title);
            map.put("summary", summary);
            map.put("hook", hook);
            return map;
        }
    }

    private record ExplorationRoute(String routeId,
                                    String label,
                                    String summary,
                                    String whyItFits,
                                    List<String> beats,
                                    List<String> risks,
                                    List<ExplorationChapterTarget> chapterTargets) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("route_id", routeId);
            map.put("label", label);
            map.put("summary", summary);
            map.put("why_it_fits", whyItFits);
            map.put("beats", beats);
            map.put("risks", risks);
            map.put("chapter_targets", chapterTargets.stream().map(ExplorationChapterTarget::toMap).toList());
            return map;
        }
    }

    private record VolumeOutlineCandidate(String routeId,
                                          String label,
                                          String summary,
                                          String whyItFits,
                                          List<String> focusPoints,
                                          int chapterCountHint,
                                          String outlineContent) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("route_id", routeId);
            map.put("label", label);
            map.put("summary", summary);
            map.put("why_it_fits", whyItFits);
            map.put("focus_points", focusPoints);
            map.put("chapter_count_hint", chapterCountHint);
            map.put("outline_content", outlineContent);
            return map;
        }
    }

    private record VolumeOutlineCandidateParseResult(List<VolumeOutlineCandidate> candidates,
                                                     String recommendedRouteId) {
    }

    private record MasterOutlineCandidate(String routeId,
                                          String label,
                                          String summary,
                                          String whyItFits,
                                          List<String> focusPoints,
                                          int plannedVolumeCountHint,
                                          String outlineContent) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("route_id", routeId);
            map.put("label", label);
            map.put("summary", summary);
            map.put("why_it_fits", whyItFits);
            map.put("focus_points", focusPoints);
            map.put("planned_volume_count_hint", plannedVolumeCountHint);
            map.put("outline_content", outlineContent);
            return map;
        }
    }

    private record MasterOutlineCandidateParseResult(List<MasterOutlineCandidate> candidates,
                                                     String recommendedRouteId) {
    }

    private record PlannedChapter(String chapterId,
                                  int chapterNo,
                                  String title,
                                  String summary,
                                  String goal,
                                  List<String> keyBeats,
                                  String endingHook,
                                  List<String> mustNotInclude,
                                  String outlineContent) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("chapter_id", chapterId);
            map.put("chapter_no", chapterNo);
            map.put("title", title);
            map.put("summary", summary);
            map.put("goal", goal);
            map.put("key_beats", keyBeats);
            map.put("ending_hook", endingHook);
            map.put("must_not_include", mustNotInclude);
            map.put("outline_content", outlineContent);
            return map;
        }
    }

    private record PlannedChapterSplitResult(boolean splitRequired,
                                             int recommendedSplitCount,
                                             List<String> splitReasons,
                                             String splitStrategySummary,
                                             List<PlannedChapter> chapters) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("split_required", splitRequired);
            map.put("recommended_split_count", recommendedSplitCount);
            map.put("split_reasons", splitReasons);
            map.put("split_strategy_summary", splitStrategySummary);
            map.put("chapters", chapters.stream().map(PlannedChapter::toMap).toList());
            return map;
        }
    }
}
