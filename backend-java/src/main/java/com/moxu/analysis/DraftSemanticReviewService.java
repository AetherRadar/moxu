package com.moxu.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.llm.LlmGateway;
import com.moxu.quality.ContinuityProtection;
import com.moxu.storage.DraftStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import static com.moxu.analysis.CausalModel.*;
import static com.moxu.quality.ContinuityProtection.*;

/** Explicitly requested bounded review. Read-only checks and finalization never start model calls. */
@Service
public class DraftSemanticReviewService {
    private static final String VERSION = "draft-semantic-v1";
    private final DraftStorage drafts;
    private final AnalysisStorage storage;
    private final LlmGateway gateway;
    private final CausalExtractor extractor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private record Snapshot(List<Source> sources, String model, String key, Report local) {}

    public DraftSemanticReviewService(DraftStorage drafts, AnalysisStorage storage,
                                      LlmGateway gateway, CausalExtractor extractor) {
        this.drafts = drafts; this.storage = storage; this.gateway = gateway; this.extractor = extractor;
    }

    private Snapshot snapshot(String project, String chapter, String content) throws IOException {
        String target = AnalysisStorage.chapter(chapter);
        List<Source> sources = drafts.readManuscriptBefore(project, target).entrySet().stream()
                .filter(e -> ContinuityService.compare(e.getKey(), target) < 0 && !e.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey(ContinuityService::compare))
                .map(e -> new Source(e.getKey(), e.getValue(), AnalysisStorage.hash(e.getValue()))).toList();
        Source previous = sources.isEmpty() ? new Source("", "", AnalysisStorage.hash("")) : sources.getLast();
        Report local = ContinuityProtection.review(project, target, previous.chapter(), previous.text(), content);
        String model = gateway.analysisFingerprint();
        String key = storage.fingerprint(List.of(VERSION, CausalExtractor.VERSION, project, target, model,
                local.draft_hash(), sources.stream().map(s -> List.of(s.chapter(), s.hash())).toList()));
        return new Snapshot(sources, model, key, local);
    }

    public Report review(String project, String chapter, String content) throws IOException {
        Snapshot snapshot = snapshot(project, chapter, content);
        return combine(snapshot, storage.causalCache(project, "draft_review", snapshot.key));
    }

