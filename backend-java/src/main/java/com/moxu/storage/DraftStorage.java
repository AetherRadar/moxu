package com.moxu.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.Draft;
import com.moxu.model.entity.SceneBrief;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.search.TextSearchUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 草稿存储
 */
@Slf4j
@Component
public class DraftStorage extends BaseStorage {
    
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)");
    private static final int MAX_DRAFT_PREV_BACKUPS = 3;
    private static final int TEXT_CHUNK_MAX_LEN = 420;
    private static final int TEXT_CHUNK_MIN_LEN_FACTOR_BASE = 180;
    private static final String TEXT_CHUNK_INDEX_DIR = "text_chunk_index";
    private static final String TEXT_CHUNK_INDEX_META = "meta.json";
    private static final String TEXT_CHUNK_INDEX_CHUNKS = "chunks.json";
    private static final String TEXT_CHUNK_INDEX_INVERTED = "inverted_index.json";
    private static final int TOKENIZER_VERSION = 5;
    private static final int CHUNKING_VERSION = 1;

    @Autowired(required = false)
    private ProjectLexiconResolver projectLexiconResolver;

    private volatile TextChunkIndexCache textChunkIndexCache;
    
    // ========== 草稿操作 ==========

    /** Explicit instructions win; otherwise inherit this chapter's plan, then its summary. */
    public String resolveWritingGoal(String projectId, String chapter, String explicitGoal) {
        if (explicitGoal != null && !explicitGoal.isBlank()) return explicitGoal.trim();
        String canonical = canonicalizeChapterId(chapter);
        if (!CHAPTER_ID_PATTERN.matcher(canonical).matches()) return "";
        Path outline = getProjectPath(projectId).resolve("outline/chapters").resolve(canonical + ".yaml");
        if (Files.exists(outline)) {
            try {
                Map<String, Object> data = readYamlAsMap(outline);
                String content = Objects.toString(data.get("content"), "").trim();
                if (!content.isBlank()) return content;
            } catch (IOException e) {
                log.warn("读取写作章纲失败: chapter={}, reason={}", canonical, e.getMessage());
            }
        }
        return getChapterSummary(projectId, canonical).map(ChapterSummary::getBriefSummary).orElse("");
    }

    /** Planning creates empty drafts; those summaries are not past events. */
    public List<String> writtenChaptersBefore(String projectId, String chapter) {
        String current = canonicalizeChapterId(chapter);
        return listChapters(projectId).stream()
                .filter(id -> compareChapterIds(id, current) < 0)
                .filter(id -> {
                    try { return Files.size(getFinalPath(projectId, id)) > 0; }
                    catch (IOException e) { return false; }
                }).toList();
    }
    
    public Draft saveCurrentDraft(String projectId, String chapter, String content, 
                                   Integer wordCount, List<String> pendingConfirmations,
                                   boolean createPrevBackup) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path finalPath = getFinalPath(projectId, canonical);
        Path historyDir = finalPath.getParent().resolve("history");
        
        int wc = wordCount != null ? wordCount : content.length();
        
        // 备份旧版本
        if (createPrevBackup && Files.exists(finalPath)) {
            rotateDraftHistory(finalPath, historyDir);
        }
        
        // 写入新内容
        writeText(finalPath, content);
        
        // 保存元数据
        Draft draft = Draft.builder()
                .chapter(canonical)
                .version("current")
                .content(content)
                .wordCount(wc)
                .pendingConfirmations(pendingConfirmations != null ? pendingConfirmations : new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        
        Path metaPath = finalPath.resolveSibling("final.meta.yaml");
        writeYaml(metaPath, draft);
        
        return draft;
    }
    
    public Optional<Draft> getLatestDraft(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return Optional.empty();
        }
        
        // 优先读取 final.md
        Path finalPath = draftDir.resolve("final.md");
        if (Files.exists(finalPath)) {
            return readDraftFromFile(finalPath, chapter, "current");
        }
        
        // 回退到 draft_*.md
        try (Stream<Path> paths = Files.list(draftDir)) {
            Optional<Path> latestDraft = paths
                    .filter(p -> p.getFileName().toString().matches("draft_.*\\.md"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }));
            
            if (latestDraft.isPresent()) {
                String version = latestDraft.get().getFileName().toString()
                        .replace("draft_", "").replace(".md", "");
                return readDraftFromFile(latestDraft.get(), chapter, version);
            }
        } catch (IOException e) {
            log.error("读取草稿失败: {}", draftDir, e);
        }
        
        return Optional.empty();
    }
    
    public Optional<String> getFinalDraft(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path finalPath = getProjectPath(projectId).resolve("drafts").resolve(resolved).resolve("final.md");
        
        if (Files.exists(finalPath)) {
            try {
                return Optional.of(readText(finalPath));
            } catch (IOException e) {
                log.error("读取最终稿失败: {}", finalPath, e);
            }
        }
        
        return Optional.empty();
    }
    
    public List<String> listChapters(String projectId) {
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        if (!Files.exists(draftsDir)) {
            return new ArrayList<>();
        }
        
        Set<String> seen = new HashSet<>();
        List<String> chapters = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(draftsDir)) {
            paths.filter(Files::isDirectory)
                    .forEach(p -> {
                        String canonical = canonicalizeChapterId(p.getFileName().toString());
                        if (!canonical.isEmpty() && !seen.contains(canonical)) {
                            seen.add(canonical);
                            chapters.add(canonical);
                        }
                    });
        } catch (IOException e) {
            log.error("列出章节失败: {}", draftsDir, e);
        }
        
        // 按章节 ID 排序
        chapters.sort(this::compareChapterIds);
        return chapters;
    }
    
    public boolean deleteChapter(String projectId, String chapter) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        boolean deleted = false;
        
        // 删除草稿目录
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        if (Files.exists(draftsDir)) {
            try (Stream<Path> paths = Files.list(draftsDir)) {
                for (Path p : paths.filter(Files::isDirectory).toList()) {
                    if (canonicalizeChapterId(p.getFileName().toString()).equals(canonical)) {
                        deleteDirectory(p);
                        deleted = true;
                    }
                }
            }
        }
        
        // 删除摘要文件
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        if (Files.exists(summariesDir)) {
            try (Stream<Path> paths = Files.list(summariesDir)) {
                for (Path p : paths.filter(f -> f.toString().endsWith("_summary.yaml")).toList()) {
                    String name = p.getFileName().toString().replace("_summary.yaml", "");
                    if (canonicalizeChapterId(name).equals(canonical)) {
                        Files.delete(p);
                        deleted = true;
                    }
                }
            }
        }
        
        return deleted;
    }

    /**
     * 清空章节生成产物，但保留章节目录壳，避免前端章节列表直接消失。
     * 会删除 drafts/{chapter}/ 下的所有文件与子目录，然后重新确保目录存在。
     */
    public void resetChapterDraftKeepingShell(String projectId, String chapter) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(canonical);

        if (Files.exists(draftDir)) {
            try (Stream<Path> entries = Files.list(draftDir)) {
                for (Path entry : entries.toList()) {
                    deleteDirectory(entry);
                }
            }
        }

        ensureDir(draftDir);
    }
    
    // ========== 场景简报 ==========
    
    public void saveSceneBrief(String projectId, String chapter, SceneBrief brief) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(canonical).resolve("scene_brief.yaml");
        writeYaml(filePath, brief);
    }
    
    public Optional<SceneBrief> getSceneBrief(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(resolved).resolve("scene_brief.yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(readYaml(filePath, SceneBrief.class));
        } catch (IOException e) {
            log.error("读取场景简报失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    // ========== 草稿版本管理 ==========
    
    /**
     * 列出草稿版本
     */
    public List<String> listDraftVersions(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return new ArrayList<>();
        }
        
        List<String> versions = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(draftDir)) {
            paths.filter(p -> p.getFileName().toString().matches("draft_v\\d+\\.md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String version = name.replace("draft_", "").replace(".md", "");
                        versions.add(version);
                    });
        } catch (IOException e) {
            log.error("列出草稿版本失败: {}", draftDir, e);
        }
        
        // 如果有 final.md，添加 current 版本
        if (Files.exists(draftDir.resolve("final.md"))) {
            if (versions.isEmpty()) {
                versions.add("v1");
            }
        }
        
        return versions;
    }
    
    /**
     * 获取指定版本的草稿
     */
    public Optional<Draft> getDraft(String projectId, String chapter, String version) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return Optional.empty();
        }
        
        // 尝试读取指定版本
        Path versionPath = draftDir.resolve("draft_" + version + ".md");
        if (Files.exists(versionPath)) {
            return readDraftFromFile(versionPath, chapter, version);
        }
        
        // 回退到 final.md
        Path finalPath = draftDir.resolve("final.md");
        if (Files.exists(finalPath)) {
            return readDraftFromFile(finalPath, chapter, version);
        }
        
        return Optional.empty();
    }
    
    /**
     * 保存最终稿
     */
    public void saveFinalDraft(String projectId, String chapter, String content) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path finalPath = getFinalPath(projectId, canonical);
        
        // 备份旧版本
        Path historyDir = finalPath.getParent().resolve("history");
        if (Files.exists(finalPath)) {
            rotateDraftHistory(finalPath, historyDir);
        }
        
        // 写入最终稿
        writeText(finalPath, content);
        
        // 更新元数据
        Draft draft = Draft.builder()
                .chapter(canonical)
                .version("final")
                .content(content)
                .wordCount(content.length())
                .createdAt(LocalDateTime.now())
                .build();
        
        Path metaPath = finalPath.resolveSibling("final.meta.yaml");
        writeYaml(metaPath, draft);
    }

    public List<Map<String, Object>> searchTextChunks(String projectId, String query, int limit,
                                                      List<String> chapters, List<String> excludeChapters,
                                                      boolean rebuild) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (limit <= 0) {
            return List.of();
        }

        ProjectLexicon projectLexicon = resolveProjectLexicon(projectId);
        if (rebuild) {
            try {
                rebuildTextChunkIndex(projectId, projectLexicon);
            } catch (Exception e) {
                log.warn("rebuildTextChunkIndex failed (continuing with fallback scan): {}", e.getMessage());
            }
        }

        try {
            Optional<TextChunkIndex> indexOpt = loadTextChunkIndex(projectId, projectLexicon);
            if (indexOpt.isPresent()) {
                return searchTextChunksWithIndex(indexOpt.get(), query, limit, chapters, excludeChapters, projectLexicon);
            }
        } catch (Exception e) {
            log.warn("searchTextChunks index path failed, fallback to scan: {}", e.getMessage());
        }

        Set<String> includeSet = normalizeChapterSet(chapters);
        Set<String> excludeSet = normalizeChapterSet(excludeChapters);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String chapter : listChapters(projectId)) {
            String canonical = canonicalizeChapterId(chapter);
            if (!includeSet.isEmpty() && !includeSet.contains(canonical)) {
                continue;
            }
            if (excludeSet.contains(canonical)) {
                continue;
            }

            Optional<String> draftOpt = getFinalDraft(projectId, canonical);
            if (draftOpt.isEmpty()) {
                continue;
            }

            List<String> chunks = splitIntoChunks(draftOpt.get());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                double score = scoreChunk(query, chunk, projectLexicon);
                if (score <= 0) {
                    continue;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chapter", canonical);
                item.put("text", chunk);
                item.put("score", score);
                item.put("chunk_index", i);
                item.put("source", Map.of(
                        "chapter", canonical,
                        "chunk_index", i,
                        "field", "final_draft"
                ));
                results.add(item);
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    public Map<String, Object> rebuildTextChunkIndex(String projectId) {
        return rebuildTextChunkIndex(projectId, resolveProjectLexicon(projectId));
    }

    private Map<String, Object> rebuildTextChunkIndex(String projectId, ProjectLexicon projectLexicon) {
        long startedAt = System.currentTimeMillis();
        int chaptersProcessed = 0;
        int chunkTotal = 0;
        long totalDocLen = 0;
        Map<String, ChunkMeta> chunkById = new LinkedHashMap<>();
        Map<String, List<String>> inverted = new LinkedHashMap<>();

        for (String chapter : listChapters(projectId)) {
            String canonical = canonicalizeChapterId(chapter);
            Optional<String> draftOpt = getFinalDraft(projectId, canonical);
            if (draftOpt.isEmpty()) {
                continue;
            }
            chaptersProcessed++;
            List<String> chunks = splitIntoChunks(draftOpt.get());
            chunkTotal += chunks.size();

            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String chunkId = chunkId(canonical, i);
                int len = text.length();
                int docLen = TextSearchUtils.estimateDocLength(text, projectLexicon);
                double lengthFactor = Math.min(1.0, len / (double) TEXT_CHUNK_MIN_LEN_FACTOR_BASE);

                ChunkMeta meta = new ChunkMeta(chunkId, canonical, i, text, len, lengthFactor, docLen);
                chunkById.put(chunkId, meta);
                totalDocLen += docLen;

                Set<String> terms = TextSearchUtils.tokenSet(text, projectLexicon);
                for (String term : terms) {
                    inverted.computeIfAbsent(term, ignored -> new ArrayList<>()).add(chunkId);
                }
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("project_id", projectId);
        meta.put("built_at", startedAt);
        meta.put("chunking_version", CHUNKING_VERSION);
        meta.put("tokenizer_version", TOKENIZER_VERSION);
        meta.put("lexicon_fingerprint", projectLexicon.fingerprint());
        meta.put("lexicon_term_count", projectLexicon.size());
        meta.put("chapters_processed", chaptersProcessed);
        meta.put("chunks_total", chunkById.size());
        meta.put("terms_total", inverted.size());
        meta.put("avg_doc_len", chunkById.isEmpty() ? 1.0 : totalDocLen / (double) chunkById.size());
        meta.put("elapsed_ms", Math.max(0, System.currentTimeMillis() - startedAt));

        try {
            Path dir = textChunkIndexDir(projectId);
            ensureDir(dir);
            writeJson(dir.resolve(TEXT_CHUNK_INDEX_META), meta);
            writeJson(dir.resolve(TEXT_CHUNK_INDEX_CHUNKS), chunkById);
            writeJson(dir.resolve(TEXT_CHUNK_INDEX_INVERTED), inverted);
            // 更新缓存
            textChunkIndexCache = null;
        } catch (IOException e) {
            log.error("写入 text_chunk_index 失败", e);
        }

        return meta;
    }
    
    // ========== 章节摘要 ==========
    
    public void saveChapterSummary(String projectId, ChapterSummary summary) throws IOException {
        String canonical = canonicalizeChapterId(summary.getChapter());
        summary.setChapter(canonical);
        
        if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
            summary.setVolumeId(extractVolumeId(canonical));
        }
        
        if (summary.getOrderIndex() == null) {
            getChapterSummary(projectId, canonical).ifPresent(existing -> {
                if (existing.getOrderIndex() != null) {
                    summary.setOrderIndex(existing.getOrderIndex());
                }
            });
        }
        
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve(canonical + "_summary.yaml");
        writeYaml(filePath, summary);
    }
    
    public Optional<ChapterSummary> getChapterSummary(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = resolveSummaryPath(projectId, chapter);
        
        if (filePath == null || !Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            ChapterSummary summary = readYaml(filePath, ChapterSummary.class);
            summary.setChapter(canonical);
            if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
                summary.setVolumeId(extractVolumeId(canonical));
            }
            return Optional.of(summary);
        } catch (IOException e) {
            log.error("读取章节摘要失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    public boolean deleteChapterSummary(String projectId, String chapter) {
        Path filePath = resolveSummaryPath(projectId, chapter);
        try {
            return filePath != null && Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除章节摘要失败: {}", filePath, e);
            return false;
        }
    }
    
        public List<ChapterSummary> listChapterSummaries(String projectId, String volumeId) {
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        if (!Files.exists(summariesDir)) {
            return new ArrayList<>();
        }
        
        Map<String, ChapterSummary> summaries = new HashMap<>();
        
        try (Stream<Path> paths = Files.list(summariesDir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith("_summary.yaml")).toList()) {
                try {
                    ChapterSummary summary = readYaml(p, ChapterSummary.class);
                    String canonical = canonicalizeChapterId(
                            summary.getChapter() != null ? summary.getChapter() : 
                            p.getFileName().toString().replace("_summary.yaml", ""));
                    summary.setChapter(canonical);
                    
                    if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
                        summary.setVolumeId(extractVolumeId(canonical));
                    }
                    
                    if (volumeId != null && !volumeId.equals(summary.getVolumeId())) {
                        continue;
                    }
                    
                    summaries.put(canonical, summary);
                } catch (IOException e) {
                    log.error("读取章节摘要失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出章节摘要失败: {}", summariesDir, e);
        }
        
        // 分卷自愈：确保每一分卷内的 order_index 是 0..N-1 连续且唯一的整数
        Map<String, List<ChapterSummary>> groupedByVol = new HashMap<>();
        for (ChapterSummary s : summaries.values()) {
            groupedByVol.computeIfAbsent(s.getVolumeId(), k -> new ArrayList<>()).add(s);
        }
        
        for (Map.Entry<String, List<ChapterSummary>> entry : groupedByVol.entrySet()) {
            List<ChapterSummary> volSummaries = entry.getValue();
            int n = volSummaries.size();
            boolean isValid = true;
            boolean[] seen = new boolean[n];
            for (ChapterSummary s : volSummaries) {
                Integer idx = s.getOrderIndex();
                if (idx == null || idx < 0 || idx >= n || seen[idx]) {
                    isValid = false;
                    break;
                }
                seen[idx] = true;
            }
            
            if (!isValid) {
                boolean hasDuplicates = false;
                Set<Integer> nonNullIndices = new HashSet<>();
                for (ChapterSummary s : volSummaries) {
                    Integer idx = s.getOrderIndex();
                    if (idx != null) {
                        if (nonNullIndices.contains(idx)) {
                            hasDuplicates = true;
                            break;
                        }
                        nonNullIndices.add(idx);
                    }
                }
                
                if (hasDuplicates) {
                    // 存在重复（如 V1C1 和 V1C8 都是 0），使用章节 ID 自然排序作为基准自愈
                    volSummaries.sort((a, b) -> compareChapterIds(a.getChapter(), b.getChapter()));
                } else {
                    // 没有重复，但可能存在 null 或不连续，保留已有 orderIndex 相对顺序，并将 null 的章节追加到最后
                    volSummaries.sort((a, b) -> {
                        Integer idxA = a.getOrderIndex();
                        Integer idxB = b.getOrderIndex();
                        if (idxA != null && idxB != null) {
                            return idxA.compareTo(idxB);
                        } else if (idxA != null) {
                            return -1;
                        } else if (idxB != null) {
                            return 1;
                        } else {
                            return compareChapterIds(a.getChapter(), b.getChapter());
                        }
                    });
                }
                
                // 重新分配连续的 orderIndex 并持久化
                for (int i = 0; i < volSummaries.size(); i++) {
                    ChapterSummary s = volSummaries.get(i);
                    s.setOrderIndex(i);
                    try {
                        saveChapterSummary(projectId, s);
                    } catch (IOException e) {
                        log.error("自愈章节顺序失败: {}", s.getChapter(), e);
                    }
                }
            }
        }
        
        List<ChapterSummary> result = new ArrayList<>(summaries.values());
        result.sort((a, b) -> {
            int volCmp = compareVolumeIds(a.getVolumeId(), b.getVolumeId());
            if (volCmp != 0) return volCmp;
            
            // 对齐前端排序逻辑：如果全卷都有 orderIndex 则按 orderIndex 排序，否则退化为 compareChapterIds
            boolean hasCompleteCustomOrder = true;
            String volId = a.getVolumeId();
            for (ChapterSummary s : summaries.values()) {
                if (Objects.equals(s.getVolumeId(), volId) && s.getOrderIndex() == null) {
                    hasCompleteCustomOrder = false;
                    break;
                }
            }
            
            if (hasCompleteCustomOrder) {
                int cmp = a.getOrderIndex().compareTo(b.getOrderIndex());
                if (cmp != 0) return cmp;
            }
            
            return compareChapterIds(a.getChapter(), b.getChapter());
        });
        
        return result;
    }
    
    // ========== 冲突报告 ==========

    /**
     * 保存冲突报告（对齐 Python draft_storage.save_conflict_report）
     * 存储路径：data/{projectId}/drafts/{chapter}/conflict_report.yaml
     */
    public void saveConflictReport(String projectId, String chapter, Map<String, Object> report) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(canonical).resolve("conflict_report.yaml");
        writeYaml(filePath, report);
    }

    public void saveAnalysisReview(String projectId, String chapter, Map<String, Object> review) throws IOException {
        writeYaml(getProjectPath(projectId).resolve("drafts").resolve(canonicalizeChapterId(chapter))
                .resolve("analysis_review.yaml"), review);
    }

    public void saveArchiveStatus(String projectId, String chapter, Map<String, Object> status) throws IOException {
        writeYaml(getProjectPath(projectId).resolve("drafts").resolve(canonicalizeChapterId(chapter))
                .resolve("archive_status.yaml"), status);
    }

    public Map<String, Object> getArchiveStatus(String projectId, String chapter) throws IOException {
        Path path = getProjectPath(projectId).resolve("drafts").resolve(canonicalizeChapterId(chapter))
                .resolve("archive_status.yaml");
        return Files.exists(path) ? readYamlAsMap(path) : Map.of("status", "not_run", "chapter", chapter);
    }

    // ========== 分卷摘要 ==========

    /**
     * 保存分卷摘要（对齐 Python draft_storage.volume_storage.save_volume_summary）
     * 存储路径：data/{projectId}/summaries/volumes/{volumeId}_summary.yaml
     */
    public void saveVolumeSummary(String projectId, Map<String, Object> summary) throws IOException {
        String volumeId = (String) summary.getOrDefault("volume_id", "V1");
        if (volumeId == null || volumeId.isBlank()) volumeId = "V1";
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve("volumes")
                .resolve(volumeId + "_summary.yaml");
        writeYaml(filePath, summary);
    }

    /**
     * 读取分卷摘要
     */
    public Optional<Map<String, Object>> getVolumeSummary(String projectId, String volumeId) {
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve("volumes")
                .resolve(volumeId + "_summary.yaml");
        if (!Files.exists(filePath)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = readYaml(filePath, Map.class);
            return Optional.ofNullable(summary);
        } catch (IOException e) {
            log.error("读取分卷摘要失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    public boolean deleteVolumeSummary(String projectId, String volumeId) {
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve("volumes")
                .resolve(volumeId + "_summary.yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除分卷摘要失败: {}", filePath, e);
            return false;
        }
    }

    // ========== 辅助方法 ==========

    private Path getFinalPath(String projectId, String chapter) {        return getProjectPath(projectId).resolve("drafts").resolve(chapter).resolve("final.md");
    }
    
    private String resolveChapterDirName(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        
        if (Files.exists(draftsDir)) {
            // 先尝试规范化名称
            if (Files.exists(draftsDir.resolve(canonical))) {
                return canonical;
            }
            // 再尝试原始名称
            if (Files.exists(draftsDir.resolve(chapter))) {
                return chapter;
            }
            // 遍历查找
            try (Stream<Path> paths = Files.list(draftsDir)) {
                for (Path p : paths.filter(Files::isDirectory).toList()) {
                    if (canonicalizeChapterId(p.getFileName().toString()).equals(canonical)) {
                        return p.getFileName().toString();
                    }
                }
            } catch (IOException e) {
                log.error("解析章节目录失败", e);
            }
        }
        
        return canonical;
    }
    
    private Path resolveSummaryPath(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        
        if (Files.exists(summariesDir)) {
            Path canonicalPath = summariesDir.resolve(canonical + "_summary.yaml");
            if (Files.exists(canonicalPath)) {
                return canonicalPath;
            }
            
            Path rawPath = summariesDir.resolve(chapter + "_summary.yaml");
            if (Files.exists(rawPath)) {
                return rawPath;
            }
        }
        
        return summariesDir.resolve(canonical + "_summary.yaml");
    }
    
    private String canonicalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "";
        }
        
        String normalized = chapterId.trim().toUpperCase();
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        
        return chapterId.trim();
    }
    
    private String extractVolumeId(String chapterId) {
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (matcher.matches()) {
            return "V" + matcher.group(1);
        }
        return "V1";
    }
    
    private int compareChapterIds(String a, String b) {
        Matcher ma = CHAPTER_ID_PATTERN.matcher(a);
        Matcher mb = CHAPTER_ID_PATTERN.matcher(b);
        
        if (ma.matches() && mb.matches()) {
            int volA = Integer.parseInt(ma.group(1));
            int volB = Integer.parseInt(mb.group(1));
            if (volA != volB) return Integer.compare(volA, volB);
            
            int chA = Integer.parseInt(ma.group(2));
            int chB = Integer.parseInt(mb.group(2));
            return Integer.compare(chA, chB);
        }
        
        return a.compareTo(b);
    }
    
    private int compareVolumeIds(String a, String b) {
        try {
            int volA = Integer.parseInt(a.substring(1));
            int volB = Integer.parseInt(b.substring(1));
            return Integer.compare(volA, volB);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }
    
    private Optional<Draft> readDraftFromFile(Path filePath, String chapter, String version) {
        try {
            String content = readText(filePath);
            String canonical = canonicalizeChapterId(chapter);
            
            // 尝试读取元数据
            Path metaPath = filePath.resolveSibling(filePath.getFileName().toString().replace(".md", ".meta.yaml"));
            if (Files.exists(metaPath)) {
                Draft draft = readYaml(metaPath, Draft.class);
                draft.setChapter(canonical);
                draft.setContent(content);
                return Optional.of(draft);
            }
            
            return Optional.of(Draft.builder()
                    .chapter(canonical)
                    .version(version)
                    .content(content)
                    .wordCount(content.length())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (IOException e) {
            log.error("读取草稿文件失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    private void rotateDraftHistory(Path finalPath, Path historyDir) throws IOException {
        ensureDir(historyDir);
        
        String ts = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        Path backupPath = historyDir.resolve("final_" + ts + ".md");
        
        Files.move(finalPath, backupPath);
        
        // 清理旧备份
        try (Stream<Path> paths = Files.list(historyDir)) {
            List<Path> backups = paths
                    .filter(p -> p.getFileName().toString().startsWith("final_") && p.toString().endsWith(".md"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .toList();
            
            while (backups.size() > MAX_DRAFT_PREV_BACKUPS) {
                Files.deleteIfExists(backups.get(0));
                backups = backups.subList(1, backups.size());
            }
        }
    }
    
    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectory(entry);
                }
            }
        }
        Files.delete(path);
    }

    private Set<String> normalizeChapterSet(List<String> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String chapter : chapters) {
            String canonical = canonicalizeChapterId(chapter);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return normalized;
    }

    private List<String> splitIntoChunks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\R\\s*\\R+");
        StringBuilder current = new StringBuilder();
        for (String raw : paragraphs) {
            String paragraph = raw.trim();
            if (paragraph.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() + 2 > TEXT_CHUNK_MAX_LEN) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private double scoreChunk(String query, String chunk, ProjectLexicon projectLexicon) {
        return TextSearchUtils.calculateHybridScore(query, chunk, null, projectLexicon);
    }

    private Set<String> tokenize(String text, ProjectLexicon projectLexicon) {
        return TextSearchUtils.tokenSet(text, projectLexicon);
    }

    private Path textChunkIndexDir(String projectId) {
        return getProjectPath(projectId).resolve(TEXT_CHUNK_INDEX_DIR);
    }

    private Optional<TextChunkIndex> loadTextChunkIndex(String projectId, ProjectLexicon projectLexicon) {
        Path dir = textChunkIndexDir(projectId);
        Path metaPath = dir.resolve(TEXT_CHUNK_INDEX_META);
        Path chunksPath = dir.resolve(TEXT_CHUNK_INDEX_CHUNKS);
        Path invertedPath = dir.resolve(TEXT_CHUNK_INDEX_INVERTED);
        if (!Files.exists(metaPath) || !Files.exists(chunksPath) || !Files.exists(invertedPath)) {
            return Optional.empty();
        }

        long metaMtime = lastModified(metaPath);
        long chunksMtime = lastModified(chunksPath);
        long invertedMtime = lastModified(invertedPath);
        long stamp = Math.max(metaMtime, Math.max(chunksMtime, invertedMtime));

        TextChunkIndexCache cache = this.textChunkIndexCache;
        if (cache != null && cache.projectId.equals(projectId) && cache.lastModified == stamp) {
            return Optional.of(cache.index);
        }

        try {
            Map<String, Object> meta = jsonMapper.readValue(Files.readString(metaPath), new TypeReference<>() {});
            int tokenizerVersion = intValue(meta.get("tokenizer_version"), -1);
            int chunkingVersion = intValue(meta.get("chunking_version"), -1);
            if (tokenizerVersion != TOKENIZER_VERSION || chunkingVersion != CHUNKING_VERSION) {
                return Optional.empty();
            }
            String lexiconFingerprint = stringValue(meta.get("lexicon_fingerprint"), "empty");
            if (!Objects.equals(lexiconFingerprint, projectLexicon.fingerprint())) {
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            Map<String, ChunkMeta> chunks = jsonMapper.readValue(
                    Files.readString(chunksPath),
                    new TypeReference<Map<String, ChunkMeta>>() {}
            );
            Map<String, List<String>> inverted = jsonMapper.readValue(
                    Files.readString(invertedPath),
                    new TypeReference<Map<String, List<String>>>() {}
            );

            TextChunkIndex index = new TextChunkIndex(meta, chunks != null ? chunks : Map.of(),
                    inverted != null ? inverted : Map.of());
            this.textChunkIndexCache = new TextChunkIndexCache(projectId, stamp, index);
            return Optional.of(index);
        } catch (Exception e) {
            log.warn("读取 text_chunk_index 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private String chunkId(String chapter, int chunkIndex) {
        return "chunk:" + chapter + ":" + chunkIndex;
    }

    private List<Map<String, Object>> searchTextChunksWithIndex(TextChunkIndex index, String query, int limit,
                                                                List<String> chapters, List<String> excludeChapters,
                                                                ProjectLexicon projectLexicon) {
        Map<String, Double> queryWeights = TextSearchUtils.queryTokenWeights(query, projectLexicon);
        Set<String> queryTerms = queryWeights.keySet();
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        double totalQueryWeight = queryWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalQueryWeight <= 0.0) {
            return List.of();
        }

        Set<String> includeSet = normalizeChapterSet(chapters);
        Set<String> excludeSet = normalizeChapterSet(excludeChapters);

        Map<String, Double> overlap = new HashMap<>();
        Map<String, Integer> df = new LinkedHashMap<>();
        for (String term : queryTerms) {
            List<String> postings = index.inverted.get(term);
            if (postings == null || postings.isEmpty()) {
                df.put(term, 0);
                continue;
            }
            df.put(term, postings.size());
            double termWeight = queryWeights.getOrDefault(term, 1.0);
            for (String chunkId : postings) {
                overlap.merge(chunkId, termWeight, Double::sum);
            }
        }

        int totalDocs = Math.max(index.chunks.size(), 1);
        double avgDocLen = doubleValue(index.meta.get("avg_doc_len"), 1.0);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        PriorityQueue<ScoredChunk> heap = new PriorityQueue<>(Comparator.comparingDouble(a -> a.score));
        for (Map.Entry<String, Double> entry : overlap.entrySet()) {
            String chunkId = entry.getKey();
            double ov = entry.getValue();
            if (ov <= 0.0) continue;
            ChunkMeta meta = index.chunks.get(chunkId);
            if (meta == null) continue;
            String chapter = meta.chapter;
            if (!includeSet.isEmpty() && !includeSet.contains(chapter)) continue;
            if (excludeSet.contains(chapter)) continue;

            double overlapScore = ov / totalQueryWeight;
            if (!normalizedQuery.isBlank() && meta.text.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                overlapScore = Math.min(1.0, overlapScore + 0.25);
            }
            double bm25 = TextSearchUtils.calculateBm25Score(
                    meta.text,
                    queryTerms,
                    df,
                    totalDocs,
                    avgDocLen,
                    meta.docLen,
                    queryWeights
            );
            double score = overlapScore * 0.35 + bm25 * 0.65;
            if (score <= 0) continue;

            if (heap.size() < limit) {
                heap.add(new ScoredChunk(chunkId, score));
            } else if (score > heap.peek().score) {
                heap.poll();
                heap.add(new ScoredChunk(chunkId, score));
            }
        }

        List<ScoredChunk> top = new ArrayList<>(heap);
        top.sort((a, b) -> Double.compare(b.score, a.score));
        List<Map<String, Object>> results = new ArrayList<>();
        for (ScoredChunk sc : top) {
            ChunkMeta meta = index.chunks.get(sc.chunkId);
            if (meta == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapter", meta.chapter);
            item.put("text", meta.text);
            item.put("score", sc.score);
            item.put("chunk_index", meta.chunkIndex);
            item.put("source", Map.of(
                    "chapter", meta.chapter,
                    "chunk_index", meta.chunkIndex,
                    "field", "final_draft"
            ));
            results.add(item);
        }
        return results;
    }

    private ProjectLexicon resolveProjectLexicon(String projectId) {
        if (projectLexiconResolver == null) {
            return ProjectLexicon.empty();
        }
        return projectLexiconResolver.resolve(projectId);
    }

    public record ChunkMeta(String chunkId, String chapter, int chunkIndex, String text, int chunkLen,
                            double lengthFactor, int docLen) {
    }

    private record TextChunkIndex(Map<String, Object> meta, Map<String, ChunkMeta> chunks,
                                  Map<String, List<String>> inverted) {
    }

    private record TextChunkIndexCache(String projectId, long lastModified, TextChunkIndex index) {
    }

    private record ScoredChunk(String chunkId, double score) {
    }
}
