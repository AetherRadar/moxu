package com.moxu.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.llm.LlmGateway;
import com.moxu.storage.DraftStorage;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import static com.moxu.analysis.CausalModel.*;

/** Resumable, source-versioned causal audit. It never changes prose, cards or canon. */
@Service
public class CausalAuditService {
    private final AnalysisStorage storage;
    private final DraftStorage drafts;
    private final LlmGateway gateway;
    private final CausalExtractor extractor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CausalGraphEngine engine = new CausalGraphEngine();
    private static final String BOOK_REPORT = "V0C0";
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private record Active(String jobId, AnalysisContext.Account account) {}
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public record Request(@NotBlank String revision, @Min(0) @Max(1000000000) Long max_tokens,
                          @Min(1) @Max(1440) Integer max_minutes) {}
    public record Decision(@NotBlank String revision, @NotBlank String finding_id,
                           @NotBlank @Pattern(regexp = "accepted_risk|false_positive|reopen") String resolution,
                           @Size(max = 1000) String note) {}
    private record Snapshot(List<Source> sources, String revision, String model) {}

    public CausalAuditService(AnalysisStorage storage, DraftStorage drafts, LlmGateway gateway, CausalExtractor extractor) {
        this.storage = storage; this.drafts = drafts; this.gateway = gateway; this.extractor = extractor;
    }

