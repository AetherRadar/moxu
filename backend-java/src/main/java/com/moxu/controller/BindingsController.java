package com.moxu.controller;

import com.moxu.config.BindingsProperties;
import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.WorldCard;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.search.TextSearchUtils;
import com.moxu.storage.BindingsStateStorage;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DraftStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节绑定控制器 - 关联角色/世界观卡片到章节文本
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/bindings")
@RequiredArgsConstructor
public class BindingsController {

    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    private final BindingsProperties bindingsProperties;
    private final ProjectLexiconResolver projectLexiconResolver;
    private final BindingsStateStorage bindingsStateStorage;

    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)");
    private static final Set<String> NAME_STOPWORDS = Set.of(
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "它们",
            "这里", "那里", "自己", "大家", "众人", "某人", "某某"
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "城", "镇", "村", "山", "河", "湖", "海", "国", "帝国", "王国", "共和国",
            "联盟", "组织", "宗", "派", "门派", "宗门", "学院", "公司", "家族", "氏族", "世界",
            "院", "阁", "宫", "殿", "府", "堂", "会", "盟", "塔"
    );
    private static final List<String> GENERIC_SUFFIXES = List.of(
            "共和国", "帝国", "王国", "联盟", "组织", "宗门", "门派", "学院", "公司", "家族", "氏族", "世界",
            "监狱", "学院", "集团", "宗", "派", "门", "院", "阁", "宫", "殿", "府", "堂", "会", "盟", "塔",
            "城", "镇", "村", "山", "河", "湖", "海", "国"
    );
    private static final Set<String> RULE_HINTS = Set.of(
            "必须", "不得", "不能", "不可", "禁止", "代价", "限制", "规则", "约束"
    );
    private static final int MAX_EXAMPLES = 2;
    private static final int SNIPPET_RADIUS = 12;
    private static final Set<String> WEAK_ALIAS_SUPPORT_TOKENS = Set.of(
            "宗", "门", "派", "院", "阁", "宫", "殿", "府", "堂", "会", "盟", "塔",
            "城", "镇", "村", "山", "河", "湖", "海", "国", "界"
    );
    private final Object staleLock = new Object();
    private final Object queueLock = new Object();
    private final Set<String> rebuildingKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> queuedKeys = new HashSet<>();
    private final Set<String> rerunRequestedKeys = new HashSet<>();
    private final Deque<QueuedRebuildTask> highPriorityQueue = new ArrayDeque<>();
    private final Deque<QueuedRebuildTask> normalPriorityQueue = new ArrayDeque<>();
    private final ExecutorService backgroundRebuildExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private int activeRebuildWorkers = 0;

    @PreDestroy
    void shutdownExecutor() {
        backgroundRebuildExecutor.close();
    }

    @GetMapping("/{chapter}")
    public Map<String, Object> getBindings(
            @PathVariable String projectId,
            @PathVariable String chapter) {

        String canonical = canonicalizeChapterId(chapter);
        boolean stale = isBindingStale(projectId, canonical);
        if (stale) {
            scheduleBackgroundRebuild(projectId, List.of(canonical), "lazy_open");
        }

        Map<String, Object> binding = readBindings(projectId, canonical);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("binding", binding != null ? binding : Collections.emptyMap());
        response.put("stale", stale);
        response.put("rebuilding", isBindingRebuilding(projectId, canonical));
        return response;
    }

    @PostMapping("/{chapter}/rebuild")
    public Map<String, Object> rebuildBindings(
            @PathVariable String projectId,
            @PathVariable String chapter) throws IOException {

        Map<String, Object> binding = buildBindings(projectId, chapter, true);
        return Map.of("success", true, "binding", binding);
    }

    public Map<String, Object> rebuildBindingsForChapter(String projectId, String chapter) {
        try {
            return buildBindings(projectId, chapter, true);
        } catch (IOException e) {
            log.warn("重建绑定失败: {}/{}", projectId, chapter, e);
            return Collections.emptyMap();
        }
    }

    public void markBindingsStale(String projectId, Collection<String> chapters, String reason, boolean scheduleBackground) {
        LinkedHashSet<String> canonicalChapters = canonicalizeChapterIds(chapters);
        if (canonicalChapters.isEmpty()) {
            return;
        }

        synchronized (staleLock) {
            LinkedHashSet<String> staleChapters = new LinkedHashSet<>(bindingsStateStorage.getStaleChapters(projectId));
            staleChapters.addAll(canonicalChapters);
            try {
                bindingsStateStorage.saveStaleChapters(projectId, staleChapters);
            } catch (IOException e) {
                log.warn("标记 bindings stale 失败: project={}, chapters={}, reason={}, error={}",
                        projectId, canonicalChapters, reason, e.getMessage());
                return;
            }
        }

        log.info("Marked bindings stale: project={}, chapters={}, reason={}", projectId, canonicalChapters, reason);
        if (scheduleBackground) {
            scheduleBackgroundRebuild(projectId, canonicalChapters, reason);
        }
    }

    public void markAllBindingsStale(String projectId, String reason, boolean scheduleBackground) {
        markBindingsStale(projectId, draftStorage.listChapters(projectId), reason, scheduleBackground);
    }

    public boolean isBindingStale(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        synchronized (staleLock) {
            return bindingsStateStorage.getStaleChapters(projectId).contains(canonical);
        }
    }

    public boolean isBindingRebuilding(String projectId, String chapter) {
        String key = rebuildKey(projectId, canonicalizeChapterId(chapter));
        if (rebuildingKeys.contains(key)) {
            return true;
        }
        synchronized (queueLock) {
            return queuedKeys.contains(key);
        }
    }

    public boolean deleteBindingsForChapter(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path bindingPath = getBindingPath(projectId, canonical);
        Path legacyPath = getLegacyBindingPath(projectId, canonical);

        boolean deleted = false;
        try {
            deleted |= Files.deleteIfExists(bindingPath);
        } catch (IOException e) {
            log.warn("删除 bindings 失败: {}", bindingPath, e);
        }
        try {
            deleted |= Files.deleteIfExists(legacyPath);
        } catch (IOException e) {
            log.warn("删除 legacy bindings 失败: {}", legacyPath, e);
        }

        tryRemoveEmptyParent(bindingPath);
        tryRemoveEmptyParent(legacyPath);
        clearStaleChapter(projectId, canonical);
        return deleted;
    }

    @PostMapping("/rebuild-batch")
    public Map<String, Object> rebuildBindingsBatch(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) throws IOException {

        @SuppressWarnings("unchecked")
        List<String> chapters = request != null ? (List<String>) request.get("chapters") : null;

        if (chapters == null || chapters.isEmpty()) {
            chapters = draftStorage.listChapters(projectId);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String chapter : chapters) {
            try {
                Map<String, Object> binding = buildBindings(projectId, chapter, true);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", true,
                        "binding", binding
                ));
            } catch (Exception e) {
                log.error("重建绑定失败: {}/{}", projectId, chapter, e);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }

        return Map.of("success", true, "results", results);
    }

    private Map<String, Object> readBindings(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path bindingPath = getBindingPath(projectId, canonical);
        Path legacyPath = getLegacyBindingPath(projectId, canonical);

        if (bindingPath == null || !Files.exists(bindingPath)) {
            if (legacyPath == null || !Files.exists(legacyPath)) {
                return null;
            }
            bindingPath = legacyPath;
        }

        try {
            Map<String, Object> raw = draftStorage.readYamlAsMap(bindingPath);
            return normalizeBindingPayload(raw, canonical);
        } catch (IOException e) {
            log.error("读取绑定失败: {}", bindingPath, e);
            return null;
        }
    }

    private Map<String, Object> buildBindings(String projectId, String chapter, boolean force) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path bindingPath = getBindingPath(projectId, canonical);

        if (!force && bindingPath != null && Files.exists(bindingPath)) {
            Map<String, Object> existing = readBindings(projectId, canonical);
            if (existing != null) {
                return existing;
            }
        }

        Optional<String> draftOpt = draftStorage.getFinalDraft(projectId, canonical);
        if (draftOpt.isEmpty() || draftOpt.get().isBlank()) {
            Map<String, Object> empty = emptyPayload(canonical);
            draftStorage.writeYaml(bindingPath, empty);
            clearStaleChapter(projectId, canonical);
            return empty;
        }

        String content = draftOpt.get();
        List<String> chunks = splitForBinding(content);
        Set<String> recentEntities = new LinkedHashSet<>(getSeedEntities(projectId, canonical, 2));
        ProjectLexicon projectLexicon = resolveProjectLexicon(projectId);

        List<Hit> characterHits = new ArrayList<>();
        List<Hit> worldHits = new ArrayList<>();
        List<Hit> ruleHits = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();

        for (String name : cardStorage.listCharacterCards(projectId)) {
            Optional<CharacterCard> cardOpt = cardStorage.getCharacterCard(projectId, name);
            if (cardOpt.isEmpty()) {
                continue;
            }
            CharacterCard card = cardOpt.get();
            ScoreData score = scoreCandidate(
                    content,
                    chunks,
                    name,
                    card.getAliases(),
                    recentEntities,
                    true,
                    projectLexicon
            );
            if (!score.matched) {
                continue;
            }
            characterHits.add(new Hit(name, score.score));
            sources.add(entitySource(name, "character", score));
        }

        for (String name : cardStorage.listWorldCards(projectId)) {
            Optional<WorldCard> cardOpt = cardStorage.getWorldCard(projectId, name);
            if (cardOpt.isEmpty()) {
                continue;
            }
            WorldCard card = cardOpt.get();
            ScoreData entityScore = scoreCandidate(
                    content,
                    chunks,
                    name,
                    card.getAliases(),
                    recentEntities,
                    false,
                    projectLexicon
            );
            if (entityScore.matched) {
                worldHits.add(new Hit(name, entityScore.score));
                sources.add(entitySource(name, "world_entity", entityScore));
            }

            List<String> rules = card.getRules() == null ? List.of() : card.getRules();
            for (int i = 0; i < rules.size(); i++) {
                String ruleText = stringValue(rules.get(i));
                if (ruleText.isBlank()) {
                    continue;
                }
                String ruleId = "world_rule:" + name + ":" + (i + 1);
                RuleScoreData ruleScore = scoreRule(content, chunks, ruleText, projectLexicon);
                if (!ruleScore.matched) {
                    continue;
                }
                ruleHits.add(new Hit(ruleId, ruleScore.score));
                sources.add(ruleSource(ruleId, ruleText, ruleScore));
            }
        }

        sortHits(characterHits);
        sortHits(worldHits);
        sortHits(ruleHits);
        sources.sort((a, b) -> Double.compare(
                numberValue(b.get("score"), 0.0),
                numberValue(a.get("score"), 0.0)
        ));

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("chapter", canonical);
        binding.put("characters", hitNames(characterHits));
        binding.put("world_entities", hitNames(worldHits));
        binding.put("world_rules", hitNames(ruleHits));
        binding.put("world", hitNames(worldHits));
        binding.put("sources", sources);
        binding.put("draft_path", "drafts/" + canonical + "/final.md");
        binding.put("built_at", System.currentTimeMillis() / 1000.0);

        draftStorage.writeYaml(bindingPath, binding);
        clearStaleChapter(projectId, canonical);
        return binding;
    }

    private void scheduleBackgroundRebuild(String projectId, Collection<String> chapters, String reason) {
        LinkedHashSet<String> canonicalChapters = canonicalizeChapterIds(chapters);
        if (canonicalChapters.isEmpty()) {
            return;
        }

        boolean highPriority = isHighPriorityReason(reason);
        int queued = 0;
        int promoted = 0;
        int rerunMarked = 0;

        synchronized (queueLock) {
            for (String chapter : canonicalChapters) {
                String key = rebuildKey(projectId, chapter);

                if (rebuildingKeys.contains(key)) {
                    if (shouldRequestRerun(reason)) {
                        rerunRequestedKeys.add(key);
                        rerunMarked++;
                    }
                    continue;
                }

                if (queuedKeys.contains(key)) {
                    if (highPriority && promoteQueuedTaskLocked(key)) {
                        promoted++;
                    }
                    continue;
                }

                enqueueTaskLocked(new QueuedRebuildTask(projectId, chapter, reason, highPriority));
                queued++;
            }

            dispatchQueuedRebuildsLocked();
        }

        if (queued > 0 || promoted > 0 || rerunMarked > 0) {
            log.info("Queued background bindings rebuild: project={}, queued={}, promoted={}, rerun_marked={}, reason={}",
                    projectId, queued, promoted, rerunMarked, reason);
        }
    }

    private void enqueueTaskLocked(QueuedRebuildTask task) {
        if (task.highPriority()) {
            highPriorityQueue.offerLast(task);
        } else {
            normalPriorityQueue.offerLast(task);
        }
        queuedKeys.add(task.key());
    }

    private boolean promoteQueuedTaskLocked(String key) {
        Iterator<QueuedRebuildTask> iterator = normalPriorityQueue.iterator();
        while (iterator.hasNext()) {
            QueuedRebuildTask task = iterator.next();
            if (!task.key().equals(key)) {
                continue;
            }
            iterator.remove();
            highPriorityQueue.offerLast(task.promote());
            return true;
        }
        return false;
    }

    private void dispatchQueuedRebuildsLocked() {
        int maxConcurrency = maxBackgroundConcurrency();
        while (activeRebuildWorkers < maxConcurrency) {
            QueuedRebuildTask task = pollNextTaskLocked();
            if (task == null) {
                return;
            }

            rebuildingKeys.add(task.key());
            activeRebuildWorkers++;
            backgroundRebuildExecutor.submit(() -> runBackgroundRebuild(task));
        }
    }

    private QueuedRebuildTask pollNextTaskLocked() {
        QueuedRebuildTask task = highPriorityQueue.pollFirst();
        if (task == null) {
            task = normalPriorityQueue.pollFirst();
        }
        if (task != null) {
            queuedKeys.remove(task.key());
        }
        return task;
    }

    private void runBackgroundRebuild(QueuedRebuildTask task) {
        try {
            log.info("Background rebuild bindings start: project={}, chapter={}, reason={}, priority={}",
                    task.projectId(), task.chapter(), task.reason(), task.highPriority() ? "high" : "normal");
            buildBindings(task.projectId(), task.chapter(), true);
            log.info("Background rebuild bindings done: project={}, chapter={}, reason={}, priority={}",
                    task.projectId(), task.chapter(), task.reason(), task.highPriority() ? "high" : "normal");
        } catch (Exception e) {
            log.warn("后台重建 bindings 失败: project={}, chapter={}, reason={}, error={}",
                    task.projectId(), task.chapter(), task.reason(), e.getMessage());
        } finally {
            synchronized (queueLock) {
                rebuildingKeys.remove(task.key());
                activeRebuildWorkers = Math.max(0, activeRebuildWorkers - 1);

                if (rerunRequestedKeys.remove(task.key())
                        && !queuedKeys.contains(task.key())
                        && !rebuildingKeys.contains(task.key())) {
                    enqueueTaskLocked(task.withReason("rerun_after_active"));
                }

                dispatchQueuedRebuildsLocked();
            }
        }
    }

    private boolean isHighPriorityReason(String reason) {
        String normalized = stringValue(reason).toLowerCase(Locale.ROOT);
        return normalized.startsWith("lazy_") || normalized.startsWith("open_");
    }

    private boolean shouldRequestRerun(String reason) {
        String normalized = stringValue(reason).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        return !normalized.startsWith("lazy_") && !normalized.startsWith("open_");
    }

    private int maxBackgroundConcurrency() {
        if (bindingsProperties == null || bindingsProperties.getAsync() == null) {
            return 4;
        }
        return Math.max(1, bindingsProperties.getAsync().getMaxConcurrentRebuilds());
    }

    private void clearStaleChapter(String projectId, String chapter) {
        synchronized (staleLock) {
            LinkedHashSet<String> staleChapters = new LinkedHashSet<>(bindingsStateStorage.getStaleChapters(projectId));
            if (!staleChapters.remove(chapter)) {
                return;
            }
            try {
                bindingsStateStorage.saveStaleChapters(projectId, staleChapters);
            } catch (IOException e) {
                log.warn("清除 bindings stale 失败: project={}, chapter={}, error={}", projectId, chapter, e.getMessage());
            }
        }
    }

    private LinkedHashSet<String> canonicalizeChapterIds(Collection<String> chapters) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (chapters == null) {
            return result;
        }
        for (String chapter : chapters) {
            String canonical = canonicalizeChapterId(chapter);
            if (!canonical.isBlank()) {
                result.add(canonical);
            }
        }
        return result;
    }

    private String rebuildKey(String projectId, String chapter) {
        return projectId + "::" + chapter;
    }

    private record QueuedRebuildTask(String projectId, String chapter, String reason, boolean highPriority) {
        private String key() {
            return projectId + "::" + chapter;
        }

        private QueuedRebuildTask promote() {
            if (highPriority) {
                return this;
            }
            return new QueuedRebuildTask(projectId, chapter, reason, true);
        }

        private QueuedRebuildTask withReason(String newReason) {
            return new QueuedRebuildTask(projectId, chapter, newReason, highPriority);
        }
    }

    private Map<String, Object> normalizeBindingPayload(Map<String, Object> raw, String canonicalChapter) {
        if (raw == null) {
            return null;
        }

        List<String> characters = normalizeNameList(raw.get("characters"));
        List<String> worldEntities = normalizeNameList(raw.get("world_entities"));
        if (worldEntities.isEmpty()) {
            worldEntities = normalizeNameList(raw.get("world"));
        }
        List<String> worldRules = normalizeWorldRuleList(raw.get("world_rules"));
        List<Map<String, Object>> sources = normalizeSources(raw.get("sources"));

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("chapter", stringValue(raw.get("chapter")).isBlank() ? canonicalChapter : stringValue(raw.get("chapter")));
        normalized.put("characters", characters);
        normalized.put("world_entities", worldEntities);
        normalized.put("world_rules", worldRules);
        normalized.put("world", worldEntities);
        normalized.put("sources", sources);
        normalized.put("draft_path", stringValue(raw.get("draft_path")));
        normalized.put("built_at", numberValue(raw.get("built_at"), numberValue(raw.get("updated_at"), System.currentTimeMillis() / 1000.0)));
        return normalized;
    }

    private List<String> normalizeNameList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String s) {
                String name = s.trim();
                if (!name.isBlank()) {
                    names.add(name);
                }
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                String name = stringValue(map.get("name"));
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    private List<String> normalizeWorldRuleList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int fallbackIndex = 1;
        for (Object item : list) {
            if (item instanceof String s) {
                String ruleId = s.trim();
                if (!ruleId.isBlank()) {
                    ids.add(ruleId);
                }
                continue;
            }
            if (item instanceof Map<?, ?> map) {
                String ruleId = stringValue(map.get("rule_id"));
                if (ruleId.isBlank()) {
                    String worldCard = stringValue(map.get("world_card"));
                    if (!worldCard.isBlank()) {
                        ruleId = "world_rule:" + worldCard + ":" + fallbackIndex;
                    }
                }
                if (!ruleId.isBlank()) {
                    ids.add(ruleId);
                    fallbackIndex++;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private List<Map<String, Object>> normalizeSources(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                source.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            sources.add(source);
        }
        return sources;
    }

    private List<String> getSeedEntities(String projectId, String chapter, int window) {
        List<String> chapters = draftStorage.listChapters(projectId);
        if (chapters == null || chapters.isEmpty() || window <= 0) {
            return List.of();
        }

        String canonical = canonicalizeChapterId(chapter);
        List<String> normalized = new ArrayList<>();
        for (String item : chapters) {
            String chapterId = canonicalizeChapterId(item);
            if (!chapterId.isBlank() && !normalized.contains(chapterId)) {
                normalized.add(chapterId);
            }
        }

        int index = normalized.indexOf(canonical);
        int end = index >= 0 ? index : normalized.size();
        int start = Math.max(0, end - window);
        LinkedHashSet<String> seeds = new LinkedHashSet<>();
        for (String recentChapter : normalized.subList(start, end)) {
            Map<String, Object> binding = readBindings(projectId, recentChapter);
            if (binding == null) {
                continue;
            }
            seeds.addAll(normalizeNameList(binding.get("characters")));
            seeds.addAll(normalizeNameList(binding.get("world_entities")));
        }
        return new ArrayList<>(seeds);
    }

    private ScoreData scoreCandidate(String text,
                                     List<String> chunks,
                                     String name,
                                     List<String> aliases,
                                     Set<String> recentEntities,
                                     boolean characterMode,
                                     ProjectLexicon projectLexicon) {
        List<String> searchAliases = normalizeAliases(name, aliases, characterMode ? 1 : 2);
        int totalCount = 0;
        List<String> matchedAliases = new ArrayList<>();
        List<String> examples = new ArrayList<>();

        for (String alias : searchAliases) {
            Occurrence occurrence = findOccurrences(text, alias);
            if (occurrence.count <= 0) {
                continue;
            }
            totalCount += occurrence.count;
            if (!matchedAliases.contains(alias)) {
                matchedAliases.add(alias);
            }
            for (String example : occurrence.examples) {
                if (examples.size() >= MAX_EXAMPLES || examples.contains(example)) {
                    continue;
                }
                examples.add(example);
            }
        }

        double bestScore = 0.0;
        if (totalCount == 0) {
            for (String alias : searchAliases) {
                List<String> candidateTerms = extractCandidateTerms(alias, projectLexicon);
                MatchScore match = bestBm25Match(alias, candidateTerms, chunks, projectLexicon);
                double threshold = candidateThreshold(alias, projectLexicon);
                if (match.termHits < requiredAliasTermHits(alias, candidateTerms, projectLexicon) || match.score < threshold) {
                    continue;
                }
                if (!hasStrongNonExactEvidence(text, alias, candidateTerms, projectLexicon)) {
                    continue;
                }
                if (match.score > bestScore) {
                    bestScore = match.score;
                    matchedAliases = new ArrayList<>(List.of(alias));
                    examples = new ArrayList<>();
                    if (!match.example.isBlank()) {
                        examples.add(match.example);
                    }
                }
            }
        }

        double score = totalCount * 2.0 + bestScore;
        if (recentEntities.contains(name)) {
            score += 0.8;
        }
        return new ScoreData(totalCount > 0 || bestScore > 0, score, totalCount, matchedAliases, examples);
    }

    private RuleScoreData scoreRule(String text, List<String> chunks, String ruleText, ProjectLexicon projectLexicon) {
        List<String> terms = extractTerms(ruleText, projectLexicon);
        if (terms.isEmpty()) {
            return new RuleScoreData(false, 0.0, List.of());
        }
        if (termOverlap(text, terms) < requiredRuleOverlap(ruleText, terms)) {
            return new RuleScoreData(false, 0.0, List.of());
        }

        MatchScore match = bestBm25Match(ruleText, chunks, projectLexicon);
        double score = match.score;
        String example = match.example;
        if (!ruleText.isBlank() && text.contains(ruleText)) {
            score += 0.8;
            if (example.isBlank()) {
                example = makeChunkSnippet(ruleText);
            }
        }
        if (score < ruleThreshold(ruleText, terms)) {
            return new RuleScoreData(false, 0.0, List.of());
        }
        List<String> examples = example.isBlank() ? List.of() : List.of(example);
        return new RuleScoreData(true, score, examples);
    }

    private MatchScore bestBm25Match(String query, List<String> chunks, ProjectLexicon projectLexicon) {
        return bestBm25Match(query, extractTerms(query, projectLexicon), chunks, projectLexicon);
    }

    private MatchScore bestBm25Match(String query,
                                     List<String> queryTerms,
                                     List<String> chunks,
                                     ProjectLexicon projectLexicon) {
        List<String> terms = queryTerms == null || queryTerms.isEmpty()
                ? extractTerms(query, projectLexicon)
                : queryTerms;
        if (terms.isEmpty() || chunks == null || chunks.isEmpty()) {
            return new MatchScore(0.0, "", 0);
        }

        Map<String, Integer> df = new LinkedHashMap<>();
        for (String term : terms) {
            int count = 0;
            for (String chunk : chunks) {
                if (countTerm(chunk, term) > 0) {
                    count++;
                }
            }
            df.put(term, count);
        }

        int totalDocs = Math.max(chunks.size(), 1);
        double avgdl = chunks.stream().mapToInt(chunk -> estimateDocLen(chunk, projectLexicon)).average().orElse(1.0);
        double bestScore = 0.0;
        String bestChunk = "";
        int bestHits = 0;

        double k1 = 1.2;
        double b = 0.75;
        for (String chunk : chunks) {
            int dl = Math.max(1, estimateDocLen(chunk, projectLexicon));
            double score = 0.0;
            int termHits = 0;
            for (String term : terms) {
                int tf = countTerm(chunk, term);
                if (tf <= 0) {
                    continue;
                }
                termHits++;
                int dfi = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (totalDocs - dfi + 0.5) / (dfi + 0.5));
                score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl));
            }
            if (score > bestScore) {
                bestScore = score;
                bestChunk = chunk;
                bestHits = termHits;
            }
        }

        return new MatchScore(bestScore, bestChunk.isBlank() ? "" : makeChunkSnippet(bestChunk), bestHits);
    }

    private List<String> extractCandidateTerms(String alias, ProjectLexicon projectLexicon) {
        List<String> rawTerms = extractTerms(alias, projectLexicon);
        if (rawTerms.isEmpty()) {
            return List.of();
        }

        String normalizedAlias = stringValue(alias).trim();
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (String term : rawTerms) {
            String normalizedTerm = stringValue(term).trim();
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (isWeakAliasSupportToken(normalizedAlias, normalizedTerm)) {
                continue;
            }
            filtered.add(normalizedTerm);
        }

        if (!filtered.isEmpty()) {
            return new ArrayList<>(filtered);
        }

        LinkedHashSet<String> fallback = new LinkedHashSet<>();
        for (String term : rawTerms) {
            String normalizedTerm = stringValue(term).trim();
            if (!normalizedTerm.isBlank() && normalizedTerm.length() > 1) {
                fallback.add(normalizedTerm);
            }
        }
        return new ArrayList<>(fallback);
    }

    private boolean isWeakAliasSupportToken(String alias, String term) {
        if (term == null || term.isBlank()) {
            return true;
        }
        String normalizedAlias = stringValue(alias).trim();
        String normalizedTerm = term.trim();
        if (normalizedAlias.length() >= 3 && normalizedTerm.length() <= 1) {
            return true;
        }
        if (WEAK_ALIAS_SUPPORT_TOKENS.contains(normalizedTerm)
                && normalizedAlias.length() >= normalizedTerm.length() + 1
                && normalizedAlias.endsWith(normalizedTerm)) {
            return true;
        }
        if (!normalizedAlias.equals(normalizedTerm)
                && normalizedAlias.length() <= 4
                && endsWithWeakSupportSuffix(normalizedAlias)
                && normalizedAlias.contains(normalizedTerm)) {
            return true;
        }
        return false;
    }

    private boolean endsWithWeakSupportSuffix(String alias) {
        if (alias == null || alias.isBlank()) {
            return false;
        }
        for (String suffix : WEAK_ALIAS_SUPPORT_TOKENS) {
            if (alias.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStrongNonExactEvidence(String text,
                                              String alias,
                                              List<String> candidateTerms,
                                              ProjectLexicon projectLexicon) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (countTerm(text, alias) > 0) {
            return true;
        }
        List<String> terms = candidateTerms == null || candidateTerms.isEmpty()
                ? extractCandidateTerms(alias, projectLexicon)
                : candidateTerms;
        if (terms.isEmpty()) {
            return false;
        }
        return termOverlap(text, terms) >= requiredAliasTermHits(alias, terms, projectLexicon);
    }

    private Occurrence findOccurrences(String text, String alias) {
        if (text == null || text.isBlank() || alias == null || alias.isBlank()) {
            return new Occurrence(0, List.of());
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerAlias = alias.toLowerCase(Locale.ROOT);
        int count = 0;
        int start = 0;
        List<String> examples = new ArrayList<>();
        while (true) {
            int idx = lowerText.indexOf(lowerAlias, start);
            if (idx < 0) {
                break;
            }
            count++;
            if (examples.size() < MAX_EXAMPLES) {
                examples.add(makeSnippet(text, idx, alias.length()));
            }
            start = idx + Math.max(1, lowerAlias.length());
        }
        return new Occurrence(count, examples);
    }

    private List<String> splitForBinding(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\R\\s*\\R+");
        StringBuilder current = new StringBuilder();
        for (String raw : paragraphs) {
            String paragraph = raw == null ? "" : raw.trim();
            if (paragraph.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() + 1 > 420) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<String> normalizeAliases(String name, List<String> aliases, int minLength) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String primaryName = stringValue(name).trim();
        if (!stringValue(name).isBlank()) {
            result.add(primaryName);
        }
        if (aliases != null) {
            for (String alias : aliases) {
                String cleaned = stringValue(alias);
                if (!cleaned.isBlank()) {
                    result.add(cleaned);
                }
            }
        }

        List<String> normalized = new ArrayList<>();
        for (String alias : result) {
            if (alias.length() < minLength || NAME_STOPWORDS.contains(alias)) {
                continue;
            }
            if (!alias.equals(primaryName) && isExactGenericAlias(alias)) {
                continue;
            }
            normalized.add(alias);
        }
        return normalized;
    }

    private List<String> extractTerms(String text, ProjectLexicon projectLexicon) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return TextSearchUtils.tokenSet(text, projectLexicon).stream()
                .filter(term -> !NAME_STOPWORDS.contains(term))
                .toList();
    }

    private int minTermHits(String alias, ProjectLexicon projectLexicon) {
        int termCount = extractCandidateTerms(alias, projectLexicon).size();
        if (termCount <= 2) {
            return 1;
        }
        if (termCount <= 4) {
            return 2;
        }
        return 3;
    }

    private int requiredAliasTermHits(String alias, ProjectLexicon projectLexicon) {
        return requiredAliasTermHits(alias, extractCandidateTerms(alias, projectLexicon), projectLexicon);
    }

    private int requiredAliasTermHits(String alias, List<String> terms, ProjectLexicon projectLexicon) {
        int base = minTermHits(alias, projectLexicon);
        int length = textLength(alias);
        if (isGenericName(alias) && length <= 3) {
            return Math.max(base, 2);
        }
        int termCount = terms == null ? 0 : terms.size();
        if (length >= 6 || termCount >= 4) {
            return Math.max(1, base - 1);
        }
        return base;
    }

    private double candidateThreshold(String alias, ProjectLexicon projectLexicon) {
        int length = textLength(alias);
        int termCount = extractCandidateTerms(alias, projectLexicon).size();
        boolean generic = isGenericName(alias);
        BindingsProperties.Thresholds thresholds = bindingsProperties.getThresholds();
        double threshold = generic ? thresholds.getBm25Generic() : thresholds.getBm25();
        if (length <= 2) {
            threshold += generic
                    ? thresholds.getAliasShortGenericPenalty()
                    : thresholds.getAliasShortSpecificPenalty();
        } else if (length == 3) {
            threshold += generic
                    ? thresholds.getAliasMediumGenericPenalty()
                    : thresholds.getAliasMediumSpecificPenalty();
        } else if (length >= 6) {
            threshold -= thresholds.getAliasLongRelief();
        }
        if (termCount <= 1 && generic) {
            threshold += thresholds.getAliasGenericSingleTermPenalty();
        } else if (termCount >= 4) {
            threshold -= thresholds.getAliasRichTermRelief();
        }
        return clamp(threshold, 0.9, 1.85);
    }

    private int termOverlap(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
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

    private int estimateDocLen(String text, ProjectLexicon projectLexicon) {
        return TextSearchUtils.estimateDocLength(text, projectLexicon);
    }

    private int countTerm(String doc, String term) {
        return TextSearchUtils.countTerm(doc, term);
    }

    private ProjectLexicon resolveProjectLexicon(String projectId) {
        return projectLexiconResolver == null ? ProjectLexicon.empty() : projectLexiconResolver.resolve(projectId);
    }

    private boolean isGenericName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String cleaned = name.trim();
        if (NAME_STOPWORDS.contains(cleaned) || GENERIC_TERMS.contains(cleaned)) {
            return true;
        }
        for (String suffix : GENERIC_SUFFIXES) {
            if (cleaned.length() <= suffix.length()) {
                continue;
            }
            if (cleaned.endsWith(suffix)) {
                String stem = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
                if (stem.length() <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExactGenericAlias(String alias) {
        return alias != null && GENERIC_TERMS.contains(alias.trim());
    }

    private int requiredRuleOverlap(String ruleText, List<String> terms) {
        int termCount = terms == null ? 0 : terms.size();
        int length = textLength(ruleText);
        if (length <= 8) {
            return Math.min(Math.max(2, termCount - 1), Math.max(termCount, 2));
        }
        if (termCount <= 4) {
            return 2;
        }
        if (termCount <= 7) {
            return 3;
        }
        return 4;
    }

    private double ruleThreshold(String ruleText, List<String> terms) {
        int length = textLength(ruleText);
        int termCount = terms == null ? 0 : terms.size();
        BindingsProperties.Thresholds thresholds = bindingsProperties.getThresholds();
        double threshold = thresholds.getRule();
        if (length <= 6) {
            threshold += thresholds.getRuleShortPenalty();
        } else if (length <= 12) {
            threshold += thresholds.getRuleMediumPenalty();
        } else if (length >= 24) {
            threshold -= thresholds.getRuleLongRelief();
        }
        if (termCount <= 3) {
            threshold += thresholds.getRuleFewTermsPenalty();
        } else if (termCount >= 7) {
            threshold -= thresholds.getAliasRichTermRelief();
        }
        if (containsRuleHint(ruleText)) {
            threshold -= thresholds.getRuleHintRelief();
        }
        return clamp(threshold, 0.95, 1.55);
    }

    private boolean containsRuleHint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String hint : RULE_HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private int textLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, Object> entitySource(String entity, String type, ScoreData score) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("entity", entity);
        source.put("type", type);
        source.put("count", score.count);
        source.put("score", round(score.score));
        source.put("matched_aliases", score.matchedAliases);
        source.put("examples", score.examples);
        return source;
    }

    private Map<String, Object> ruleSource(String ruleId, String text, RuleScoreData score) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("rule_id", ruleId);
        source.put("type", "world_rule");
        source.put("score", round(score.score));
        source.put("examples", score.examples);
        source.put("text", text);
        return source;
    }

    private void sortHits(List<Hit> hits) {
        hits.sort((a, b) -> {
            int scoreCompare = Double.compare(b.score, a.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return a.name.compareTo(b.name);
        });
    }

    private List<String> hitNames(List<Hit> hits) {
        List<String> names = new ArrayList<>();
        for (Hit hit : hits) {
            names.add(hit.name);
        }
        return names;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String makeChunkSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String snippet = text.trim();
        int limit = Math.max(SNIPPET_RADIUS * 4, 80);
        if (snippet.length() > limit) {
            snippet = snippet.substring(0, limit);
        }
        return snippet.replaceAll("\\s+", " ").trim();
    }

    private String makeSnippet(String text, int start, int length) {
        int left = Math.max(0, start - SNIPPET_RADIUS);
        int right = Math.min(text.length(), start + length + SNIPPET_RADIUS);
        return text.substring(left, right).replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> emptyPayload(String chapter) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("chapter", chapter);
        empty.put("characters", List.of());
        empty.put("world_entities", List.of());
        empty.put("world_rules", List.of());
        empty.put("world", List.of());
        empty.put("sources", List.of());
        empty.put("draft_path", "drafts/" + chapter + "/final.md");
        empty.put("built_at", System.currentTimeMillis() / 1000.0);
        return empty;
    }

    private Path getBindingPath(String projectId, String chapter) {
        return draftStorage.getProjectPath(projectId)
                .resolve("index").resolve("chapters").resolve(chapter).resolve("bindings.yaml");
    }

    private Path getLegacyBindingPath(String projectId, String chapter) {
        return draftStorage.getProjectPath(projectId)
                .resolve("drafts").resolve(chapter).resolve("bindings.yaml");
    }

    private void tryRemoveEmptyParent(Path filePath) {
        if (filePath == null) {
            return;
        }
        Path parent = filePath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.deleteIfExists(parent);
        } catch (IOException ignored) {
        }
    }

    private String canonicalizeChapterId(String chapterId) {
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Hit {
        private final String name;
        private final double score;

        private Hit(String name, double score) {
            this.name = name;
            this.score = score;
        }
    }

    private static final class Occurrence {
        private final int count;
        private final List<String> examples;

        private Occurrence(int count, List<String> examples) {
            this.count = count;
            this.examples = examples;
        }
    }

    private static final class MatchScore {
        private final double score;
        private final String example;
        private final int termHits;

        private MatchScore(double score, String example, int termHits) {
            this.score = score;
            this.example = example;
            this.termHits = termHits;
        }
    }

    private static final class ScoreData {
        private final boolean matched;
        private final double score;
        private final int count;
        private final List<String> matchedAliases;
        private final List<String> examples;

        private ScoreData(boolean matched, double score, int count, List<String> matchedAliases, List<String> examples) {
            this.matched = matched;
            this.score = score;
            this.count = count;
            this.matchedAliases = matchedAliases;
            this.examples = examples;
        }
    }

    private static final class RuleScoreData {
        private final boolean matched;
        private final double score;
        private final List<String> examples;

        private RuleScoreData(boolean matched, double score, List<String> examples) {
            this.matched = matched;
            this.score = score;
            this.examples = examples;
        }
    }
}
