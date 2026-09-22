package com.moxu.context;

import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.Fact;
import com.moxu.model.entity.StyleCard;
import com.moxu.model.entity.WorldCard;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.search.TextSearchUtils;
import com.moxu.storage.CardStorage;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 上下文选择引擎 - 智能选择相关上下文项
 */
@Slf4j
@Component
public class ContextSelectEngine {

    private static final int MAX_CANDIDATES_PER_TYPE = 50;
    private static final Set<String> RULE_HINTS = Set.of(
            "必须", "不得", "不能", "不可", "禁止", "代价", "限制", "规则", "失效", "副作用", "约束"
    );
    private static final List<String> FACT_HIGH_VALUE_HINTS = List.of(
            "规则", "禁忌", "代价", "限制", "必须", "不得", "不能", "无法",
            "命令", "决定", "意识到", "获得", "失去", "成为", "不再",
            "修为", "契约", "机制", "本质", "秘术", "禁术"
    );
    private static final List<String> FACT_DURABLE_HINTS = List.of(
            "规则", "禁忌", "代价", "限制", "必须", "不得", "不能", "无法",
            "命令", "决定", "意识到", "获得", "失去", "成为", "不再",
            "修为", "契约", "机制", "本质", "秘术", "禁术"
    );
    private static final List<String> FACT_LOW_SIGNAL_HINTS = List.of(
            "看见", "听见", "谈论", "路过", "喊", "喝令", "站住", "回去集结",
            "完成任务", "清通", "回堵", "下午完成", "来回确认", "中午休息"
    );
    private static final Set<String> FACT_DEDUP_STOPWORDS = Set.of(
            "今天", "昨日", "昨天", "今早", "清晨", "中午", "下午", "傍晚", "深夜", "夜里",
            "已经", "仍然", "继续", "随后", "然后", "再次", "其中", "一名", "一位", "一个", "两名", "几名",
            "弟子", "有人", "外门", "宗门"
    );
    private static final Pattern FACT_UNNAMED_ACTOR_RE = Pattern.compile(
            "^(?:一名|一位|一个|两名|几名|有人|数名|一伙).*(?:弟子|男子|女子|人|壮实弟子|路过弟子).*$"
    );

    private final CardStorage cardStorage;
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;
    private final ProjectLexiconResolver projectLexiconResolver;

    public ContextSelectEngine(CardStorage cardStorage,
                               CanonStorage canonStorage,
                               DraftStorage draftStorage,
                               ProjectLexiconResolver projectLexiconResolver) {
        this.cardStorage = cardStorage;
        this.canonStorage = canonStorage;
        this.draftStorage = draftStorage;
        this.projectLexiconResolver = projectLexiconResolver;
    }

    /**
     * 确定性选择 - 加载特定智能体必须使用的项
     */
    public List<ContextItem> deterministicSelect(String projectId, String agentName) {
        List<ContextItem> items = new ArrayList<>();

        Map<String, List<String>> alwaysLoadMap = Map.of(
                "archivist", List.of("style_card"),
                "writer", List.of("style_card", "scene_brief"),
                "editor", List.of("style_card")
        );

        List<String> itemTypes = alwaysLoadMap.getOrDefault(agentName, List.of());
        for (String itemType : itemTypes) {
            ContextItem item = loadItem(projectId, itemType);
            if (item != null) {
                item.setPriority(ContextPriority.CRITICAL);
                items.add(item);
            }
        }
        return items;
    }

