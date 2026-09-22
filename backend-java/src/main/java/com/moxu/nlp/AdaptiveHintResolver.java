package com.moxu.nlp;

import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.SceneBrief;
import com.moxu.model.entity.WorldCard;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.search.TextSearchUtils;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DraftStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 项目自适应 hint 解析器。
 *
 * <p>核心目标：
 * <ul>
 *     <li>保留少量全局通用语义 hint；</li>
 *     <li>按题材补入通用 genre pack；</li>
 *     <li>基于当前项目 cards / summaries / canon / scene brief / overlay texts 提取高频术语；</li>
 *     <li>让 Agent 依赖“动态档案”，而不是不断向源码里硬塞当前小说热词。</li>
 * </ul>
 */
public final class AdaptiveHintResolver {

    private static final int MAX_LAYER_TERMS = 20;
    private static final int MAX_PROJECT_TERMS = 24;
    private static final int MAX_VOLUME_SUMMARIES = 8;
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("^v\\d+c\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT_ONLY_PATTERN = Pattern.compile("^\\d+$");
    private static final Pattern SEARCHABLE_PATTERN = Pattern.compile(".*([\\p{IsHan}]|[a-z0-9_]).*");
    private static final Set<String> ADAPTIVE_STOPWORDS = Set.of(
            "本章", "当前", "当前章", "章节", "剧情", "场景", "故事", "人物", "角色", "设定", "规则", "事实",
            "摘要", "线索", "细节", "目标", "变化", "情况", "问题", "答案", "内容", "方式", "时候", "地方",
            "东西", "事情", "这里", "那里", "这个", "那个", "这些", "那些", "自己", "他们", "她们", "我们", "你们",
            "因为", "所以", "然后", "但是", "如果", "已经", "没有", "不是", "不能", "不会", "开始", "继续", "最后",
            "发现", "确认", "决定", "认为", "觉得", "似乎", "仿佛", "好像", "一种", "一些", "一处", "一间", "一座", "一片"
    );
    private static final Set<String> GENERIC_DISCOVERY_TERMS = Set.of(
            "入口", "遗迹", "线索", "证据", "记录", "痕迹", "异常", "反应", "资源", "代价"
    );
    private static final Set<String> GENERIC_MECHANISM_TERMS = Set.of(
            "机制", "条件", "触发", "反应", "关联", "影响", "用途", "代价", "限制", "来源"
    );
    private static final Set<String> GENERIC_DURABLE_TERMS = Set.of(
            "规则", "禁忌", "代价", "本质", "机制", "来源", "身份", "关系", "获得", "失去",
            "限制", "资源", "证据", "记录", "入口", "遗迹", "线索"
    );
    private static final Set<String> GENERIC_STATE_TERMS = Set.of(
            "位置", "情绪", "目标", "伤势", "持有", "资源", "关系", "限制", "异动"
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "来源", "代价", "机制", "影响", "联系", "入口", "线索", "证据", "异常", "反应"
    );
    private static final Set<String> GENERIC_CARRYOVER_TERMS = Set.of(
            "遗失", "见底", "匮乏", "受困", "伤势", "补给", "资源"
    );
    private static final Map<String, GenrePack> GENRE_PACKS = buildGenrePacks();

    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    private final CanonStorage canonStorage;
    private final ProjectLexiconResolver projectLexiconResolver;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AdaptiveHintResolver(CardStorage cardStorage,
                                DraftStorage draftStorage,
                                CanonStorage canonStorage) {
        this.cardStorage = cardStorage;
        this.draftStorage = draftStorage;
        this.canonStorage = canonStorage;
        this.projectLexiconResolver = new ProjectLexiconResolver(cardStorage);
    }

    public AdaptiveHintProfile resolve(String projectId, String chapter) {
        return resolve(projectId, chapter, List.of());
    }

    public AdaptiveHintProfile resolve(String projectId, String chapter, Collection<String> overlayTexts) {
        String normalizedProject = projectId == null ? "" : projectId.trim();
        if (normalizedProject.isBlank()) {
            return AdaptiveHintProfile.empty();
        }
        String normalizedChapter = chapter == null ? "" : chapter.trim().toUpperCase(Locale.ROOT);
        String cacheKey = normalizedProject + "::" + normalizedChapter;
        long stamp = projectStamp(normalizedProject);
        CacheEntry cached = cache.get(cacheKey);
        if ((overlayTexts == null || overlayTexts.isEmpty()) && cached != null && cached.stamp == stamp) {
            return cached.profile;
        }

        AdaptiveHintProfile base = buildProfile(normalizedProject, normalizedChapter, List.of());
        if (overlayTexts == null || overlayTexts.isEmpty()) {
            cache.put(cacheKey, new CacheEntry(stamp, base));
            return base;
        }
        return buildProfile(normalizedProject, normalizedChapter, overlayTexts);
    }

    private AdaptiveHintProfile buildProfile(String projectId, String chapter, Collection<String> overlayTexts) {
        ProjectLexicon lexicon = projectLexiconResolver.resolve(projectId);
        ScoreBuckets buckets = new ScoreBuckets();

        collectCardTerms(projectId, buckets, lexicon);
        collectChapterArtifacts(projectId, chapter, buckets, lexicon);
        collectVolumeSummaries(projectId, chapter, buckets, lexicon);
        collectOverlayTexts(overlayTexts, buckets, lexicon);

        String genre = inferGenre(buckets.genreProbeText());
        GenrePack genrePack = GENRE_PACKS.getOrDefault(genre, GenrePack.empty());

        List<String> genericTerms = mergeDistinct(
                GENERIC_DISCOVERY_TERMS,
                GENERIC_MECHANISM_TERMS,
                GENERIC_DURABLE_TERMS,
                GENERIC_QUERY_TERMS
        ).stream().limit(MAX_LAYER_TERMS).toList();
        List<String> genreTerms = genrePack.allTerms().stream().limit(MAX_LAYER_TERMS).toList();
        List<String> projectTerms = topTerms(buckets.allTerms, MAX_PROJECT_TERMS);

        return new AdaptiveHintProfile(
                genre,
                genericTerms,
                genreTerms,
                projectTerms,
                mergeDistinct(GENERIC_DISCOVERY_TERMS, genrePack.discoveryTerms(), topTerms(buckets.discoveryTerms, MAX_LAYER_TERMS)),
                mergeDistinct(GENERIC_MECHANISM_TERMS, genrePack.mechanismTerms(), topTerms(buckets.mechanismTerms, MAX_LAYER_TERMS)),
                mergeDistinct(GENERIC_DURABLE_TERMS, genrePack.durableTerms(), topTerms(buckets.durableTerms, MAX_LAYER_TERMS)),
                mergeDistinct(GENERIC_STATE_TERMS, genrePack.stateTerms(), topTerms(buckets.stateTerms, MAX_LAYER_TERMS)),
                mergeDistinct(GENERIC_QUERY_TERMS, genrePack.queryTerms(), topTerms(buckets.queryTerms, MAX_LAYER_TERMS)),
                mergeDistinct(GENERIC_CARRYOVER_TERMS, genrePack.carryoverTerms())
        );
    }

    private void collectCardTerms(String projectId, ScoreBuckets buckets, ProjectLexicon lexicon) {
        try {
            for (String name : cardStorage.listCharacterCards(projectId)) {
                cardStorage.getCharacterCard(projectId, name).ifPresent(card ->
                        collectCharacterCard(card, buckets, lexicon));
            }
            for (String name : cardStorage.listWorldCards(projectId)) {
                cardStorage.getWorldCard(projectId, name).ifPresent(card ->
                        collectWorldCard(card, buckets, lexicon));
            }
        } catch (Exception ignored) {
        }
    }

    private void collectCharacterCard(CharacterCard card, ScoreBuckets buckets, ProjectLexicon lexicon) {
        if (card == null) {
            return;
        }
        collectNameLike(card.getName(), 2.6d, buckets);
        collectNames(card.getAliases(), 2.2d, buckets);
        collectWeightedText(card.getDescription(), 1.2d, buckets, buckets.durableTerms, lexicon);
        collectWeightedText(card.getDescription(), 0.9d, buckets, buckets.queryTerms, lexicon);
    }

    private void collectWorldCard(WorldCard card, ScoreBuckets buckets, ProjectLexicon lexicon) {
        if (card == null) {
            return;
        }
        collectNameLike(card.getName(), 2.6d, buckets);
        collectNames(card.getAliases(), 2.2d, buckets);
        collectWeightedText(card.getDescription(), 1.5d, buckets, buckets.discoveryTerms, lexicon);
        collectWeightedText(card.getDescription(), 1.6d, buckets, buckets.durableTerms, lexicon);
        collectWeightedText(card.getDescription(), 1.0d, buckets, buckets.queryTerms, lexicon);
    }

    private void collectChapterArtifacts(String projectId, String chapter, ScoreBuckets buckets, ProjectLexicon lexicon) {
        if (chapter == null || chapter.isBlank()) {
            return;
        }
        draftStorage.getSceneBrief(projectId, chapter).ifPresent(sceneBrief ->
                collectSceneBrief(sceneBrief, buckets, lexicon));
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary ->
                collectSummary(summary, buckets, lexicon, 2.4d));

        for (com.moxu.model.entity.Fact fact : canonStorage.listFacts(projectId)) {
            String factChapter = firstNonBlank(fact.getIntroducedIn(), fact.getSource());
            if (!chapter.equalsIgnoreCase(factChapter)) {
                continue;
            }
            String statement = firstNonBlank(fact.getStatement(), fact.getContent());
            collectWeightedText(statement, 2.7d, buckets, buckets.discoveryTerms, lexicon);
            collectWeightedText(statement, 2.4d, buckets, buckets.durableTerms, lexicon);
            collectWeightedText(statement, 2.1d, buckets, buckets.queryTerms, lexicon);
            collectWeightedText(statement, 1.9d, buckets, buckets.mechanismTerms, lexicon);
        }

        for (Map<String, Object> state : canonStorage.listCharacterStates(projectId)) {
            if (state == null || state.isEmpty()) {
                continue;
            }
            String lastSeen = String.valueOf(state.getOrDefault("last_seen", state.getOrDefault("source", ""))).trim();
            if (!chapter.equalsIgnoreCase(lastSeen)) {
                continue;
            }
            collectWeightedText(String.valueOf(state.getOrDefault("state", "")), 2.6d, buckets, buckets.stateTerms, lexicon);
            collectWeightedText(String.valueOf(state.getOrDefault("location", "")), 1.6d, buckets, buckets.discoveryTerms, lexicon);
            collectList(state.get("goals"), 2.2d, buckets, buckets.queryTerms, lexicon);
            collectList(state.get("inventory"), 2.2d, buckets, buckets.stateTerms, lexicon);
            collectList(state.get("injuries"), 2.0d, buckets, buckets.stateTerms, lexicon);
        }
    }

