package com.moxu.analysis;

import com.moxu.agent.ArchivistAgent;
import com.moxu.llm.LlmGateway;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.orchestrator.Orchestrator;
import com.moxu.orchestrator.OrchestratorPool;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durable candidates are separate from canon. Only sync/save operations apply them. */
@Service
public class AnalysisJobService {
    private static final String PROMPT_VERSION = "analysis-v2-continuity";
    private final AnalysisStorage storage;
    private final DraftStorage drafts;
    private final MemoryPackStorage memory;
    private final OrchestratorPool pool;
    private final LlmGateway gateway;
    private final ArchivistAgent archivist;
    private final AnalysisProperties defaults;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, AnalysisContext.Account> active = new ConcurrentHashMap<>();

    public AnalysisJobService(AnalysisStorage storage, DraftStorage drafts, MemoryPackStorage memory,
                              OrchestratorPool pool, LlmGateway gateway, ArchivistAgent archivist,
                              AnalysisProperties defaults) {
        this.storage = storage; this.drafts = drafts; this.memory = memory; this.pool = pool;
        this.gateway = gateway; this.archivist = archivist; this.defaults = defaults;
    }

    public Map<String, Object> run(String project, AnalysisJobRequest request, boolean apply) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A project analysis is already running");
        try { return runLocked(project, request, apply); }
        finally { lock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runLocked(String project, AnalysisJobRequest request, boolean apply) throws IOException {
        String id;
        try { id = request.getJobId() == null ? UUID.randomUUID().toString() : UUID.fromString(request.getJobId()).toString(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job_id"); }
        Map<String, Object> previous = storage.job(project, id);
        if (!previous.isEmpty()) {
            if (request.getCombined() == null && previous.get("combined") instanceof Boolean value) request.setCombined(value);
            if (request.getBulkMode() == null && previous.get("bulk_mode") instanceof Boolean value) request.setBulkMode(value);
            if (request.getBookSummary() == null && previous.get("book_summary") instanceof Boolean value) request.setBookSummary(value);
            if (request.getMaxTokens() == null && previous.get("max_tokens") instanceof Number value) request.setMaxTokens(value.longValue());
            if (request.getMaxMinutes() == null && previous.get("max_minutes") instanceof Number value) request.setMaxMinutes(value.intValue());
            if (request.getMaxCallsPerChapter() == null && previous.get("max_calls_per_chapter") instanceof Number value)
                request.setMaxCallsPerChapter(value.intValue());
        }
        List<String> chapters = request.getChapters() == null
                ? previous.get("chapters") instanceof List<?> saved ? saved.stream().map(Object::toString).toList()
                : drafts.listChapters(project) : request.getChapters();
        chapters = chapters.stream().map(AnalysisStorage::chapter).distinct().sorted(AnalysisJobService::compare).toList();
        if (request.getFromChapter() != null) {
            String from = AnalysisStorage.chapter(request.getFromChapter());
            chapters = chapters.stream().filter(ch -> compare(ch, from) >= 0).toList();
        }
        if (chapters.size() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 2000 chapters");
        AnalysisProperties options = options(request);
        String language = request.getLanguage() == null ? Objects.toString(previous.get("language"), "zh") : request.getLanguage();
        Map<String, Object> signature = Map.of("chapters", chapters, "apply", apply, "language", language,
                "combined", options.isCombined(), "bulk_mode", options.isBulkMode(),
                "book_summary", Boolean.TRUE.equals(request.getBookSummary()));
        if (!previous.isEmpty() && !storage.fingerprint(signature).equals(previous.get("signature")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Resume must keep chapter scope and analysis mode unchanged");
        Orchestrator orchestrator = pool.getOrchestrator(project);
        orchestrator.setLanguage(language);
        var account = new AnalysisContext.Account(id, options.getMaxTokensPerJob(), options.getMaxMinutesPerJob(),
                options.getMaxCallsPerChapter(), storage.spent(project, id),
                previous.get("elapsed_ms") instanceof Number elapsed ? elapsed.longValue() : 0,
                event -> storage.event(project, event));
        active.put(project + "/" + id, account);
        long started = System.nanoTime();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("job_id", id); job.put("chapters", chapters); job.put("language", language);
        job.put("signature", storage.fingerprint(signature)); job.put("mode", apply ? "sync" : "preview");
        job.put("combined", options.isCombined()); job.put("bulk_mode", options.isBulkMode());
        job.put("project_id", project); job.put("model_profile_hash", gateway.analysisFingerprint());
        job.put("book_summary", Boolean.TRUE.equals(request.getBookSummary()));
        job.put("max_tokens", options.getMaxTokensPerJob()); job.put("max_minutes", options.getMaxMinutesPerJob());
        job.put("max_calls_per_chapter", options.getMaxCallsPerChapter());
        job.put("status", "running"); job.put("created_at", previous.getOrDefault("created_at", Instant.now().toString()));
        job.put("previous_elapsed_ms", previous.getOrDefault("elapsed_ms", 0));
        List<Map<String, Object>> results = new ArrayList<>();
        job.put("results", results);
        Set<String> volumes = new LinkedHashSet<>();
        try {
            checkpoint(project, id, job, account, started);
            for (String chapter : chapters) {
                long chapterStarted = System.nanoTime();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chapter", chapter);
                job.put("next_chapter", chapter);
                checkpoint(project, id, job, account, started);
                try (var ignored = new AnalysisContext(account, chapter, "analysis", options)) {
                    account.check();
                    String content = content(project, chapter);
                    if (content.isBlank()) {
                        row.put("success", false); row.put("error", "No draft content");
                    } else {
                        String title = title(project, chapter);
                        String sourceHash = AnalysisStorage.hash(content);
                        row.put("source_hash", sourceHash);
                        row.put("title_hash", AnalysisStorage.hash(title));
                        String fingerprint = storage.fingerprint(Map.of("source", sourceHash, "title", title,
                                "model", gateway.analysisFingerprint(), "language", language, "prompt", PROMPT_VERSION,
                                "combined", options.isCombined(), "compact", options.isCompactPrompt(),
                                "output_tokens", options.getMaxOutputTokens(), "fallback", options.isLegacyFallback(),
                                "cards", storage.cardsFingerprint(project)));
                        Map<String, Object> entry = storage.entry(project, chapter);
                        Map<String, Object> analysis = Map.of();
                        boolean resumeCompleted = !previous.isEmpty() && previous.get("results") instanceof List<?> old
                                && old.stream().anyMatch(value -> value instanceof Map<?, ?> r
                                && chapter.equals(r.get("chapter")) && Boolean.TRUE.equals(r.get("success")));
                        if (defaults.isIncremental() && (!Boolean.TRUE.equals(request.getForce()) || resumeCompleted)
                                && fingerprint.equals(entry.get("fingerprint"))) {
                            analysis = storage.result(project, chapter);
                        }
                        boolean skipped = !analysis.isEmpty();
                        if (!skipped) {
                            Map<String, Object> generated = orchestrator.analyzeChapter(project, chapter, content, title);
                            if (!Boolean.TRUE.equals(generated.get("success"))) {
                                if (!account.stopReason().isEmpty()) throw new AnalysisContext.Paused(account.stopReason());
                                throw new IllegalStateException("Analysis failed; previous canon was not changed");
                            }
                            analysis = new LinkedHashMap<>((Map<String, Object>) generated.get("analysis"));
                            analysis.put("_source_hash", sourceHash);
                            analysis.put("_title_hash", AnalysisStorage.hash(title));
                            analysis.put("_fingerprint", fingerprint);
                            analysis.put("_bulk_mode", options.isBulkMode());
                            storage.result(project, chapter, analysis);
                            Map<String, Object> manifest = new LinkedHashMap<>();
                            manifest.put("fingerprint", fingerprint); manifest.put("content_hash", sourceHash);
                            manifest.put("title_hash", AnalysisStorage.hash(title)); manifest.put("status", "ready");
                            manifest.put("summary_hash", storage.fingerprint(analysis.get("summary")));
                            manifest.put("canon_hash", storage.fingerprint(analysis.get("facts")));
                            manifest.put("model_profile_hash", gateway.analysisFingerprint());
                            manifest.put("last_analyzed_at", Instant.now().toString());
                            storage.entry(project, chapter, manifest);
                        }
                        row.put("skipped", skipped);
                        row.put("success", true);
                        if (apply) {
                            Map<String, Object> saved = saveCandidate(project, chapter, analysis, true, orchestrator);
                            row.put("success", Boolean.TRUE.equals(saved.get("success")));
                            row.put("stats", saved.getOrDefault("stats", Map.of()));
                            if (!Boolean.TRUE.equals(saved.get("success"))) row.put("error", "Saving analysis is incomplete");
                            volumes.add(chapter.substring(0, chapter.indexOf('C')));
                        } else row.put("analysis", analysis);
                    }
                } catch (AnalysisContext.Paused e) {
                    job.put("status", "paused"); job.put("pause_reason", e.getMessage());
                    break;
                } catch (Exception e) {
                    row.put("success", false); row.put("error", "Chapter analysis failed; retry is available");
                    row.put("error_type", e.getClass().getSimpleName());
                }
                row.put("latency_ms", (System.nanoTime() - chapterStarted) / 1_000_000);
                results.add(row);
                checkpoint(project, id, job, account, started);
            }
            if (!"paused".equals(job.get("status"))) {
                try (var ignored = new AnalysisContext(account, "_reduce", "volume", options)) {
                    for (String volume : volumes) refreshVolume(project, volume);
                    if (Boolean.TRUE.equals(request.getBookSummary()))
                        job.put("synopsis", reduceBook(project, chapters, options.getReduceBatchSize()));
                } catch (AnalysisContext.Paused e) {
                    job.put("status", "paused"); job.put("pause_reason", e.getMessage());
                } catch (Exception e) {
                    if (!account.stopReason().isEmpty()) {
                        job.put("status", "paused"); job.put("pause_reason", account.stopReason());
                    } else {
                        job.put("status", "partial"); job.put("reduce_error", "Summary reduction failed; existing summary was retained");
                        job.put("reduce_error_type", e.getClass().getSimpleName());
                    }
                }
            }
            if ("running".equals(job.get("status"))) {
                job.put("status", results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))) ? "complete" : "partial");
            }
            job.put("success", "complete".equals(job.get("status")));
            job.put("usage", storage.usage(project, id));
            job.put("skipped_count", results.stream().filter(row -> Boolean.TRUE.equals(row.get("skipped"))).count());
            job.put("remaining_chapters", chapters.stream().filter(chapter -> results.stream().noneMatch(row ->
                    chapter.equals(row.get("chapter")) && Boolean.TRUE.equals(row.get("success")))).toList());
            if ("complete".equals(job.get("status"))) job.remove("next_chapter");
            checkpoint(project, id, job, account, started);
            return job;
        } finally { active.remove(project + "/" + id); }
    }

    private void checkpoint(String project, String id, Map<String, Object> job,
                            AnalysisContext.Account account, long started) throws IOException {
        job.put("updated_at", Instant.now().toString());
        job.put("metrics", account.metrics());
        job.put("elapsed_ms", ((Number) job.getOrDefault("previous_elapsed_ms", 0)).longValue()
                + (System.nanoTime() - started) / 1_000_000);
        storage.job(project, id, job);
    }

    public Map<String, Object> status(String project, String id) throws IOException {
        Map<String, Object> job = storage.job(project, id);
        if (job.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis job not found");
        if ("running".equals(job.get("status")) && !active.containsKey(project + "/" + id)) {
            job.put("status", "paused"); job.put("pause_reason", "interrupted");
        }
        var account = active.get(project + "/" + id);
        if (account != null) job.put("metrics", account.metrics());
        return job;
    }
    public Map<String, Object> cancel(String project, String id) throws IOException {
        Map<String, Object> job = status(project, id);
        var account = active.get(project + "/" + id);
        if (account != null) account.cancel();
        job.put("cancel_requested", account != null);
        return job;
    }

    public Map<String, Object> saveCandidate(String project, String chapter, Map<String, Object> analysis,
                                             boolean overwrite, Orchestrator orchestrator) throws IOException {
        validateCandidate(project, chapter, analysis);
        Map<String, Object> result = orchestrator.saveAnalysis(project, chapter, analysis, overwrite, false);
        if (Boolean.TRUE.equals(result.get("success"))) {
            memory.invalidateFrom(project, chapter);
            // Preview caches are not proof that a source revision was applied.
            if (overwrite && analysis.containsKey("_source_hash")) {
                validateCandidate(project, chapter, analysis);
                storage.applied(project, chapter, Map.of(
                        "source_hash", analysis.get("_source_hash"),
                        "title_hash", analysis.get("_title_hash"),
                        "semantic", ContinuityService.semantic(analysis)));
            }
        }
        return result;
    }

    private void validateCandidate(String project, String chapter, Map<String, Object> analysis) {
        if (analysis.containsKey("_source_hash") && (!Objects.equals(analysis.get("_source_hash"), AnalysisStorage.hash(content(project, chapter)))
                || !Objects.equals(analysis.get("_title_hash"), AnalysisStorage.hash(title(project, chapter)))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chapter changed since analysis; regenerate the candidate");
    }

    public Map<String, Object> analyzeSingle(String project, String chapter, String content, String title) {
        var options = new AnalysisProperties();
        BeanUtils.copyProperties(defaults, options);
        options.setBulkMode(false);
        String id = UUID.randomUUID().toString();
        var account = new AnalysisContext.Account(id, options.getMaxTokensPerJob(), options.getMaxMinutesPerJob(),
                options.getMaxCallsPerChapter(), 0, event -> storage.event(project, event));
        try (var ignored = new AnalysisContext(account, AnalysisStorage.chapter(chapter), "analysis", options)) {
            Map<String, Object> response = new LinkedHashMap<>(pool.getOrchestrator(project).analyzeChapter(project, chapter, content, title));
            response.put("analysis_trace_id", id);
            response.put("metrics", account.metrics());
            return response;
        }
    }

    public Map<String, Object> saveReviewed(String project, List<Map<String, Object>> items, boolean overwrite) throws IOException {
        return saveReviewed(project, items, overwrite, true);
    }
    public Map<String, Object> saveReviewed(String project, List<Map<String, Object>> items, boolean overwrite,
                                             boolean bulk) throws IOException {
        return saveReviewed(project, items, overwrite, bulk, null);
    }
    public Map<String, Object> saveForContinuity(String project, List<Map<String, Object>> items,
                                                AnalysisJobRequest budget) throws IOException {
        return saveReviewed(project, items, true, true, budget);
    }
    private Map<String, Object> saveReviewed(String project, List<Map<String, Object>> items, boolean overwrite,
                                             boolean bulk, AnalysisJobRequest budget) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "A project analysis is already running");
        String activeId = null;
        try {
            for (Map<String, Object> item : items) {
                String chapter = AnalysisStorage.chapter(Objects.toString(item.get("chapter"), ""));
                validateCandidate(project, chapter, map(item.get("analysis")));
            }
            var options = budget == null ? options(new AnalysisJobRequest()) : options(budget);
            options.setBulkMode(bulk && defaults.isBulkMode());
            String id = budget == null ? UUID.randomUUID().toString() : UUID.fromString(budget.getJobId()).toString();
            Map<String, Object> previous = budget == null ? Map.of() : storage.job(project, id);
            var account = new AnalysisContext.Account(id, options.getMaxTokensPerJob(), options.getMaxMinutesPerJob(),
                    options.getMaxCallsPerChapter(), storage.spent(project, id),
                    previous.get("elapsed_ms") instanceof Number elapsed ? elapsed.longValue() : 0,
                    event -> storage.event(project, event));
            activeId = project + "/" + id;
            active.put(activeId, account);
            List<Map<String, Object>> results = new ArrayList<>();
            Set<String> volumes = new LinkedHashSet<>();
            for (Map<String, Object> item : items) {
                String chapter = AnalysisStorage.chapter(Objects.toString(item.get("chapter"), ""));
                Map<String, Object> analysis = map(item.get("analysis"));
                try (var ignored = new AnalysisContext(account, chapter, "save", options)) {
                    account.check();
                    Map<String, Object> result = new LinkedHashMap<>(saveCandidate(project, chapter, analysis, overwrite, pool.getOrchestrator(project)));
                    result.put("chapter", chapter);
                    results.add(result);
                    volumes.add(chapter.substring(0, chapter.indexOf('C')));
                }
            }
            try (var ignored = new AnalysisContext(account, "_reduce", "volume", options)) {
                for (String volume : volumes) {
                    account.check();
                    refreshVolume(project, volume);
                }
            } catch (Exception e) {
                return Map.of("success", false, "results", results, "error", "章节已保存，卷摘要未完成；可重试", "metrics", account.metrics());
            }
            return Map.of("success", results.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))),
                    "results", results, "metrics", account.metrics());
        } finally {
            if (activeId != null) active.remove(activeId);
            lock.unlock();
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "analysis is required");
        return (Map<String, Object>) value;
    }

    public void refreshVolume(String project, String volume) throws IOException {
        List<ChapterSummary> summaries = drafts.listChapterSummaries(project, volume).stream()
                .filter(summary -> summary.getWordCount() > 0).toList();
        if (summaries.isEmpty()) return;
        String hash = storage.fingerprint(Map.of("model", gateway.analysisFingerprint(), "prompt", PROMPT_VERSION,
                "summaries", summaries.stream().map(summary -> Map.of(
                "chapter", summary.getChapter(), "title", Objects.toString(summary.getTitle(), ""),
                "summary", Objects.toString(summary.getBriefSummary(), ""), "words", summary.getWordCount(),
                "loops", summary.getOpenLoops() == null ? List.of() : summary.getOpenLoops())).toList()));
        Map<String, Object> old = drafts.getVolumeSummary(project, volume).orElse(Map.of());
        if (hash.equals(old.get("source_hash"))) return;
        Map<String, Object> generated = AnalysisContext.stage("volume", () -> archivist.generateVolumeSummary(project, volume, summaries));
        if (!"complete".equals(generated.get("generation_status"))) throw new IllegalStateException("Volume summary failed");
        Map<String, Object> result = new LinkedHashMap<>(generated);
        result.put("source_hash", hash);
        drafts.saveVolumeSummary(project, result);
    }

    private Map<String, Object> reduceBook(String project, List<String> chapters, int batchSize) throws IOException {
        List<ChapterSummary> maps = new ArrayList<>();
        for (String chapter : chapters) {
            String text = content(project, chapter);
            if (text.isBlank()) continue;
            maps.add(ChapterSummary.builder().chapter(chapter).title(title(project, chapter))
                    .wordCount(text.length()).briefSummary(ChapterDigest.extract(text, 900)).build());
        }
        if (maps.isEmpty()) return Map.of();
        int chapterCount = maps.size();
        int size = Math.max(2, Math.min(50, batchSize));
        // Each reduce level receives bounded groups, never the full book's raw prose.
        int level = 0;
        while (maps.size() > 1 || level == 0) {
            List<ChapterSummary> next = new ArrayList<>();
            for (int start = 0; start < maps.size(); start += size) {
                List<ChapterSummary> group = maps.subList(start, Math.min(maps.size(), start + size));
                String node = "book_" + level + "_" + start;
                String hash = storage.fingerprint(Map.of("model", gateway.analysisFingerprint(),
                        "prompt", PROMPT_VERSION, "group", group));
                Map<String, Object> summary = storage.reduction(project, hash);
                if (summary.isEmpty()) {
                    summary = AnalysisContext.stage("book_reduce",
                            () -> archivist.generateVolumeSummary(project, node, group));
                }
                if (!"complete".equals(summary.get("generation_status"))) throw new IllegalStateException("Book summary failed");
                storage.reduction(project, hash, summary);
                next.add(ChapterSummary.builder().chapter(node).title("").wordCount(group.stream().mapToInt(ChapterSummary::getWordCount).sum())
                        .briefSummary(Objects.toString(summary.get("brief_summary"), "")).build());
            }
            maps = next; level++;
        }
        Map<String, Object> result = Map.of("brief_summary", maps.getFirst().getBriefSummary(),
                "chapter_count", chapterCount, "source", "deterministic_excerpt_map_reduce",
                "requires_review", true, "updated_at", Instant.now().toString());
        storage.bookSummary(project, result);
        return result;
    }

    public Map<String, Object> bookSummary(String project) throws IOException { return storage.bookSummary(project); }

    private String content(String project, String chapter) {
        return drafts.getLatestDraft(project, chapter).map(draft -> Objects.toString(draft.getContent(), ""))
                .or(() -> drafts.getFinalDraft(project, chapter)).orElse("");
    }
    private String title(String project, String chapter) {
        return drafts.getChapterSummary(project, chapter).map(ChapterSummary::getTitle)
                .or(() -> drafts.getSceneBrief(project, chapter).map(brief -> brief.getTitle())).orElse("");
    }
    private AnalysisProperties options(AnalysisJobRequest request) {
        var value = new AnalysisProperties();
        BeanUtils.copyProperties(defaults, value);
        if (request.getCombined() != null) value.setCombined(request.getCombined());
        if (request.getBulkMode() != null) value.setBulkMode(request.getBulkMode());
        if (request.getMaxTokens() != null) value.setMaxTokensPerJob(request.getMaxTokens());
        if (request.getMaxMinutes() != null) value.setMaxMinutesPerJob(request.getMaxMinutes());
        if (request.getMaxCallsPerChapter() != null) value.setMaxCallsPerChapter(request.getMaxCallsPerChapter());
        return value;
    }
    private static int compare(String left, String right) {
        String[] a = left.substring(1).split("C"), b = right.substring(1).split("C");
        int volume = Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0]));
        return volume != 0 ? volume : Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
    }
}