    private Snapshot snapshot(String project, String target) throws IOException {
        AnalysisStorage.chapter(target);
        List<Source> sources = drafts.readManuscriptForAudit(project).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ContinuityService::compare))
                .map(entry -> new Source(entry.getKey(), entry.getValue(), AnalysisStorage.hash(entry.getValue())))
                .filter(s -> !s.text().isBlank()).toList();
        String model = gateway.analysisFingerprint();
        String revision = storage.fingerprint(Map.of("version", CausalExtractor.VERSION, "model", model,
                "sources", sources.stream().map(s -> List.of(s.chapter(), s.hash())).toList()));
        return new Snapshot(sources, revision, model);
    }

    public Map<String, Object> status(String project, String target) throws IOException {
        Snapshot snapshot = snapshot(project, target);
        Map<String, Object> report = storage.causalReport(project, BOOK_REPORT);
        var running = active.get(project);
        boolean owns = running != null && running.jobId.equals(report.get("job_id"));
        if (snapshot.sources.isEmpty()) return Map.of("status", "complete", "fresh", true, "ready_for_generation", true,
                "revision", snapshot.revision, "total_chapters", 0, "findings", List.of(), "unresolved", 0);
        if (!snapshot.revision.equals(report.get("revision"))) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", report.isEmpty() ? "not_started" : "stale");
            result.put("revision", snapshot.revision); result.put("fresh", false);
            result.put("ready_for_generation", false); result.put("total_chapters", snapshot.sources.size());
            result.put("findings", List.of()); result.put("unresolved", 0);
            result.put("active", owns);
            if (owns) result.put("metrics", running.account.metrics());
            return result;
        }
        report = new LinkedHashMap<>(report);
        report.put("active", owns);
        if ("running".equals(report.get("status")) && !owns) report.put("status", "paused");
        if (owns) report.put("metrics", running.account.metrics());
        report.put("fresh", true);
        report.put("ready_for_generation", "complete".equals(report.get("status")) && unresolved(report) == 0);
        report.put("unresolved", unresolved(report));
        return report;
    }

    public Map<String, Object> run(String project, String target, Request request) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有因果核查正在运行");
        try {
            if (active.containsKey(project)) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有因果核查正在运行");
            return runLocked(project, AnalysisStorage.chapter(target), request);
        }
        finally { lock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runLocked(String project, String target, Request request) throws IOException {
        Snapshot snapshot = snapshot(project, target);
        if (!snapshot.revision.equals(request.revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "作品已变化，请重新检查因果任务");
        Map<String, Object> old = storage.causalReport(project, BOOK_REPORT);
        boolean resume = snapshot.revision.equals(old.get("revision")) && old.get("job_id") instanceof String;
        if (resume && "complete".equals(old.get("status"))) return status(project, target);
        String id = resume ? old.get("job_id").toString() : UUID.randomUUID().toString();
        long previousElapsed = resume && old.get("elapsed_ms") instanceof Number n ? n.longValue() : 0;
        long tokens = request.max_tokens() == null ? 200000L : request.max_tokens();
        int minutes = request.max_minutes() == null ? 30 : request.max_minutes();
        if (tokens < 0 || tokens > 1000000000L || minutes < 1 || minutes > 1440)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid causal budget");
        var account = new AnalysisContext.Account(id, tokens, minutes, 20, storage.spent(project, id), previousElapsed,
                event -> storage.event(project, event));
        active.put(project, new Active(id, account));
        long started = System.nanoTime();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("job_id", id); report.put("revision", snapshot.revision); report.put("target", target);
        report.put("status", "running"); report.put("total_chapters", snapshot.sources.size());
        report.put("completed_chapters", 0); report.put("cached_segments", 0); report.put("processed_segments", 0);
        report.put("max_tokens", tokens); report.put("max_minutes", minutes);
        report.put("decisions", resume ? old.getOrDefault("decisions", Map.of()) : Map.of());
        if (!resume && old.containsKey("graph_hash")) report.put("previous_graph_hash", old.get("graph_hash"));
        else if (old.containsKey("previous_graph_hash")) report.put("previous_graph_hash", old.get("previous_graph_hash"));
        List<Map<String, Object>> findings = new ArrayList<>();
        report.put("findings", findings);
        var options = new AnalysisProperties();
        options.setBulkMode(true);
        List<CausalGraphEngine.Segment> segments = new ArrayList<>();
        Map<String, Source> byChapter = new LinkedHashMap<>();
        snapshot.sources.forEach(s -> byChapter.put(s.chapter(), s));
        try {
            save(project, target, report, account, previousElapsed, started);
            for (Source source : snapshot.sources) {
                report.put("stage", "extract"); report.put("current_chapter", source.chapter());
                for (int[] range : CausalExtractor.segments(source.text())) {
                    account.check();
                    // Extractions contain literal quotes, not offsets; rebuild evidence against the current source.
                    String key = storage.fingerprint(List.of(CausalExtractor.VERSION, snapshot.model, source.chapter(),
                            AnalysisStorage.hash(source.text().substring(range[0], range[1]))));
                    Map<String, Object> cache = storage.causalCache(project, "extraction", key);
                    Extraction result;
                    if (cache.isEmpty()) {
                        requireModel(snapshot.model);
                        try (var ignored = new AnalysisContext(account, source.chapter() + ":" + range[0], "causal_extract", options)) {
                            result = extractor.extract(source, range[0], range[1]);
                        }
                        requireModel(snapshot.model);
                        storage.causalCache(project, "extraction", key, data(result));
                    } else {
                        result = mapper.convertValue(cache, Extraction.class);
                        report.put("cached_segments", ((Number) report.get("cached_segments")).intValue() + 1);
                    }
                    segments.add(new CausalGraphEngine.Segment(source, range[0], range[1], result));
                    report.put("processed_segments", ((Number) report.get("processed_segments")).intValue() + 1);
                    save(project, target, report, account, previousElapsed, started);
                }
                report.put("completed_chapters", ((Number) report.get("completed_chapters")).intValue() + 1);
                save(project, target, report, account, previousElapsed, started);
            }
            account.check();
            Graph graph = engine.build(segments);
            Graph previous = null;
            if (report.get("previous_graph_hash") instanceof String hash) {
                Map<String, Object> cache = storage.causalCache(project, "graph", hash);
                if (!cache.isEmpty()) previous = mapper.convertValue(cache, Graph.class);
            }
            Set<String> impacted = engine.impacted(previous, graph);
            report.put("impacted_events", impacted.size());
            report.put("reused_events", graph.nodes().stream().filter(n -> !impacted.contains(n.id())).count());
            report.put("event_count", graph.nodes().size()); report.put("dependency_count", graph.edges().size());
            String graphHash = storage.fingerprint(graph);
            storage.causalCache(project, "graph", graphHash, data(graph));
            report.put("graph_hash", graphHash);
            report.put("stage", "verify");
            for (String chapter : graph.uncertainChapters()) {
                Source source = byChapter.get(chapter);
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("id", "coverage-" + AnalysisStorage.hash(chapter + source.hash()).substring(0, 24));
                finding.put("classification", "insufficient_evidence"); finding.put("chapter", chapter);
                finding.put("explanation", "该章存在倒叙、非现实场景、身份歧义或提取覆盖缺口，不能据此确认完整因果一致性。");
                finding.put("uncertainties", segments.stream().filter(s -> s.source().chapter().equals(chapter))
                        .flatMap(s -> s.extraction().uncertainties().stream()).distinct().toList());
                findings.add(finding);
            }
            int checked = 0;
            report.put("total_candidates", graph.candidates().size());
            Map<String, Node> nodes = new HashMap<>();
            graph.nodes().forEach(n -> nodes.put(n.id(), n));
            Map<String, List<String>> incoming = new HashMap<>();
            graph.edges().forEach(e -> incoming.computeIfAbsent(e.to(), ignored -> new ArrayList<>()).add(e.from()));
            for (Candidate candidate : graph.candidates()) {
                account.check();
                Map<String, Object> finding = data(candidate);
                finding.put("chapter", candidate.current().chapter());
                finding.put("classification", "insufficient_evidence");
                finding.put("explanation", "缺少可对照的前提证据，不能将缺失信息判为冲突。");
                if (candidate.prior() != null) {
                    String priorContext = context(byChapter.get(candidate.prior().chapter()), candidate.prior());
                    String currentContext = context(byChapter.get(candidate.current().chapter()), candidate.current());
                    // Bridge edits may explain a pair; unrelated chapters need not invalidate verification.
                    var bridge = snapshot.sources.stream().filter(s ->
                            ContinuityService.compare(s.chapter(), candidate.prior().chapter()) >= 0
                                    && ContinuityService.compare(s.chapter(), candidate.current().chapter()) <= 0)
                            .map(s -> List.of(s.chapter(), s.hash())).toList();
                    String key = storage.fingerprint(List.of(CausalExtractor.VERSION, snapshot.model, candidate,
                            priorContext, currentContext, bridge));
                    Map<String, Object> cached = storage.causalCache(project, "verification", key);
                    Verdict verdict;
                    if (cached.isEmpty()) {
                        requireModel(snapshot.model);
                        try (var ignored = new AnalysisContext(account, candidate.id(), "causal_verify", options)) {
                            verdict = extractor.verify(candidate, priorContext, currentContext);
                        }
                        requireModel(snapshot.model);
                        storage.causalCache(project, "verification", key, data(verdict));
                    } else verdict = mapper.convertValue(cached, Verdict.class);
                    String classification = verdict.confidence() < 0.8 ? "insufficient_evidence"
                            : switch (verdict.verdict()) {
                                case "conflict" -> Set.of("state_mismatch", "resurrection_or_conflict").contains(candidate.kind())
                                        ? "supported_conflict" : "insufficient_evidence";
                                case "explained" -> "explained";
                                default -> "insufficient_evidence";
                            };
                    finding.put("classification", classification);
                    finding.put("explanation", verdict.explanation());
                    finding.put("verification", data(verdict));
                }
                finding.put("dependency_path", ancestry(candidate.nodeId(), incoming).stream().map(nodes::get)
                        .filter(Objects::nonNull).map(n -> Map.of("id", n.id(), "chapter", n.chapter(),
                                "label", n.label(), "quote", n.evidence().quote())).toList());
                findings.add(finding);
                report.put("checked_candidates", ++checked);
                if (checked % 10 == 0 || checked == graph.candidates().size())
                    save(project, target, report, account, previousElapsed, started);
            }
            account.check();
            if (!snapshot.revision.equals(snapshot(project, target).revision)) {
                report.put("status", "stale"); report.put("error", "核查期间正文或模型配置变化，结果不能用于当前版本");
            } else report.put("status", "complete");
        } catch (AnalysisContext.Paused e) {
            report.put("status", "paused"); report.put("pause_reason", e.getMessage());
        } catch (Exception e) {
            report.put("status", "partial"); report.put("error", "因果核查未完成，可恢复任务；未完成不代表无冲突");
            report.put("error_type", e.getClass().getSimpleName());
        } finally {
            try {
                report.put("usage", storage.usage(project, id));
                save(project, target, report, account, previousElapsed, started);
            } finally { active.remove(project); }
        }
        return status(project, target);
    }

    public Map<String, Object> cancel(String project, String target) throws IOException {
        AnalysisStorage.chapter(target);
        Map<String, Object> report = storage.causalReport(project, BOOK_REPORT);
        if (report.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "因果任务不存在");
        // Reports are book-scoped, so switching the generation target refers to the same job.
        var running = active.get(project);
        if (running != null && running.jobId.equals(report.get("job_id"))
                && "running".equals(report.get("status"))) running.account.cancel();
        return status(project, target);
    }

    public Map<String, Object> decide(String project, String target, Decision decision) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(project, ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待因果核查完成");
        try {
            Map<String, Object> report = new LinkedHashMap<>(status(project, target));
            if (!"complete".equals(report.get("status")) || !Boolean.TRUE.equals(report.get("fresh"))
                    || !Objects.equals(report.get("revision"), decision.revision()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "不能确认旧版本或未完成的因果报告");
            if (!Set.of("accepted_risk", "false_positive", "reopen").contains(Objects.toString(decision.resolution(), "")))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid finding resolution");
            if ("false_positive".equals(decision.resolution()) && (decision.note() == null || decision.note().isBlank()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标记误报时请填写依据");
            boolean exists = rows(report.get("findings")).stream().anyMatch(f -> decision.finding_id().equals(f.get("id"))
                    && !"explained".equals(f.get("classification")));
            if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "因果问题不存在");
            Map<String, Object> decisions = new LinkedHashMap<>(map(report.get("decisions")));
            if ("reopen".equals(decision.resolution())) decisions.remove(decision.finding_id());
            else decisions.put(decision.finding_id(), Map.of("resolution", decision.resolution(),
                    "note", Objects.toString(decision.note(), ""), "at", java.time.Instant.now().toString()));
            report.put("decisions", decisions);
            storage.causalReport(project, BOOK_REPORT, report);
            return status(project, target);
        } finally { lock.unlock(); }
    }
    public Map<String, Object> graph(String project, String target) throws IOException {
        Map<String, Object> report = status(project, target);
        if (!Boolean.TRUE.equals(report.get("fresh")) || !(report.get("graph_hash") instanceof String hash))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前版本的因果图尚未建立");
        return storage.causalCache(project, "graph", hash);
    }
    private void save(String project, String target, Map<String, Object> report, AnalysisContext.Account account,
                      long elapsed, long started) throws IOException {
        report.put("elapsed_ms", elapsed + (System.nanoTime() - started) / 1_000_000);
        report.put("metrics", account.metrics()); report.put("unresolved", unresolved(report));
        report.put("updated_at", java.time.Instant.now().toString());
        storage.causalReport(project, BOOK_REPORT, report);
    }
    private Map<String, Object> data(Object value) { return mapper.convertValue(value, new TypeReference<>() {}); }
    private void requireModel(String expected) {
        if (!expected.equals(gateway.analysisFingerprint()))
            throw new IllegalStateException("Causal model configuration changed during audit");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
    private static long unresolved(Map<String, Object> report) {
        Map<String, Object> decisions = map(report.get("decisions"));
        return rows(report.get("findings")).stream().filter(f -> !"explained".equals(f.get("classification")))
                .filter(f -> !decisions.containsKey(f.get("id"))).count();
    }
    private static String context(Source source, Evidence evidence) {
        if (source == null || !source.hash().equals(evidence.sourceHash()))
            throw new IllegalStateException("Causal source revision mismatch");
        int start = Math.max(0, evidence.start() - 2000), end = Math.min(source.text().length(), evidence.end() + 2000);
        return source.text().substring(start, end);
    }
    private static List<String> ancestry(String id, Map<String, List<String>> incoming) {
        LinkedHashSet<String> path = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty() && path.size() < 16) {
            String current = queue.removeFirst();
            if (path.add(current)) queue.addAll(incoming.getOrDefault(current, List.of()));
        }
        return new ArrayList<>(path);
    }
}