    private ContextItem loadItem(String projectId, String itemType) {
        try {
            if ("style_card".equals(itemType)) {
                Optional<StyleCard> cardOpt = cardStorage.getStyleCard(projectId);
                if (cardOpt.isPresent()) {
                    String content = formatCard(cardOpt.get());
                    if (looksLikeRewritePrompt(content)) {
                        log.warn("Style card looks like a rewrite prompt; skipping injection for safety.");
                        return null;
                    }
                    return ContextItem.builder()
                            .id("style_card")
                            .type(ContextType.STYLE_CARD)
                            .content(content)
                            .priority(ContextPriority.CRITICAL)
                            .metadata(evidenceMetadata("style", "global", List.of(), Map.of("path", "cards/style.yaml"), Map.of()))
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("Error loading {}: {}", itemType, e.getMessage());
        }
        return null;
    }

    private String formatCard(Object card) {
        if (card instanceof StyleCard styleCard) {
            StringBuilder sb = new StringBuilder();
            if (styleCard.getStyle() != null) {
                sb.append("style: ").append(styleCard.getStyle()).append("\n");
            }
            return sb.toString();
        }
        return card.toString();
    }

    private boolean looksLikeRewritePrompt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        List<String> strongMarkers = List.of(
                "chaoticspokenflow", "一逗到底", "极致拟人化",
                "混沌增益", "strictprohibitions", "executionsteps"
        );
        for (String marker : strongMarkers) {
            if (compact.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return compact.contains("ai检测") || compact.contains("ai检测工具");
    }

    /**
     * 检索式选择 - 基于查询相关性排序
     */
    public List<ContextItem> retrievalSelect(String projectId, String query,
                                             List<String> itemTypes, int topK) {
        return retrievalSelect(projectId, query, itemTypes, topK, null, null);
    }

    /**
     * 检索式选择（可限定 text_chunk / summary 的章节范围）
     */
    public List<ContextItem> retrievalSelect(String projectId, String query,
                                             List<String> itemTypes, int topK,
                                             List<String> textChunkChapters,
                                             List<String> textChunkExcludeChapters) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        Set<String> normalizedTypes = normalizeItemTypes(itemTypes);
        List<ContextItem> candidates = new ArrayList<>();
        ProjectLexicon projectLexicon = resolveProjectLexicon(projectId);
        Map<String, Double> characterIdfTable = Map.of();
        Map<String, Double> worldIdfTable = Map.of();
        Map<String, Double> factIdfTable = Map.of();
        List<CharacterCard> characterCards = List.of();
        List<WorldCard> worldCards = List.of();
        List<Fact> facts = List.of();

        if (normalizedTypes.contains("character")) {
            characterCards = loadCharacterCards(projectId);
            characterIdfTable = TextSearchUtils.buildIdfTable(
                    characterCards.stream()
                            .map(this::formatCharacterEvidence)
                            .filter(Objects::nonNull)
                            .filter(s -> !s.isBlank())
                            .toList(),
                    projectLexicon
            );
        }

        if (normalizedTypes.contains("world_entity") || normalizedTypes.contains("world_rule")) {
            worldCards = loadWorldCards(projectId);
            worldIdfTable = TextSearchUtils.buildIdfTable(
                    worldCards.stream()
                            .flatMap(card -> collectWorldSearchDocs(card).stream())
                            .filter(Objects::nonNull)
                            .filter(s -> !s.isBlank())
                            .toList(),
                    projectLexicon
            );
        }

        if (normalizedTypes.contains("fact")) {
            facts = loadFacts(projectId);
            factIdfTable = TextSearchUtils.buildIdfTable(
                    facts.stream()
                            .map(Fact::getStatement)
                            .filter(Objects::nonNull)
                            .filter(s -> !s.isBlank())
                            .toList(),
                    projectLexicon
            );
        }

        if (normalizedTypes.contains("style")) {
            loadStyleCandidates(projectId, query, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("character")) {
            loadCharacterCandidates(query, characterCards, characterIdfTable, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("world_entity")) {
            loadWorldEntityCandidates(query, worldCards, worldIdfTable, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("world_rule")) {
            loadWorldRuleCandidates(query, worldCards, worldIdfTable, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("fact")) {
            loadFactCandidates(query, facts, factIdfTable, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("summary")) {
            loadSummaryCandidates(projectId, query, textChunkChapters, textChunkExcludeChapters, projectLexicon, candidates);
        }
        if (normalizedTypes.contains("text_chunk")) {
            loadTextChunkCandidates(projectId, query, topK, textChunkChapters, textChunkExcludeChapters, projectLexicon, candidates);
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(ContextItem::getRelevanceScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private Set<String> normalizeItemTypes(List<String> itemTypes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : itemTypes == null ? List.<String>of() : itemTypes) {
            String type = raw == null ? "" : raw.trim();
            if (type.isBlank()) {
                continue;
            }
            switch (type) {
                case "world" -> {
                    normalized.add("world_entity");
                    normalized.add("world_rule");
                }
                case "chapter_summary" -> normalized.add("summary");
                case "style_card" -> normalized.add("style");
                default -> normalized.add(type);
            }
        }
        return normalized;
    }

    private List<CharacterCard> loadCharacterCards(String projectId) {
        try {
            List<CharacterCard> cards = new ArrayList<>();
            List<String> names = cardStorage.listCharacterCards(projectId);
            for (String name : names.subList(0, Math.min(names.size(), MAX_CANDIDATES_PER_TYPE))) {
                cardStorage.getCharacterCard(projectId, name).ifPresent(cards::add);
            }
            return cards;
        } catch (Exception e) {
            log.warn("Failed to preload character cards: {}", e.getMessage());
            return List.of();
        }
    }

    private List<WorldCard> loadWorldCards(String projectId) {
        try {
            List<WorldCard> cards = new ArrayList<>();
            List<String> names = cardStorage.listWorldCards(projectId);
            for (String name : names.subList(0, Math.min(names.size(), MAX_CANDIDATES_PER_TYPE))) {
                cardStorage.getWorldCard(projectId, name).ifPresent(cards::add);
            }
            return cards;
        } catch (Exception e) {
            log.warn("Failed to preload world cards: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Fact> loadFacts(String projectId) {
        try {
            List<Fact> facts = canonStorage.listFacts(projectId);
            return facts.subList(0, Math.min(facts.size(), MAX_CANDIDATES_PER_TYPE));
        } catch (Exception e) {
            log.warn("Failed to preload facts: {}", e.getMessage());
            return List.of();
        }
    }

    private void loadStyleCandidates(String projectId,
                                     String query,
                                     ProjectLexicon projectLexicon,
                                     List<ContextItem> candidates) {
        try {
            Optional<StyleCard> styleOpt = cardStorage.getStyleCard(projectId);
            if (styleOpt.isEmpty() || styleOpt.get().getStyle() == null || styleOpt.get().getStyle().isBlank()) {
                return;
            }
            String text = styleOpt.get().getStyle().trim();
            if (looksLikeRewritePrompt(text)) {
                return;
            }
            double score = scoreText(query, text, projectLexicon);
            if (score <= 0) {
                return;
            }
            candidates.add(ContextItem.builder()
                    .id("style_card")
                    .type(ContextType.STYLE_CARD)
                    .content(text)
                    .priority(ContextPriority.MEDIUM)
                    .relevanceScore(score)
                    .metadata(evidenceMetadata("style", "global", List.of(), Map.of("path", "cards/style.yaml"), Map.of()))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to load style card: {}", e.getMessage());
        }
    }

    private void loadCharacterCandidates(String query,
                                         List<CharacterCard> cards,
                                         Map<String, Double> idfTable,
                                         ProjectLexicon projectLexicon,
                                         List<ContextItem> candidates) {
        for (CharacterCard card : cards) {
            if (card == null || card.getName() == null || card.getName().isBlank()) {
                continue;
            }
            String name = card.getName().trim();
            List<String> entities = uniqueStrings(buildCharacterEntities(card));
            String text = formatCharacterEvidence(card);
            double score = scoreText(query, text, idfTable, projectLexicon);
            if (score <= 0) {
                continue;
            }
            candidates.add(ContextItem.builder()
                    .id("character:" + name)
                    .type(ContextType.CHARACTER_CARD)
                    .content(text)
                    .priority(ContextPriority.MEDIUM)
                    .relevanceScore(score)
                    .metadata(evidenceMetadata("character", "global", entities, Map.of("card", name, "field", "description"), Map.of()))
                    .build());
        }
    }

    private void loadWorldEntityCandidates(String query,
                                           List<WorldCard> cards,
                                           Map<String, Double> idfTable,
                                           ProjectLexicon projectLexicon,
                                           List<ContextItem> candidates) {
        for (WorldCard card : cards) {
            if (card == null || card.getName() == null || card.getName().isBlank()) {
                continue;
            }
            String name = card.getName().trim();
            List<String> entities = buildWorldEntities(card);
            String text = formatWorldEntityEvidence(card);
            double score = scoreText(query, text, idfTable, projectLexicon);
            if (score > 0) {
                candidates.add(ContextItem.builder()
                        .id("world_entity:" + name)
                        .type(ContextType.WORLD_CARD)
                        .content(text)
                        .priority(ContextPriority.MEDIUM)
                        .relevanceScore(score)
                        .metadata(evidenceMetadata("world_entity", "global", entities, Map.of("card", name, "field", "name"), Map.of()))
                        .build());
            }

            String category = card.getCategory() == null ? "" : card.getCategory().trim();
            if (!category.isBlank()) {
                String categoryText = "类别: " + category;
                double categoryScore = scoreText(query, categoryText + " " + name, idfTable, projectLexicon);
                if (categoryScore > 0) {
                    candidates.add(ContextItem.builder()
                            .id("world_entity:" + name + ":category")
                            .type(ContextType.WORLD_CARD)
                            .content(categoryText)
                            .priority(ContextPriority.MEDIUM)
                            .relevanceScore(categoryScore)
                            .metadata(evidenceMetadata("world_entity", "global", uniqueStrings(List.of(name, category)), Map.of("card", name, "field", "category"), Map.of()))
                            .build());
                }
            }
        }
    }

    private void loadWorldRuleCandidates(String query,
                                         List<WorldCard> cards,
                                         Map<String, Double> idfTable,
                                         ProjectLexicon projectLexicon,
                                         List<ContextItem> candidates) {
        for (WorldCard card : cards) {
            if (card == null || card.getName() == null || card.getName().isBlank()) {
                continue;
            }
            String name = card.getName().trim();
            List<String> entities = buildWorldEntities(card);
            List<String> ruleTexts = extractWorldRules(card);
            for (int i = 0; i < ruleTexts.size(); i++) {
                String ruleText = ruleTexts.get(i);
                double score = scoreText(query, ruleText + " " + name, idfTable, projectLexicon);
                if (score <= 0) {
                    continue;
                }
                candidates.add(ContextItem.builder()
                        .id("world_rule:" + name + ":" + (i + 1))
                        .type(ContextType.WORLD_CARD)
                        .content(ruleText)
                        .priority(ContextPriority.MEDIUM)
                        .relevanceScore(score)
                        .metadata(evidenceMetadata("world_rule", "global", entities, Map.of("card", name, "field", "rules"), Map.of()))
                        .build());
            }
        }
    }

    private void loadFactCandidates(String query,
                                    List<Fact> facts,
                                    Map<String, Double> idfTable,
                                    ProjectLexicon projectLexicon,
                                    List<ContextItem> candidates) {
        List<FactRetrievalCandidate> ranked = new ArrayList<>();
        int idx = 0;
        for (Fact fact : facts) {
            if (fact == null) {
                idx++;
                continue;
            }
            String statement = fact.getStatement();
            if (statement == null || statement.isBlank()) {
                idx++;
                continue;
            }
            double relevance = scoreText(query, statement, idfTable, projectLexicon);
            if (relevance <= 0) {
                idx++;
                continue;
            }
            double quality = factQualityMultiplier(statement, projectLexicon);
            if (quality <= 0.20d) {
                idx++;
                continue;
            }
            double finalScore = relevance * quality;
            if (isLowSignalFactStatement(statement) && finalScore < 0.45d) {
                idx++;
                continue;
            }
            String factId = fact.getId() != null ? fact.getId() : String.format("F%04d", idx + 1);
            ranked.add(new FactRetrievalCandidate(factId, fact, statement, relevance, finalScore));
            idx++;
        }

        ranked.sort(Comparator.comparingDouble(FactRetrievalCandidate::finalScore).reversed());

        List<FactRetrievalCandidate> selected = new ArrayList<>();
        for (FactRetrievalCandidate candidate : ranked) {
            if (isNearDuplicateFactCandidate(candidate, selected, projectLexicon)) {
                continue;
            }
            selected.add(candidate);
        }

        for (FactRetrievalCandidate candidate : selected) {
            Fact fact = candidate.fact();
            candidates.add(ContextItem.builder()
                    .id(candidate.id())
                    .type(ContextType.FACT)
                    .content(candidate.statement())
                    .priority(ContextPriority.MEDIUM)
                    .relevanceScore(candidate.finalScore())
                    .metadata(evidenceMetadata("fact", "global", List.of(), Map.of(
                            "introduced_in", fact.getIntroducedIn() != null ? fact.getIntroducedIn() : ""
                    ), Map.of(
                            "raw_relevance", candidate.rawRelevance()
                    )))
                    .build());
        }
    }

    private void loadSummaryCandidates(String projectId,
                                       String query,
                                       List<String> chapters,
                                       List<String> excludeChapters,
                                       ProjectLexicon projectLexicon,
                                       List<ContextItem> candidates) {
        try {
            Set<String> chapterFilter = chapters == null ? Set.of() : new LinkedHashSet<>(chapters);
            Set<String> excluded = excludeChapters == null ? Set.of() : new LinkedHashSet<>(excludeChapters);
            for (ChapterSummary summary : draftStorage.listChapterSummaries(projectId, null)) {
                if (summary == null || summary.getChapter() == null || summary.getChapter().isBlank()) {
                    continue;
                }
                String chapter = summary.getChapter();
                if (!chapterFilter.isEmpty() && !chapterFilter.contains(chapter)) {
                    continue;
                }
                if (excluded.contains(chapter)) {
                    continue;
                }
                addSummaryCandidate(candidates, query, chapter, "brief_summary", summary.getBriefSummary(), null, projectLexicon);

                List<String> keyEvents = summary.getKeyEvents() == null ? List.of() : summary.getKeyEvents();
                for (int i = 0; i < keyEvents.size(); i++) {
                    addSummaryCandidate(candidates, query, chapter, "key_events", keyEvents.get(i), i, projectLexicon);
                }

                List<String> openLoops = summary.getOpenLoops() == null ? List.of() : summary.getOpenLoops();
                for (int i = 0; i < openLoops.size(); i++) {
                    addSummaryCandidate(candidates, query, chapter, "open_loops", openLoops.get(i), i, projectLexicon);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load chapter summaries: {}", e.getMessage());
        }
    }

    private void addSummaryCandidate(List<ContextItem> candidates,
                                     String query,
                                     String chapter,
                                     String field,
                                     String text,
                                     Integer index,
                                     ProjectLexicon projectLexicon) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isBlank()) {
            return;
        }
        double score = scoreText(query, cleaned, projectLexicon);
        if (score <= 0) {
            return;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("chapter", chapter);
        source.put("field", field);
        if (index != null) {
            source.put("index", index);
        }
        candidates.add(ContextItem.builder()
                .id(index == null ? "summary:" + chapter + ":" + field : "summary:" + chapter + ":" + field + ":" + (index + 1))
                .type(ContextType.CHAPTER_SUMMARY)
                .content(cleaned)
                .priority(ContextPriority.MEDIUM)
                .relevanceScore(score)
                .metadata(evidenceMetadata("summary", "chapter", List.of(), source, Map.of()))
                .build());
    }

    private void loadTextChunkCandidates(String projectId,
                                         String query,
                                         int topK,
                                         List<String> textChunkChapters,
                                         List<String> textChunkExcludeChapters,
                                         ProjectLexicon projectLexicon,
                                         List<ContextItem> candidates) {
        try {
            List<Map<String, Object>> chunks = draftStorage.searchTextChunks(
                    projectId,
                    query,
                    Math.min(topK * 2, MAX_CANDIDATES_PER_TYPE),
                    textChunkChapters,
                    textChunkExcludeChapters,
                    false
            );
            for (Map<String, Object> chunk : chunks) {
                String text = String.valueOf(chunk.getOrDefault("text", ""));
                if (text.isBlank()) {
                    continue;
                }
                String chapter = String.valueOf(chunk.getOrDefault("chapter", ""));
                double score = chunk.get("score") instanceof Number scoreNumber
                        ? scoreNumber.doubleValue()
                        : scoreText(query, text, projectLexicon);
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("chapter", chapter);
                source.put("chunk_index", chunk.getOrDefault("chunk_index", 0));
                candidates.add(ContextItem.builder()
                        .id("text_chunk:" + chapter + ":" + chunk.getOrDefault("chunk_index", 0))
                        .type(ContextType.TEXT_CHUNK)
                        .content(text)
                        .priority(ContextPriority.MEDIUM)
                        .relevanceScore(score)
                        .metadata(evidenceMetadata("text_chunk", "chapter", List.of(), source, Map.of()))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to load text chunks: {}", e.getMessage());
        }
    }

    /**
     * 计算文本相关性分数（简化的 BM25 + 重叠分数）
     */
    private double scoreText(String query, String text) {
        return scoreText(query, text, ProjectLexicon.empty());
    }

    private double scoreText(String query, String text, Map<String, Double> idfTable) {
        return scoreText(query, text, idfTable, ProjectLexicon.empty());
    }

    private double scoreText(String query, String text, ProjectLexicon projectLexicon) {
        return TextSearchUtils.calculateHybridScore(query, text, null, projectLexicon);
    }

    private double scoreText(String query,
                             String text,
                             Map<String, Double> idfTable,
                             ProjectLexicon projectLexicon) {
        return TextSearchUtils.calculateHybridScore(query, text, idfTable, projectLexicon);
    }

    private ProjectLexicon resolveProjectLexicon(String projectId) {
        return projectLexiconResolver == null ? ProjectLexicon.empty() : projectLexiconResolver.resolve(projectId);
    }

    private String formatCharacterEvidence(CharacterCard card) {
        StringBuilder sb = new StringBuilder();
        if (card.getName() != null && !card.getName().isBlank()) {
            sb.append(card.getName().trim());
        }
        if (card.getDescription() != null && !card.getDescription().isBlank()) {
            if (sb.length() > 0) {
                sb.append(": ");
            }
            sb.append(card.getDescription().trim());
        }
        if (card.getAliases() != null && !card.getAliases().isEmpty()) {
            String aliases = card.getAliases().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isBlank()).distinct().collect(Collectors.joining("、"));
            if (!aliases.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("别名: ").append(aliases);
            }
        }
        return sb.toString().trim();
    }

    private List<String> buildCharacterEntities(CharacterCard card) {
        List<String> entities = new ArrayList<>();
        if (card.getName() != null) {
            entities.add(card.getName().trim());
        }
        if (card.getAliases() != null) {
            entities.addAll(card.getAliases().stream().filter(Objects::nonNull).map(String::trim).toList());
        }
        return entities;
    }

    private String formatWorldEntityEvidence(WorldCard card) {
        String name = card.getName() == null ? "" : card.getName().trim();
        String definition = firstSentence(card.getDescription());
        if (name.isBlank()) {
            return definition;
        }
        if (definition.isBlank()) {
            return name;
        }
        if (definition.startsWith(name + ":") || definition.startsWith(name + "：")) {
            return definition;
        }
        return name + ": " + definition;
    }

    private List<String> buildWorldEntities(WorldCard card) {
        List<String> entities = new ArrayList<>();
        if (card.getName() != null) {
            entities.add(card.getName().trim());
        }
        if (card.getAliases() != null) {
            entities.addAll(card.getAliases().stream().filter(Objects::nonNull).map(String::trim).toList());
        }
        if (card.getCategory() != null) {
            entities.add(card.getCategory().trim());
        }
        return uniqueStrings(entities);
    }

    private List<String> collectWorldSearchDocs(WorldCard card) {
        if (card == null) {
            return List.of();
        }
        List<String> docs = new ArrayList<>();
        String entity = formatWorldEntityEvidence(card);
        if (entity != null && !entity.isBlank()) {
            docs.add(entity);
        }
        if (card.getCategory() != null && !card.getCategory().isBlank() && card.getName() != null && !card.getName().isBlank()) {
            docs.add("类别: " + card.getCategory().trim() + " " + card.getName().trim());
        }
        docs.addAll(extractWorldRules(card));
        return docs;
    }

    private List<String> extractWorldRules(WorldCard card) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        if (card.getRules() != null) {
            for (String rule : card.getRules()) {
                String text = rule == null ? "" : rule.trim();
                if (!text.isBlank()) {
                    rules.add(text);
                }
            }
        }
        String description = card.getDescription() == null ? "" : card.getDescription().trim();
        if (!description.isBlank()) {
            for (String sentence : splitTextBlocks(description, 200)) {
                if (isRuleSentence(sentence)) {
                    rules.add(sentence);
                }
            }
        }
        if (Boolean.TRUE.equals(card.getImmutable())) {
            rules.add("不可变");
        }
        return new ArrayList<>(rules);
    }

    private List<String> splitTextBlocks(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        for (String part : text.split("\\n+")) {
            String cleaned = part == null ? "" : part.trim();
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.length() <= maxLen) {
                blocks.add(cleaned);
                continue;
            }
            for (String sentence : cleaned.split("[。；;]") ) {
                String trimmed = sentence.trim();
                if (!trimmed.isBlank()) {
                    blocks.add(trimmed);
                }
            }
        }
        return blocks;
    }

    private boolean isRuleSentence(String text) {
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

    private String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] parts = text.trim().split("[。！？!?]");
        return parts.length == 0 ? text.trim() : parts[0].trim();
    }

    private List<String> uniqueStrings(List<String> items) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String item : items == null ? List.<String>of() : items) {
            String text = item == null ? "" : item.trim();
            if (!text.isBlank()) {
                seen.add(text);
            }
        }
        return new ArrayList<>(seen);
    }

    private Map<String, Object> evidenceMetadata(String evidenceType,
                                                 String scope,
                                                 List<String> entities,
                                                 Map<String, Object> source,
                                                 Map<String, Object> meta) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evidence_type", evidenceType);
        metadata.put("scope", scope);
        metadata.put("entities", entities == null ? List.of() : entities);
        metadata.put("meta", meta == null ? Map.of() : meta);
        if (source != null) {
            metadata.putAll(source);
        }
        return metadata;
    }

    private double factQualityMultiplier(String statement, ProjectLexicon projectLexicon) {
        String cleaned = statement == null ? "" : statement.trim();
        if (cleaned.isBlank()) {
            return 0.0d;
        }

        double quality = 1.0d;
        if (containsAny(cleaned, FACT_HIGH_VALUE_HINTS)) {
            quality += 0.30d;
        }
        if (containsAny(cleaned, FACT_LOW_SIGNAL_HINTS)) {
            quality -= 0.35d;
            if (!containsAny(cleaned, FACT_DURABLE_HINTS)) {
                quality -= 0.35d;
            }
        }
        if (FACT_UNNAMED_ACTOR_RE.matcher(cleaned).matches()) {
            quality -= 0.45d;
        }
        if (cleaned.length() < 10) {
            quality -= 0.15d;
        }
        if (!containsKnownEntityTerm(cleaned, projectLexicon) && !containsAny(cleaned, FACT_HIGH_VALUE_HINTS)) {
            quality -= 0.15d;
        }
        return Math.max(0.0d, quality);
    }

    private boolean containsAny(String text, List<String> hints) {
        if (text == null || text.isBlank() || hints == null || hints.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                continue;
            }
            if (lower.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsKnownEntityTerm(String text, ProjectLexicon projectLexicon) {
        if (text == null || text.isBlank() || projectLexicon == null || projectLexicon.isEmpty()) {
            return false;
        }
        for (String term : projectLexicon.orderedTerms()) {
            if (term == null || term.isBlank() || term.length() < 2) {
                continue;
            }
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLowSignalFactStatement(String statement) {
        String cleaned = statement == null ? "" : statement.trim();
        if (cleaned.isBlank()) {
            return true;
        }
        return containsAny(cleaned, FACT_LOW_SIGNAL_HINTS)
                || FACT_UNNAMED_ACTOR_RE.matcher(cleaned).matches();
    }

    private boolean isNearDuplicateFactCandidate(FactRetrievalCandidate candidate,
                                                 List<FactRetrievalCandidate> selected,
                                                 ProjectLexicon projectLexicon) {
        String normalized = normalizeFactDedupKey(candidate.statement());
        Set<String> tokens = factDedupTokens(candidate.statement(), projectLexicon);
        for (FactRetrievalCandidate existing : selected) {
            String existingNormalized = normalizeFactDedupKey(existing.statement());
            if (normalized.equals(existingNormalized)
                    || normalized.contains(existingNormalized)
                    || existingNormalized.contains(normalized)) {
                return true;
            }
            double overlap = jaccard(tokens, factDedupTokens(existing.statement(), projectLexicon));
            if (overlap >= 0.58d) {
                return true;
            }
        }
        return false;
    }

    private String normalizeFactDedupKey(String statement) {
        if (statement == null) {
            return "";
        }
        return statement.replaceAll("\\s+", "")
                .replaceAll("[，,。；;：:“”\"'！？!?（）()\\-—]+", "")
                .trim();
    }

    private Set<String> factDedupTokens(String statement, ProjectLexicon projectLexicon) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : TextSearchUtils.tokenSet(statement, projectLexicon)) {
            String normalized = token == null ? "" : token.trim();
            if (normalized.isBlank() || normalized.length() < 2 || FACT_DEDUP_STOPWORDS.contains(normalized)) {
                continue;
            }
            tokens.add(normalized);
        }
        return tokens;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String token : left) {
            if (right.contains(token)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        if (union <= 0) {
            return 0.0d;
        }
        return (double) intersection / union;
    }

    private record FactRetrievalCandidate(String id,
                                          Fact fact,
                                          String statement,
                                          double rawRelevance,
                                          double finalScore) {
    }
}