    public Report run(String project, String chapter, String content, String revision, long maxTokens) throws IOException {
        if (maxTokens < 1000 || maxTokens > 200000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语义核查预算须在 1000 至 200000 token 之间");
        ReentrantLock lock = locks.computeIfAbsent(project, ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有草稿语义核查正在运行");
        try {
            Snapshot snapshot = snapshot(project, chapter, content);
            Map<String, Object> cached = storage.causalCache(project, "draft_review", snapshot.key);
            if (!combine(snapshot, cached).revision().equals(revision))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "正文或前文已变化，请重新核对");
            if ("complete".equals(cached.get("status"))) return combine(snapshot, cached);
            List<CausalGraphEngine.Segment> segments = new ArrayList<>();
            for (Source source : snapshot.sources) for (int[] range : CausalExtractor.segments(source.text())) {
                String key = extractionKey(snapshot.model, source, range);
                Map<String, Object> extraction = storage.causalCache(project, "extraction", key);
                if (extraction.isEmpty())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "前文因果提取未同步，请先完成前文因果核查");
                segments.add(new CausalGraphEngine.Segment(source, range[0], range[1],
                        mapper.convertValue(extraction, Extraction.class)));
            }
            Source draft = new Source(AnalysisStorage.chapter(chapter), content, AnalysisStorage.hash(content));
            var ranges = CausalExtractor.segments(content);
            if (ranges.size() > 2)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "草稿超出单次语义核查范围，请拆分至 16000 字符以内");
            String job = UUID.randomUUID().toString();
            var account = new AnalysisContext.Account(job, maxTokens, 3, 4, 0, event -> storage.event(project, event));
            var options = new AnalysisProperties();
            options.setBulkMode(true);
            List<Issue> issues = new ArrayList<>();
            List<String> excerpts = new ArrayList<>();
            String status = "complete";
            try (var ignored = new AnalysisContext(account, chapter, "draft_semantic", options)) {
                Set<String> existingCandidates = new HashSet<>();
                new CausalGraphEngine().build(segments).candidates().forEach(c -> existingCandidates.add(c.id()));
                for (int[] range : ranges) {
                    account.check();
                    String key = extractionKey(snapshot.model, draft, range);
                    var saved = storage.causalCache(project, "extraction", key);
                    Extraction extraction;
                    if (saved.isEmpty()) {
                        requireModel(snapshot.model);
                        extraction = extractor.extract(draft, range[0], range[1]);
                        requireModel(snapshot.model);
                        storage.causalCache(project, "extraction", key, data(extraction));
                    } else extraction = mapper.convertValue(saved, Extraction.class);
                    segments.add(new CausalGraphEngine.Segment(draft, range[0], range[1], extraction));
                }
                Graph graph = new CausalGraphEngine().build(segments);
                if (!graph.uncertainChapters().isEmpty()) {
                    issues.add(new Issue("semantic_coverage", "warning", "", 0,
                            "因果链存在提取缺口、身份或时间顺序不明，不能确认完整连续性。"));
                }
                List<Candidate> candidates = graph.candidates().stream()
                        .filter(c -> c.current().chapter().equals(draft.chapter()) || !existingCandidates.contains(c.id()))
                        .toList();
                Map<String, Source> byChapter = new HashMap<>();
                snapshot.sources.forEach(s -> byChapter.put(s.chapter(), s));
                byChapter.put(draft.chapter(), draft);
                int verified = 0;
                for (Candidate candidate : candidates) {
                    account.check();
                    if (candidate.prior() == null || verified >= 2) {
                        issues.add(issue(candidate, draft, "前提证据缺失或达到单次复核上限，请核对这一事件状态。"));
                        continue;
                    }
                    String prior = excerpt(byChapter.get(candidate.prior().chapter()), candidate.prior());
                    String current = excerpt(byChapter.get(candidate.current().chapter()), candidate.current());
                    String key = storage.fingerprint(List.of(VERSION, snapshot.key, candidate, prior, current));
                    Map<String, Object> saved = storage.causalCache(project, "verification", key);
                    Verdict verdict;
                    if (saved.isEmpty()) {
                        requireModel(snapshot.model);
                        verdict = extractor.verify(candidate, prior, current);
                        requireModel(snapshot.model);
                        storage.causalCache(project, "verification", key, data(verdict));
                        verified++;
                    } else verdict = mapper.convertValue(saved, Verdict.class);
                    if ("explained".equals(verdict.verdict()) && verdict.confidence() >= 0.8) continue;
                    boolean supported = "conflict".equals(verdict.verdict()) && verdict.confidence() >= 0.8
                            && Set.of("state_mismatch", "resurrection_or_conflict").contains(candidate.kind());
                    issues.add(issue(candidate, draft,
                            (supported ? "存在相互矛盾的状态证据：" : "状态仍需核实：") + verdict.explanation()));
                    excerpts.add("[" + candidate.prior().chapter() + "] " + candidate.prior().quote());
                }
            } catch (AnalysisContext.Paused e) {
                status = "partial";
                issues.add(new Issue("semantic_incomplete", "warning", "", 0, "语义核查达到预算或时间上限，未完成不代表通过。"));
            } catch (RuntimeException e) {
                status = "partial";
                issues.add(new Issue("semantic_incomplete", "warning", "", 0, "语义核查未完成，请重试或人工核对。"));
            }
            if (!snapshot.key.equals(snapshot(project, chapter, content).key))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "核查期间前文或模型配置变化，结果已失效");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("issues", issues);
            result.put("excerpts", String.join("\n", excerpts.stream().distinct().limit(10).toList()));
            result.put("metrics", account.metrics()); result.put("job_id", job);
            storage.causalCache(project, "draft_review", snapshot.key, result);
            return combine(snapshot, result);
        } finally { lock.unlock(); }
    }

    private Report combine(Snapshot snapshot, Map<String, Object> semantic) {
        Report local = snapshot.local;
        String status = Objects.toString(semantic.get("status"), "unchecked");
        List<Issue> issues = new ArrayList<>(local.issues());
        if (semantic.isEmpty()) {
            issues.add(new Issue("semantic_unchecked", "warning", "", 0, "草稿尚未进行跨章语义核查。"));
        } else {
            issues.addAll(mapper.convertValue(semantic.get("issues"), new TypeReference<List<Issue>>() {}));
        }
        String revision = storage.fingerprint(List.of(snapshot.key, status, issues));
        return new Report(revision, local.source_chapter(), local.source_hash(), local.draft_hash(),
                "complete".equals(status) ? "local_and_semantic" : "local_rules_only",
                local.truncated() || issues.size() > 30, List.copyOf(issues.subList(0, Math.min(30, issues.size()))),
                (local.reference_excerpts() + "\n" + semantic.getOrDefault("excerpts", "")).strip(), status);
    }

    private String extractionKey(String model, Source source, int[] range) {
        return storage.fingerprint(List.of(CausalExtractor.VERSION, model, source.chapter(),
                AnalysisStorage.hash(source.text().substring(range[0], range[1]))));
    }
    private Issue issue(Candidate candidate, Source draft, String message) {
        boolean inDraft = candidate.current().chapter().equals(draft.chapter());
        return new Issue("semantic_" + candidate.kind(), "warning",
                (inDraft ? "" : "[" + candidate.current().chapter() + "] ") + candidate.current().quote(),
                inDraft ? candidate.current().start() : 0, message);
    }
    private String excerpt(Source source, Evidence evidence) {
        if (!source.hash().equals(evidence.sourceHash())) throw new IllegalStateException("Stale semantic evidence");
        return source.text().substring(Math.max(0, evidence.start() - 1200), Math.min(source.text().length(), evidence.end() + 1200));
    }
    private void requireModel(String model) {
        if (!model.equals(gateway.analysisFingerprint())) throw new IllegalStateException("Model changed");
    }
    private Map<String, Object> data(Object value) { return mapper.convertValue(value, new TypeReference<>() {}); }
}