    private void collectSceneBrief(SceneBrief sceneBrief, ScoreBuckets buckets, ProjectLexicon lexicon) {
        if (sceneBrief == null) {
            return;
        }
        collectNameLike(sceneBrief.getTitle(), 1.4d, buckets);
        collectWeightedText(sceneBrief.getGoal(), 1.6d, buckets, buckets.queryTerms, lexicon);
        collectList(sceneBrief.getFacts(), 1.8d, buckets, buckets.discoveryTerms, lexicon);
        collectList(sceneBrief.getKeyBeats(), 1.6d, buckets, buckets.queryTerms, lexicon);
        collectList(sceneBrief.getWorldConstraints(), 1.5d, buckets, buckets.durableTerms, lexicon);
    }

    private void collectSummary(ChapterSummary summary,
                                ScoreBuckets buckets,
                                ProjectLexicon lexicon,
                                double weight) {
        if (summary == null) {
            return;
        }
        collectNameLike(summary.getTitle(), weight, buckets);
        collectWeightedText(summary.getBriefSummary(), weight, buckets, buckets.discoveryTerms, lexicon);
        collectList(summary.getKeyEvents(), weight + 0.3d, buckets, buckets.discoveryTerms, lexicon);
        collectList(summary.getNewFacts(), weight + 0.6d, buckets, buckets.discoveryTerms, lexicon);
        collectList(summary.getNewFacts(), weight + 0.5d, buckets, buckets.durableTerms, lexicon);
        collectList(summary.getOpenLoops(), weight + 0.7d, buckets, buckets.queryTerms, lexicon);
        collectList(summary.getOpenLoops(), weight + 0.4d, buckets, buckets.mechanismTerms, lexicon);
        collectList(summary.getCharacterStateChanges(), weight + 0.5d, buckets, buckets.stateTerms, lexicon);
    }

