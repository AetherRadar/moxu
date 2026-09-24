package com.moxu.analysis;

import com.moxu.storage.CanonStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Source revisions drive extraction; semantic deltas drive review.
 * Entity matches are review candidates, never a proof of narrative consistency.
 */
@Service
public class ContinuityService {
    private final AnalysisStorage storage;
    private final DraftStorage drafts;
    private final CanonStorage canon;
    private final AnalysisJobService jobs;
    private final MemoryPackStorage memory;
    private final CausalAuditService causal;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private static final String PROJECT_REVIEW = "V0C0";

    public ContinuityService(AnalysisStorage storage, DraftStorage drafts, CanonStorage canon, AnalysisJobService jobs,
                             MemoryPackStorage memory, CausalAuditService causal) {
        this.storage = storage; this.drafts = drafts; this.canon = canon; this.jobs = jobs; this.memory = memory;
        this.causal = causal;
    }

    public record Request(String revision, String job_id, Long max_tokens, Integer max_minutes) {}
    private record Source(String chapter, String content, String source_hash, String title_hash) {}

    static int compare(String a, String b) {
        String[] x = AnalysisStorage.chapter(a).substring(1).split("C");
        String[] y = AnalysisStorage.chapter(b).substring(1).split("C");
        int volume = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
        return volume != 0 ? volume : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
    }

    private List<Source> sources(String project, String target) {
        return drafts.listChapters(project).stream().filter(ch -> compare(ch, target) < 0)
                .sorted(ContinuityService::compare).map(ch -> {
                    String text = drafts.getLatestDraft(project, ch).map(d -> Objects.toString(d.getContent(), "")).orElse("");
                    String title = drafts.getChapterSummary(project, ch).map(s -> Objects.toString(s.getTitle(), ""))
                            .orElseGet(() -> drafts.getSceneBrief(project, ch).map(s -> Objects.toString(s.getTitle(), "")).orElse(""));
                    return new Source(ch, text, AnalysisStorage.hash(text), AnalysisStorage.hash(title));
                }).toList();
    }

    private String revision(String project, List<Source> sources) throws IOException {
        // Also bind approval to manual canon/card/summary edits, not just manuscript text.
        return storage.fingerprint(Map.of("version", 1,
                "sources", sources.stream().map(s -> List.of(s.chapter, s.source_hash, s.title_hash)).toList(),
                "materials", materials(project)));
    }

    private String materials(String project) throws IOException {
        return storage.fingerprint(Map.of("cards", storage.cardsFingerprint(project),
                "facts", normalize(canon.listFactsRaw(project)),
                "timeline", normalize(canon.listTimelineEvents(project)),
                "states", normalize(canon.listCharacterStates(project)),
                "summaries", drafts.listChapterSummaries(project, null).stream()
                        .sorted(Comparator.comparing(s -> s.getChapter())).toList()));
    }

    public Map<String, Object> inspect(String project, String requestedTarget) throws IOException {
        return causalGate(project, requestedTarget, inspectBase(project, requestedTarget));
    }