    private void collectVolumeSummaries(String projectId, String chapter, ScoreBuckets buckets, ProjectLexicon lexicon) {
        String volumeId = extractVolumeId(chapter);
        if (volumeId.isBlank()) {
            return;
        }
        List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, volumeId);
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        List<ChapterSummary> ordered = new ArrayList<>(summaries);
        ordered.sort(Comparator.comparingInt(item -> chapterSortKey(item == null ? "" : item.getChapter())));
        int currentOrder = chapterSortKey(chapter);
        int used = 0;
        for (int i = ordered.size() - 1; i >= 0 && used < MAX_VOLUME_SUMMARIES; i--) {
            ChapterSummary summary = ordered.get(i);
            if (summary == null || chapterSortKey(summary.getChapter()) > currentOrder) {
                continue;
            }
            double weight = Objects.equals(normalizeChapter(summary.getChapter()), normalizeChapter(chapter)) ? 1.8d : 0.9d;
            collectSummary(summary, buckets, lexicon, weight);
            used++;
        }
    }

    private void collectOverlayTexts(Collection<String> overlayTexts, ScoreBuckets buckets, ProjectLexicon lexicon) {
        if (overlayTexts == null || overlayTexts.isEmpty()) {
            return;
        }
        for (String text : overlayTexts) {
            collectWeightedText(text, 2.2d, buckets, buckets.discoveryTerms, lexicon);
            collectWeightedText(text, 2.0d, buckets, buckets.durableTerms, lexicon);
            collectWeightedText(text, 2.3d, buckets, buckets.queryTerms, lexicon);
            collectWeightedText(text, 1.8d, buckets, buckets.mechanismTerms, lexicon);
            collectWeightedText(text, 1.6d, buckets, buckets.stateTerms, lexicon);
        }
    }

    private void collectList(Object rawValues,
                             double weight,
                             ScoreBuckets buckets,
                             Map<String, Double> target,
                             ProjectLexicon lexicon) {
        if (!(rawValues instanceof Collection<?> values)) {
            return;
        }
        for (Object value : values) {
            collectWeightedText(String.valueOf(value == null ? "" : value), weight, buckets, target, lexicon);
        }
    }

    private void collectNames(Collection<String> names, double weight, ScoreBuckets buckets) {
        if (names == null || names.isEmpty()) {
            return;
        }
        for (String name : names) {
            collectNameLike(name, weight, buckets);
        }
    }

    private void collectNameLike(String raw, double weight, ScoreBuckets buckets) {
        String cleaned = clean(raw);
        if (!isUsefulAdaptiveTerm(cleaned)) {
            return;
        }
        addScore(buckets.allTerms, cleaned, weight + Math.min(cleaned.length() * 0.08d, 0.8d));
        addScore(buckets.discoveryTerms, cleaned, weight * 0.8d);
        addScore(buckets.durableTerms, cleaned, weight);
        addScore(buckets.queryTerms, cleaned, weight * 0.8d);
        buckets.genreProbeText.append(' ').append(cleaned);
    }

    private void collectWeightedText(String raw,
                                     double weight,
                                     ScoreBuckets buckets,
                                     Map<String, Double> target,
                                     ProjectLexicon lexicon) {
        String cleaned = clean(raw);
        if (cleaned.isBlank()) {
            return;
        }
        for (String term : extractTerms(cleaned, lexicon)) {
            double score = weight + Math.min(term.length() * 0.08d, 0.8d);
            addScore(target, term, score);
            addScore(buckets.allTerms, term, Math.max(score * 0.85d, score - 0.25d));
            buckets.genreProbeText.append(' ').append(term);
        }
    }

    private List<String> extractTerms(String text, ProjectLexicon lexicon) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        for (String token : TextSearchUtils.tokenSet(text, lexicon == null ? ProjectLexicon.empty() : lexicon)) {
            if (isUsefulAdaptiveTerm(token)) {
                terms.add(normalize(token));
            }
        }
        for (HanlpLexiconSupport.TokenInfo token : HanlpLexiconSupport.tokenize(text)) {
            String word = normalize(token.word());
            if (!HanlpLexiconSupport.isNounishNature(token.nature()) || !isUsefulAdaptiveTerm(word)) {
                continue;
            }
            terms.add(word);
        }
        return new ArrayList<>(terms);
    }

    private String inferGenre(String probeText) {
        String lower = clean(probeText).toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return "";
        }
        String bestGenre = "";
        int bestScore = 0;
        for (Map.Entry<String, GenrePack> entry : GENRE_PACKS.entrySet()) {
            int score = 0;
            for (String term : entry.getValue().allTerms()) {
                if (!term.isBlank() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestGenre = entry.getKey();
            }
        }
        return bestScore >= 2 ? bestGenre : "";
    }

    private static Map<String, GenrePack> buildGenrePacks() {
        Map<String, GenrePack> packs = new LinkedHashMap<>();
        packs.put("xianxia", new GenrePack(
                List.of("修为", "宗门", "洞府", "阵纹", "禁制", "壁刻", "传承", "气机", "经脉", "丹田", "法器", "灵机", "符箓", "丹药"),
                List.of("阵纹", "禁制", "气机", "经脉", "灵机", "法器", "传承"),
                List.of("修为", "宗门", "传承", "法器", "禁制", "经脉", "丹田", "洞府"),
                List.of("经脉", "丹田", "气机", "伤势", "法器"),
                List.of("传承", "壁刻", "阵纹", "禁制", "气机", "法器"),
                List.of("伤势", "法器", "资源")
        ));
        packs.put("urban", new GenrePack(
                List.of("公司", "合同", "警局", "医院", "监控", "录音", "股权", "手机", "小区", "地铁", "电梯", "楼盘", "证据"),
                List.of("合同", "监控", "录音", "证据", "股权"),
                List.of("公司", "警局", "医院", "合同", "股权", "证据"),
                List.of("伤势", "手机", "证件", "住址"),
                List.of("合同", "监控", "录音", "证据", "股权", "警局"),
                List.of("伤势", "补给", "遗失")
        ));
        packs.put("mystery", new GenrePack(
                List.of("嫌疑人", "死者", "现场", "物证", "法医", "口供", "证词", "作案", "尸体", "监控", "警局"),
                List.of("作案", "物证", "证词", "监控", "现场"),
                List.of("嫌疑人", "死者", "法医", "物证", "证词", "警局"),
                List.of("伤势", "尸体", "证件"),
                List.of("嫌疑人", "死者", "物证", "证词", "监控", "现场"),
                List.of("伤势", "遗失")
        ));
        return packs;
    }

    private List<String> topTerms(Map<String, Double> scores, int limit) {
        if (scores == null || scores.isEmpty() || limit <= 0) {
            return List.of();
        }
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0d)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    @SafeVarargs
    private final List<String> mergeDistinct(Collection<String>... groups) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (Collection<String> group : groups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            for (String item : group) {
                String cleaned = normalize(item);
                if (isUsefulAdaptiveTerm(cleaned) || GENERIC_DISCOVERY_TERMS.contains(cleaned)
                        || GENERIC_MECHANISM_TERMS.contains(cleaned)
                        || GENERIC_DURABLE_TERMS.contains(cleaned)
                        || GENERIC_STATE_TERMS.contains(cleaned)
                        || GENERIC_QUERY_TERMS.contains(cleaned)
                        || GENERIC_CARRYOVER_TERMS.contains(cleaned)) {
                    merged.add(cleaned);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private void addScore(Map<String, Double> scores, String term, double delta) {
        if (scores == null) {
            return;
        }
        String normalized = normalize(term);
        if (!isUsefulAdaptiveTerm(normalized)) {
            return;
        }
        scores.merge(normalized, delta, Double::sum);
    }

    private boolean isUsefulAdaptiveTerm(String term) {
        String normalized = normalize(term);
        if (normalized.isBlank() || normalized.length() < 2 || normalized.length() > 12) {
            return false;
        }
        if (!SEARCHABLE_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        if (DIGIT_ONLY_PATTERN.matcher(normalized).matches() || CHAPTER_ID_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        if (ADAPTIVE_STOPWORDS.contains(normalized)) {
            return false;
        }
        return normalized.chars().filter(ch -> ch >= '0' && ch <= '9').count() < normalized.length();
    }

    private String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalize(String text) {
        return clean(text).toLowerCase(Locale.ROOT);
    }

    private String extractVolumeId(String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return "";
        }
        String normalized = chapter.trim().toUpperCase(Locale.ROOT);
        int idx = normalized.indexOf('C');
        if (idx <= 0) {
            return "";
        }
        return normalized.substring(0, idx);
    }

    private int chapterSortKey(String chapter) {
        String normalized = normalizeChapter(chapter);
        int cIdx = normalized.indexOf('C');
        if (cIdx < 0 || cIdx >= normalized.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(normalized.substring(cIdx + 1));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private String normalizeChapter(String chapter) {
        return chapter == null ? "" : chapter.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private long projectStamp(String projectId) {
        long stamp = 0L;
        stamp = Math.max(stamp, lastModified(cardStorage.getProjectPath(projectId).resolve("cards")));
        stamp = Math.max(stamp, lastModified(draftStorage.getProjectPath(projectId).resolve("summaries")));
        stamp = Math.max(stamp, lastModified(draftStorage.getProjectPath(projectId).resolve("drafts")));
        stamp = Math.max(stamp, lastModified(canonStorage.getProjectPath(projectId).resolve("canon")));
        return stamp;
    }

    private long lastModified(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.exists(path) ? java.nio.file.Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private record CacheEntry(long stamp, AdaptiveHintProfile profile) {
    }

    private record GenrePack(List<String> discoveryTerms,
                             List<String> mechanismTerms,
                             List<String> durableTerms,
                             List<String> stateTerms,
                             List<String> queryTerms,
                             List<String> carryoverTerms) {
        private static GenrePack empty() {
            return new GenrePack(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        private List<String> allTerms() {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            terms.addAll(discoveryTerms);
            terms.addAll(mechanismTerms);
            terms.addAll(durableTerms);
            terms.addAll(stateTerms);
            terms.addAll(queryTerms);
            terms.addAll(carryoverTerms);
            return new ArrayList<>(terms);
        }
    }

    private static final class ScoreBuckets {
        private final Map<String, Double> allTerms = new LinkedHashMap<>();
        private final Map<String, Double> discoveryTerms = new LinkedHashMap<>();
        private final Map<String, Double> mechanismTerms = new LinkedHashMap<>();
        private final Map<String, Double> durableTerms = new LinkedHashMap<>();
        private final Map<String, Double> stateTerms = new LinkedHashMap<>();
        private final Map<String, Double> queryTerms = new LinkedHashMap<>();
        private final StringBuilder genreProbeText = new StringBuilder();

        private String genreProbeText() {
            return genreProbeText.toString();
        }
    }
}