    private Map<String, Object> causalGate(String project, String target, Map<String, Object> base) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>(base);
        Map<String, Object> audit = causal.status(project, target);
        result.put("causal", audit);
        if (Set.of("ready", "needs_review").contains(Objects.toString(base.get("status"), ""))
                && !Boolean.TRUE.equals(audit.get("ready_for_generation"))) result.put("status", "needs_causal");
        return result;
    }

    private Map<String, Object> inspectBase(String project, String requestedTarget) throws IOException {
        String target = AnalysisStorage.chapter(requestedTarget);
        List<Source> sources = sources(project, target);
        String revision = revision(project, sources);
        Map<String, Object> previous = storage.continuity(project, target);
        Map<String, Object> global = storage.continuity(project, PROJECT_REVIEW);
        boolean pendingReview = Boolean.TRUE.equals(global.get("pending"));
        boolean incomplete = Boolean.TRUE.equals(global.get("incomplete"));
        if (revision.equals(previous.get("revision")) && (!pendingReview || !"ready".equals(previous.get("status")))
                && (!incomplete || target.equals(global.get("owner")))) {
            Map<String, Object> result = new LinkedHashMap<>(previous);
            if (incomplete && "needs_review".equals(result.get("status"))) result.put("status", "partial");
            if ("running".equals(result.get("status"))) {
                var lock = locks.get(project);
                if (lock == null || !lock.isLocked()) result.put("status", "paused");
            }
            return result;
        }
        List<String> changed = new ArrayList<>(), unknown = new ArrayList<>(), removed = new ArrayList<>();
        for (Source source : sources) {
            Map<String, Object> applied = storage.applied(project, source.chapter);
            // A successful archive with matching source hashes can seed a legacy checkpoint.
            if (applied.isEmpty() && !source.content.isBlank()) {
                Map<String, Object> archive = drafts.getArchiveStatus(project, source.chapter);
                if ("complete".equals(archive.get("status")) && source.source_hash.equals(archive.get("content_hash"))
                        && source.title_hash.equals(archive.get("title_hash"))) {
                    Map<String, Object> archived = Map.of(
                            "facts", canon.listFactsRaw(project).stream().filter(f ->
                                    source.chapter.equals(f.get("introduced_in")) || source.chapter.equals(f.get("source"))).toList(),
                            "timeline_events", canon.listTimelineEventsByChapter(project, source.chapter),
                            "character_states", canon.listCharacterStates(project).stream()
                                    .filter(s -> source.chapter.equals(s.get("last_seen"))).toList());
                    applied = Map.of("source_hash", source.source_hash, "title_hash", source.title_hash,
                            "semantic", semantic(archived));
                    storage.applied(project, source.chapter, applied);
                }
            }
            if (source.content.isBlank()) {
                if (!applied.isEmpty() && !Boolean.TRUE.equals(applied.get("deleted"))) removed.add(source.chapter);
                continue;
            }
            if (!source.source_hash.equals(applied.get("source_hash")) || !source.title_hash.equals(applied.get("title_hash"))) {
                changed.add(source.chapter);
                if (applied.isEmpty()) unknown.add(source.chapter);
            }
        }
        // Detect deleted chapter directories using the last checked scope.
        {
            List<String> old = storage.appliedChapters(project).stream().filter(ch -> compare(ch, target) < 0).toList();
            Set<String> present = new HashSet<>(sources.stream().map(Source::chapter).toList());
            for (String ch : old) if (!present.contains(ch) && !Boolean.TRUE.equals(storage.applied(project, ch).get("deleted"))) removed.add(ch);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chapter", target); result.put("revision", revision);
        result.put("changed_chapters", changed); result.put("unknown_chapters", unknown);
        result.put("removed_chapters", removed);
        result.put("source_chapters", sources.stream().map(Source::chapter).toList());
        result.put("affected_chapters", List.of()); result.put("deltas", List.of());
        boolean externalChange = changed.isEmpty() && (!previous.isEmpty()
                || !global.isEmpty() && !Objects.equals(global.get("materials"), materials(project)));
        result.put("status", !removed.isEmpty() ? "blocked" : !changed.isEmpty() ? "needs_sync"
                : externalChange || pendingReview ? "needs_review" : "ready");
        result.put("message", !removed.isEmpty() ? "前文章节已删除或清空，需清理对应事实后核对"
                : externalChange ? "设定、事实或摘要已变更，请核对后继续" : "");
        // Preserve unresolved semantic review even after another edit.
        if (previous.get("deltas") instanceof List<?> deltas && !"ready".equals(previous.get("status"))) {
            result.put("deltas", deltas);
            result.put("affected_chapters", previous.getOrDefault("affected_chapters", List.of()));
        }
        if (pendingReview) {
            result.put("deltas", global.getOrDefault("deltas", List.of()));
            result.put("affected_chapters", global.getOrDefault("affected_chapters", List.of()));
        }
        if (Boolean.TRUE.equals(global.get("incomplete")) && !target.equals(global.get("owner"))) {
            result.put("status", "blocked");
            result.put("message", "请返回 " + global.get("owner") + " 恢复未完成的前文同步任务");
        }
        if (incomplete && target.equals(global.get("owner")) && removed.isEmpty()
                && previous.get("changed_chapters") instanceof List<?> original && !original.isEmpty()) {
            // Preserve the original job scope, even if some chapters were already applied.
            TreeSet<String> retry = new TreeSet<>(ContinuityService::compare);
            retry.addAll(changed);
            original.forEach(ch -> retry.add(ch.toString()));
            result.put("changed_chapters", new ArrayList<>(retry));
            result.put("status", "partial");
            if (retry.equals(new TreeSet<>(original.stream().map(Object::toString).toList()))
                    && previous.containsKey("job_id")) result.put("job_id", previous.get("job_id"));
        }
        if ("ready".equals(result.get("status"))) {
            storage.continuity(project, target, result);
            if (global.isEmpty()) storage.continuity(project, PROJECT_REVIEW,
                    Map.of("materials", materials(project), "pending", false));
        }
        return result;
    }

    public void requireReady(String project, String target) {
        try {
            var lock = locks.get(project);
            if (lock != null && lock.isLocked())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "前文同步正在运行，请等待完成");
            if (!"ready".equals(inspect(project, target).get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "前文已变更，请先同步并核对后再生成");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "前文校验未完成，请重试");
        }
    }

    public Map<String, Object> sync(String project, String target, Request request) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "前文同步正在运行");
        try { return causalGate(project, target, syncLocked(project, AnalysisStorage.chapter(target), request)); }
        finally { lock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> syncLocked(String project, String target, Request request) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<>(inspectBase(project, target));
        if (!Objects.equals(request.revision(), plan.get("revision")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "前文再次变化，请重新检查");
        if ("blocked".equals(plan.get("status"))) return plan;
        List<String> changed = (List<String>) plan.get("changed_chapters");
        if (changed.isEmpty()) return plan;
        String id = request.job_id() == null ? Objects.toString(plan.get("job_id"), UUID.randomUUID().toString())
                : UUID.fromString(request.job_id()).toString();
        plan.put("job_id", id); plan.put("status", "running");
        storage.continuity(project, target, plan);
        try {
            AnalysisJobRequest job = new AnalysisJobRequest();
            job.setJobId(id); job.setChapters(changed); job.setBulkMode(true);
            job.setMaxTokens(request.max_tokens() == null ? 200000L : request.max_tokens());
            job.setMaxMinutes(request.max_minutes() == null ? 30 : request.max_minutes());
            if (job.getMaxTokens() < 0 || job.getMaxTokens() > 1000000000L || job.getMaxMinutes() < 1 || job.getMaxMinutes() > 1440)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid synchronization budget");
            Map<String, Object> analysis = jobs.run(project, job, false);
            plan.put("job", analysis);
            if (!"complete".equals(analysis.get("status"))) {
                plan.put("status", analysis.get("status"));
                storage.continuity(project, target, plan);
                return plan;
            }
            List<Source> sources = sources(project, target);
            if (!Objects.equals(plan.get("revision"), revision(project, sources)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同步期间前文或设定发生变化，请重新检查");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) analysis.get("results");
            List<Map<String, Object>> deltas = new ArrayList<>((List<Map<String, Object>>) plan.get("deltas"));
            Set<String> affected = new TreeSet<>(ContinuityService::compare);
            affected.addAll((List<String>) plan.get("affected_chapters"));
            List<Map<String, Object>> items = new ArrayList<>();
            List<Source> wholeBook = sources(project, "V2147483647C2147483647");
            String cardRevision = storage.cardsFingerprint(project);
            Map<String, Set<String>> chapterTerms = new LinkedHashMap<>();
            Set<String> allTerms = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String chapter = row.get("chapter").toString();
                Map<String, Object> candidate = (Map<String, Object>) row.get("analysis");
                Set<String> terms = changedEntities(storage.applied(project, chapter).get("semantic"), semantic(candidate));
                chapterTerms.put(chapter, terms);
                allTerms.addAll(terms);
            }
            // One reverse index for the entire change batch; never re-extract unchanged prose.
            Map<String, List<String>> reverseIndex = new LinkedHashMap<>();
            for (Source source : wholeBook) {
                for (String term : allTerms) {
                    if (source.content.contains(term))
                        reverseIndex.computeIfAbsent(term, ignored -> new ArrayList<>()).add(source.chapter);
                }
            }
            plan.put("entity_dependencies", reverseIndex);
            for (Map<String, Object> row : rows) {
                String chapter = row.get("chapter").toString();
                Map<String, Object> candidate = new LinkedHashMap<>((Map<String, Object>) row.get("analysis"));
                Object old = storage.applied(project, chapter).get("semantic");
                Object next = semantic(candidate);
                if (!Objects.equals(old, next)) {
                    Set<String> terms = chapterTerms.get(chapter);
                    Set<String> dependencies = new TreeSet<>(ContinuityService::compare);
                    if (terms.isEmpty()) dependencies.addAll(wholeBook.stream().map(Source::chapter).toList());
                    else for (String term : terms) dependencies.addAll(reverseIndex.getOrDefault(term, List.of()));
                    List<String> matches = dependencies.stream().filter(ch -> compare(ch, chapter) > 0).toList();
                    affected.addAll(matches);
                    Map<String, Object> delta = new LinkedHashMap<>();
                    delta.put("chapter", chapter); delta.put("before", old == null ? Map.of() : old);
                    delta.put("after", next); delta.put("related_chapters", matches);
                    delta.put("coverage", terms.isEmpty() ? "unknown_conservative" : "entity_candidates_only");
                    Map<String, Object> conflictCheck = canon.detectConflicts(project, chapter,
                            com.moxu.quality.CanonEvidenceGuard.maps(candidate.get("facts")),
                            com.moxu.quality.CanonEvidenceGuard.maps(candidate.get("timeline_events")),
                            com.moxu.quality.CanonEvidenceGuard.maps(candidate.get("character_states")));
                    delta.put("conflict_check", conflictCheck);
                    deltas.add(delta);
                }
                // Synchronizing prose must not silently overwrite user-maintained cards.
                candidate.put("proposals", List.of());
                items.add(Map.of("chapter", chapter, "analysis", candidate));
            }
            plan.put("deltas", deltas); plan.put("affected_chapters", new ArrayList<>(affected));
            plan.put("status", "running");
            storage.continuity(project, target, plan); // Retain the before-image across partial saves/crashes.
            storage.continuity(project, PROJECT_REVIEW, Map.of("pending", true, "incomplete", true, "owner", target, "deltas", deltas,
                    "affected_chapters", new ArrayList<>(affected), "materials", materials(project)));
            Map<String, Object> saved = jobs.saveForContinuity(project, items, job);
            plan.put("save_result", saved);
            // Never bless a revision edited while save/volume reduction was running.
            List<Source> after = sources(project, target);
            if (!sources.equals(after) || !wholeBook.equals(sources(project, "V2147483647C2147483647"))
                    || !cardRevision.equals(storage.cardsFingerprint(project)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "保存期间正文或设定变化，请重新检查");
            plan.put("revision", revision(project, after));
            plan.put("changed_chapters", Boolean.TRUE.equals(saved.get("success")) ? List.of() : changed);
            plan.put("status", !Boolean.TRUE.equals(saved.get("success")) ? "partial"
                    : deltas.isEmpty() ? "ready" : "needs_review");
            storage.continuity(project, PROJECT_REVIEW, Map.of("pending", !"ready".equals(plan.get("status")),
                    "incomplete", !Boolean.TRUE.equals(saved.get("success")), "owner", target,
                    "deltas", deltas, "affected_chapters", new ArrayList<>(affected), "materials", materials(project)));
            storage.continuity(project, target, plan);
            return plan;
        } catch (Exception e) {
            plan.put("status", "partial"); storage.continuity(project, target, plan);
            if (e instanceof IOException io) throw io;
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IOException(e);
        }
    }

    public Map<String, Object> acknowledge(String project, String target, String revision) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "前文同步正在运行");
        try {
        Map<String, Object> plan = new LinkedHashMap<>(inspect(project, target));
        if (Boolean.TRUE.equals(storage.continuity(project, PROJECT_REVIEW).get("incomplete")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同步保存尚未完成，请先恢复同步任务");
        if (!"needs_review".equals(plan.get("status")) || !Objects.equals(revision, plan.get("revision")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "前文状态已变化，不能确认旧检查结果");
        plan.put("status", "ready");
        plan.put("review_acknowledged", true);
        storage.continuity(project, target, plan);
        storage.continuity(project, PROJECT_REVIEW, Map.of("pending", false, "materials", materials(project)));
        return causalGate(project, target, plan);
        } finally { lock.unlock(); }
    }

    /** Explicit author confirmation is required; this never deletes or rewrites manuscript prose. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cleanRemoved(String project, String target, String expectedRevision) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, key -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "前文同步正在运行");
        try {
            Map<String, Object> plan = new LinkedHashMap<>(inspectBase(project, target));
            Map<String, Object> global = storage.continuity(project, PROJECT_REVIEW);
            if (Boolean.TRUE.equals(global.get("incomplete")) && !target.equals(global.get("owner")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先恢复原同步任务");
            List<String> removed = (List<String>) plan.get("removed_chapters");
            if (removed.isEmpty() || !Objects.equals(expectedRevision, plan.get("revision")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "前文状态已变化，请重新检查");
            List<Map<String, Object>> deltas = new ArrayList<>((List<Map<String, Object>>) plan.get("deltas"));
            Set<String> affected = new TreeSet<>(ContinuityService::compare);
            affected.addAll((List<String>) plan.get("affected_chapters"));
            List<Source> all = sources(project, "V2147483647C2147483647");
            for (String chapter : removed) {
                List<String> related = all.stream().filter(s -> compare(s.chapter, chapter) > 0).map(Source::chapter).toList();
                affected.addAll(related);
                deltas.add(Map.of("chapter", chapter, "before", storage.applied(project, chapter).getOrDefault("semantic", Map.of()),
                        "after", Map.of(), "related_chapters", related, "coverage", "unknown_conservative"));
            }
            storage.continuity(project, PROJECT_REVIEW, Map.of("pending", true, "incomplete", true, "owner", target,
                    "deltas", deltas, "affected_chapters", new ArrayList<>(affected), "materials", materials(project)));
            for (String chapter : removed) {
                if (!drafts.getLatestDraft(project, chapter).map(d -> Objects.toString(d.getContent(), "")).orElse("").isBlank())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "被清理章节已恢复正文，请重新检查");
                canon.deleteFactsByChapter(project, chapter);
                canon.deleteTimelineEventsByChapter(project, chapter);
                canon.deleteCharacterStatesByChapter(project, chapter);
                boolean remains = canon.listFactsRaw(project).stream().anyMatch(f ->
                        chapter.equals(f.get("introduced_in")) || chapter.equals(f.get("source")))
                        || !canon.listTimelineEventsByChapter(project, chapter).isEmpty()
                        || canon.listCharacterStates(project).stream().anyMatch(s -> chapter.equals(s.get("last_seen")));
                if (remains) throw new IOException("Derived chapter data could not be removed");
                String volume = chapter.substring(0, chapter.indexOf('C'));
                String title = drafts.getChapterSummary(project, chapter).map(s -> Objects.toString(s.getTitle(), "")).orElse("");
                drafts.saveChapterSummary(project, com.moxu.model.entity.ChapterSummary.builder()
                        .chapter(chapter).volumeId(volume).title(title).briefSummary("").wordCount(0).build());
                drafts.saveVolumeSummary(project, Map.of("volume_id", volume, "brief_summary", "", "generation_status", "invalidated"));
                memory.invalidateFrom(project, chapter);
                storage.applied(project, chapter, Map.of("deleted", true, "source_hash", AnalysisStorage.hash(""),
                        "title_hash", AnalysisStorage.hash(title), "semantic", semantic(Map.of())));
            }
            storage.continuity(project, PROJECT_REVIEW, Map.of("pending", true, "incomplete", false, "owner", target,
                    "deltas", deltas, "affected_chapters", new ArrayList<>(affected), "materials", materials(project)));
            Map<String, Object> next = new LinkedHashMap<>(inspectBase(project, target));
            // The changed-chapter extraction, if any, still has to run.
            next.put("deltas", deltas); next.put("affected_chapters", new ArrayList<>(affected));
            storage.continuity(project, target, next);
            return causalGate(project, target, next);
        } finally { lock.unlock(); }
    }

    static Map<String, Object> semantic(Map<String, Object> analysis) {
        Map<String, Object> result = new TreeMap<>();
        for (String key : List.of("facts", "timeline_events", "character_states")) {
            Object value = analysis.getOrDefault(key, List.of());
            result.put(key, normalize(value));
        }
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((k, v) -> {
                if (!Set.of("id", "evidence", "confidence", "created_at", "updated_at", "source", "last_seen").contains(k.toString()))
                    result.put(k.toString(), normalize(v));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ContinuityService::normalize)
                .sorted(Comparator.comparing(Object::toString)).toList();
        return value == null ? "" : value;
    }

    private static Set<String> entities(Map<?, ?> analysis) {
        Set<String> result = new LinkedHashSet<>();
        collectEntities(analysis, "", result);
        return result;
    }
    private static Set<String> changedEntities(Object before, Object after) {
        Map<?, ?> old = before instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> next = after instanceof Map<?, ?> map ? map : Map.of();
        Set<String> result = new LinkedHashSet<>();
        for (String key : List.of("facts", "timeline_events", "character_states")) {
            List<?> left = old.get(key) instanceof List<?> list ? list : List.of();
            List<?> right = next.get(key) instanceof List<?> list ? list : List.of();
            for (Object value : left) if (!right.contains(value) && value instanceof Map<?, ?> map) result.addAll(entities(map));
            for (Object value : right) if (!left.contains(value) && value instanceof Map<?, ?> map) result.addAll(entities(map));
        }
        return result;
    }
    private static void collectEntities(Object value, String key, Set<String> result) {
        if (value instanceof Map<?, ?> map) map.forEach((k, v) -> collectEntities(v, k.toString(), result));
        else if (value instanceof List<?> list) list.forEach(v -> collectEntities(v, key, result));
        else if (value instanceof String text && Set.of("character", "name", "entity", "entities", "characters",
                "participants", "location", "subject", "object").contains(key) && text.strip().length() >= 2) result.add(text.strip());
    }
}
