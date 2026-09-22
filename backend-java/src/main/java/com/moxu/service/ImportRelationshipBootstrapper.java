package com.moxu.service;

import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.CharacterCard;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 为导书场景构建轻量人物关系索引。
 * <p>
 * 目标不是“最终真相”，而是在大部分章节未做深分析时，提供：
 * <ul>
 *     <li>角色别名归一</li>
 *     <li>章节主要人物索引</li>
 *     <li>关系候选与证据</li>
 * </ul>
 */
@Component
public class ImportRelationshipBootstrapper {

    private static final int MAX_EVIDENCE_PER_PAIR = 6;
    private static final int MAX_CHAPTER_CHARACTERS = 12;
    private static final int MAX_TOP_RELATIONS = 8;
    private static final Pattern HANS_PATTERN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern SURNAME_TITLE_PATTERN = Pattern.compile("([\\p{IsHan}]{1,2}(?:局长|局|主任|老师|警官|队长|老板|总裁|总|学长|学姐|小姐|先生|女士|夫人|太太))");
    private static final Set<String> COMPOUND_SURNAMES = Set.of(
            "万俟", "司马", "上官", "欧阳", "夏侯", "诸葛", "闻人", "东方", "赫连", "皇甫",
            "尉迟", "公羊", "澹台", "公冶", "宗政", "濮阳", "淳于", "单于", "太叔", "申屠",
            "公孙", "仲孙", "轩辕", "令狐", "钟离", "宇文", "长孙", "慕容", "鲜于", "闾丘",
            "司徒", "司空", "端木", "南宫", "西门", "东门", "左丘", "第五"
    );
    private static final Set<String> DIALOGUE_HINTS = Set.of("说道", "说", "问", "回答", "回道", "开口", "叫住", "低声", "轻声", "冷声");
    private static final Set<String> KINSHIP_HINTS = Set.of("父亲", "母亲", "爸爸", "妈妈", "女儿", "儿子", "哥哥", "姐姐", "妹妹", "弟弟");
    private static final Set<String> AUTHORITY_HINTS = Set.of("老板", "上司", "下属", "局长", "主任", "老师", "学长", "学姐", "队长", "经理", "总裁", "总", "警官", "师父", "徒弟");
    private static final Set<String> ROMANCE_HINTS = Set.of("喜欢", "心动", "拥抱", "抱住", "亲", "吻", "爱上", "暧昧", "温柔", "脸红", "丈夫", "妻子", "老公", "老婆");
    private static final Set<String> CONFLICT_HINTS = Set.of("冷笑", "对峙", "争吵", "吵", "恨", "威胁", "推开", "警惕", "敌意", "逼迫", "嘲讽", "质问");
    private static final Set<String> COOPERATION_HINTS = Set.of("帮", "救", "保护", "配合", "一起", "陪", "扶住", "递给", "安慰", "照顾");

    public BootstrapResult build(List<ChapterSource> chapters,
                                 List<ChapterSummary> summaries,
                                 List<CharacterCard> characterCards) {
        List<ChapterSource> normalizedChapters = chapters == null ? List.of() : chapters.stream()
                .filter(item -> item != null && item.chapterId() != null && !item.chapterId().isBlank())
                .toList();
        List<CharacterCard> normalizedCards = characterCards == null ? List.of() : characterCards.stream()
                .filter(card -> card != null && card.getName() != null && !card.getName().isBlank())
                .toList();
        Map<String, ChapterSummary> summaryByChapter = (summaries == null ? List.<ChapterSummary>of() : summaries).stream()
                .filter(summary -> summary != null && summary.getChapter() != null && !summary.getChapter().isBlank())
                .collect(Collectors.toMap(ChapterSummary::getChapter, summary -> summary, (left, right) -> left, LinkedHashMap::new));

        if (normalizedChapters.isEmpty() || normalizedCards.isEmpty()) {
            return BootstrapResult.empty(normalizedChapters.size(), normalizedCards.size());
        }

        Map<String, AliasProfile> aliasProfiles = buildAliasProfiles(normalizedChapters, normalizedCards);
        Map<String, ChapterMentionAggregate> chapterMentions = buildChapterMentions(normalizedChapters, summaryByChapter, aliasProfiles);
        hydrateAliasCoverage(aliasProfiles, chapterMentions);
        List<RelationCandidate> relationCandidates = buildRelationCandidates(normalizedChapters, aliasProfiles, chapterMentions);

        Map<String, Object> aliasIndex = buildAliasIndex(aliasProfiles);
        Map<String, Object> chapterMentionIndex = buildChapterMentionIndex(normalizedChapters, chapterMentions);
        Map<String, Object> relationCandidateIndex = buildRelationCandidateIndex(relationCandidates);
        Map<String, Object> stats = buildStats(aliasProfiles, chapterMentions, relationCandidates);
        Map<String, List<String>> generatedAliases = aliasProfiles.values().stream()
                .collect(Collectors.toMap(
                        AliasProfile::canonical,
                        profile -> new ArrayList<>(profile.strongAliases()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        return new BootstrapResult(aliasIndex, chapterMentionIndex, relationCandidateIndex, stats, generatedAliases);
    }

    private Map<String, AliasProfile> buildAliasProfiles(List<ChapterSource> chapters, List<CharacterCard> characterCards) {
        LinkedHashMap<String, AliasProfile> profiles = new LinkedHashMap<>();
        for (CharacterCard card : characterCards) {
            LinkedHashSet<String> strongAliases = new LinkedHashSet<>();
            strongAliases.add(card.getName().trim());
            if (card.getAliases() != null) {
                card.getAliases().stream()
                        .filter(alias -> alias != null && !alias.isBlank())
                        .map(String::trim)
                        .forEach(strongAliases::add);
            }
            profiles.put(card.getName().trim(), new AliasProfile(card.getName().trim(), strongAliases, new LinkedHashSet<>()));
        }

        attachUniqueGivenNameAliases(profiles);
        attachObservedTitleAliases(chapters, profiles);
        dedupeAliasConflicts(profiles);
        return profiles;
    }

    private void attachUniqueGivenNameAliases(Map<String, AliasProfile> profiles) {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (AliasProfile profile : profiles.values()) {
            String candidate = uniqueGivenNameAlias(profile.canonical());
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            owners.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(profile.canonical());
        }
        owners.forEach((alias, canonicalOwners) -> {
            if (canonicalOwners.size() != 1) {
                return;
            }
            AliasProfile profile = profiles.get(canonicalOwners.get(0));
            if (profile != null) {
                profile.strongAliases().add(alias);
            }
        });
    }

    private void attachObservedTitleAliases(List<ChapterSource> chapters, Map<String, AliasProfile> profiles) {
        Map<String, String> uniqueSurnameOwner = buildUniqueSurnameOwner(profiles.keySet());
        if (uniqueSurnameOwner.isEmpty()) {
            return;
        }
        for (ChapterSource chapter : chapters) {
            String content = normalizeText(chapter.content());
            Matcher matcher = SURNAME_TITLE_PATTERN.matcher(content);
            while (matcher.find()) {
                String token = matcher.group(1).trim();
                String surname = extractSurname(token);
                if (surname.isBlank()) {
                    continue;
                }
                String owner = uniqueSurnameOwner.get(surname);
                if (owner == null || owner.isBlank()) {
                    continue;
                }
                AliasProfile profile = profiles.get(owner);
                if (profile == null) {
                    continue;
                }
                profile.weakAliases().add(token);
            }
        }
    }

    private void dedupeAliasConflicts(Map<String, AliasProfile> profiles) {
        Map<String, Set<String>> owners = new HashMap<>();
        for (AliasProfile profile : profiles.values()) {
            for (String alias : profile.strongAliases()) {
                owners.computeIfAbsent(normalizeKey(alias), ignored -> new LinkedHashSet<>()).add(profile.canonical());
            }
            for (String alias : profile.weakAliases()) {
                owners.computeIfAbsent(normalizeKey(alias), ignored -> new LinkedHashSet<>()).add(profile.canonical());
            }
        }
        for (AliasProfile profile : profiles.values()) {
            profile.strongAliases().removeIf(alias -> {
                Set<String> aliasOwners = owners.get(normalizeKey(alias));
                return aliasOwners != null && aliasOwners.size() > 1 && !alias.equals(profile.canonical());
            });
            profile.weakAliases().removeIf(alias -> {
                Set<String> aliasOwners = owners.get(normalizeKey(alias));
                return aliasOwners != null && aliasOwners.size() > 1;
            });
        }
    }

    private Map<String, String> buildUniqueSurnameOwner(Set<String> canonicalNames) {
        Map<String, List<String>> ownerLists = new LinkedHashMap<>();
        for (String canonical : canonicalNames) {
            String surname = extractSurname(canonical);
            if (surname.isBlank()) {
                continue;
            }
            ownerLists.computeIfAbsent(surname, ignored -> new ArrayList<>()).add(canonical);
        }
        Map<String, String> unique = new LinkedHashMap<>();
        ownerLists.forEach((surname, owners) -> {
            if (owners.size() == 1) {
                unique.put(surname, owners.get(0));
            }
        });
        return unique;
    }

    private Map<String, ChapterMentionAggregate> buildChapterMentions(List<ChapterSource> chapters,
                                                                      Map<String, ChapterSummary> summaryByChapter,
                                                                      Map<String, AliasProfile> profiles) {
        LinkedHashMap<String, ChapterMentionAggregate> result = new LinkedHashMap<>();
        for (ChapterSource chapter : chapters) {
            ChapterMentionAggregate aggregate = new ChapterMentionAggregate(chapter.chapterId(), firstNonBlank(chapter.title(), chapter.chapterId()));
            List<TextWindow> windows = buildWindows(chapter, summaryByChapter.get(chapter.chapterId()));
            for (TextWindow window : windows) {
                Map<String, MentionEvidence> mentions = detectMentions(window.text(), profiles);
                for (Map.Entry<String, MentionEvidence> entry : mentions.entrySet()) {
                    aggregate.bump(entry.getKey(), entry.getValue(), window.source());
                }
            }
            result.put(chapter.chapterId(), aggregate);
        }
        return result;
    }

    private void hydrateAliasCoverage(Map<String, AliasProfile> profiles,
                                      Map<String, ChapterMentionAggregate> chapterMentions) {
        for (ChapterMentionAggregate aggregate : chapterMentions.values()) {
            for (String canonical : aggregate.mentions().keySet()) {
                AliasProfile profile = profiles.get(canonical);
                if (profile == null) {
                    continue;
                }
                profile.chapterHits().add(aggregate.chapterId());
            }
        }
    }

    private List<RelationCandidate> buildRelationCandidates(List<ChapterSource> chapters,
                                                            Map<String, AliasProfile> profiles,
                                                            Map<String, ChapterMentionAggregate> chapterMentions) {
        LinkedHashMap<String, RelationCandidate> candidates = new LinkedHashMap<>();
        for (ChapterSource chapter : chapters) {
            ChapterMentionAggregate aggregate = chapterMentions.get(chapter.chapterId());
            if (aggregate == null) {
                continue;
            }
            List<String> chapterCharacters = aggregate.topCharacters().stream()
                    .map(item -> stringValue(item.get("name")))
                    .filter(name -> !name.isBlank())
                    .limit(MAX_CHAPTER_CHARACTERS)
                    .toList();
            forEachPair(chapterCharacters, (left, right) -> relation(candidates, left, right).chapterCooccurrence(chapter.chapterId()));

            List<TextWindow> windows = buildWindows(chapter, null);
            for (int i = 0; i < windows.size(); i++) {
                TextWindow window = windows.get(i);
                Map<String, MentionEvidence> mentions = detectMentions(window.text(), profiles);
                List<String> characters = mentions.entrySet().stream()
                        .filter(entry -> entry.getValue().score() >= 0.85d)
                        .sorted((a, b) -> Double.compare(b.getValue().score(), a.getValue().score()))
                        .map(Map.Entry::getKey)
                        .distinct()
                        .limit(5)
                        .toList();
                if (characters.size() < 2) {
                    continue;
                }
                boolean dialogue = looksDialogueWindow(window.text());
                SignalCounts signalCounts = signalCounts(window.text());
                final int windowIndex = i;
                forEachPair(characters, (left, right) -> {
                    RelationCandidate candidate = relation(candidates, left, right);
                    candidate.windowCooccurrence(chapter.chapterId(), window.source(), windowIndex,
                            summarizeEvidence(window.text()), dialogue, signalCounts);
                });
            }
        }
        return candidates.values().stream()
                .filter(candidate -> candidate.score() >= 3.4d || candidate.windowHits > 0)
                .sorted(Comparator.comparingDouble(RelationCandidate::score).reversed())
                .toList();
    }

    private Map<String, Object> buildAliasIndex(Map<String, AliasProfile> profiles) {
        LinkedHashMap<String, Object> characters = new LinkedHashMap<>();
        for (AliasProfile profile : profiles.values()) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            List<String> chapterHits = new ArrayList<>(profile.chapterHits());
            item.put("aliases", orderedAliases(profile.strongAliases(), profile.canonical()));
            item.put("weak_aliases", orderedAliases(profile.weakAliases(), null));
            item.put("chapter_hits", chapterHits);
            item.put("first_seen", chapterHits.isEmpty() ? "" : chapterHits.get(0));
            item.put("last_seen", chapterHits.isEmpty() ? "" : chapterHits.get(chapterHits.size() - 1));
            characters.put(profile.canonical(), item);
        }
        return Map.of(
                "built_at", OffsetDateTime.now().toString(),
                "characters", characters
        );
    }

    private Map<String, Object> buildChapterMentionIndex(List<ChapterSource> chapters,
                                                         Map<String, ChapterMentionAggregate> chapterMentions) {
        LinkedHashMap<String, Object> chapterMap = new LinkedHashMap<>();
        for (ChapterSource chapter : chapters) {
            ChapterMentionAggregate aggregate = chapterMentions.get(chapter.chapterId());
            chapterMap.put(chapter.chapterId(), Map.of(
                    "title", firstNonBlank(chapter.title(), chapter.chapterId()),
                    "characters", aggregate != null ? aggregate.topCharacters() : List.of()
            ));
        }
        return Map.of(
                "built_at", OffsetDateTime.now().toString(),
                "chapters", chapterMap
        );
    }

    private Map<String, Object> buildRelationCandidateIndex(List<RelationCandidate> candidates) {
        List<Map<String, Object>> pairs = candidates.stream()
                .map(RelationCandidate::toMap)
                .toList();
        return Map.of(
                "built_at", OffsetDateTime.now().toString(),
                "pairs", pairs
        );
    }

    private Map<String, Object> buildStats(Map<String, AliasProfile> profiles,
                                           Map<String, ChapterMentionAggregate> chapterMentions,
                                           List<RelationCandidate> relationCandidates) {
        return Map.of(
                "characters_indexed", profiles.size(),
                "chapters_indexed", chapterMentions.size(),
                "relation_pairs", relationCandidates.size(),
                "top_relations", relationCandidates.stream()
                        .limit(MAX_TOP_RELATIONS)
                        .map(candidate -> Map.of(
                                "left", candidate.left,
                                "right", candidate.right,
                                "score", round(candidate.score()),
                                "dominant_label", candidate.dominantLabel()
                        ))
                        .toList()
        );
    }

    private List<TextWindow> buildWindows(ChapterSource chapter, ChapterSummary summary) {
        List<TextWindow> windows = new ArrayList<>();
        List<String> paragraphs = splitParagraphs(chapter.content());
        int paragraphIndex = 0;
        for (String paragraph : paragraphs) {
            paragraphIndex++;
            String normalized = normalizeText(paragraph);
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.length() <= 220) {
                windows.add(new TextWindow(normalized, "content"));
                continue;
            }
            windows.addAll(sliceIntoSentenceWindows(normalized).stream()
                    .map(text -> new TextWindow(text, "content"))
                    .toList());
        }
        if (summary != null) {
            if (summary.getBriefSummary() != null && !summary.getBriefSummary().isBlank()) {
                windows.add(new TextWindow(normalizeText(summary.getBriefSummary()), "summary"));
            }
            if (summary.getKeyEvents() != null) {
                summary.getKeyEvents().stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(this::normalizeText)
                        .map(text -> new TextWindow(text, "summary"))
                        .forEach(windows::add);
            }
        }
        return windows;
    }

    private Map<String, MentionEvidence> detectMentions(String text, Map<String, AliasProfile> profiles) {
        LinkedHashMap<String, MentionEvidence> mentions = new LinkedHashMap<>();
        for (AliasProfile profile : profiles.values()) {
            MentionEvidence evidence = matchProfile(text, profile);
            if (evidence.hits() > 0) {
                mentions.put(profile.canonical(), evidence);
            }
        }
        return mentions;
    }

    private MentionEvidence matchProfile(String text, AliasProfile profile) {
        if (text == null || text.isBlank()) {
            return MentionEvidence.empty();
        }
        Set<Integer> occupied = new HashSet<>();
        int hits = 0;
        double score = 0.0d;
        LinkedHashSet<String> matchedTypes = new LinkedHashSet<>();

        List<AliasToken> tokens = new ArrayList<>();
        tokens.add(new AliasToken(profile.canonical(), 1.0d, "name"));
        for (String alias : profile.strongAliases()) {
            if (!alias.equals(profile.canonical())) {
                tokens.add(new AliasToken(alias, 0.86d, "alias"));
            }
        }
        for (String alias : profile.weakAliases()) {
            tokens.add(new AliasToken(alias, 0.62d, "weak_alias"));
        }
        tokens.sort(Comparator.comparingInt((AliasToken token) -> token.text().length()).reversed());

        for (AliasToken token : tokens) {
            for (int start : findAllMatches(text, token.text())) {
                boolean overlapped = false;
                for (int i = start; i < start + token.text().length(); i++) {
                    if (occupied.contains(i)) {
                        overlapped = true;
                        break;
                    }
                }
                if (overlapped) {
                    continue;
                }
                for (int i = start; i < start + token.text().length(); i++) {
                    occupied.add(i);
                }
                hits++;
                score += token.weight();
                matchedTypes.add(token.type());
            }
        }
        return hits == 0 ? MentionEvidence.empty() : new MentionEvidence(hits, score, new ArrayList<>(matchedTypes));
    }

    private RelationCandidate relation(Map<String, RelationCandidate> relationMap, String left, String right) {
        String a = left.compareTo(right) <= 0 ? left : right;
        String b = left.compareTo(right) <= 0 ? right : left;
        return relationMap.computeIfAbsent(a + "|" + b, ignored -> new RelationCandidate(a, b));
    }

    private void forEachPair(List<String> items, PairConsumer consumer) {
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                consumer.accept(items.get(i), items.get(j));
            }
        }
    }

    private boolean looksDialogueWindow(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.contains("“") || text.contains("”") || text.contains("\"")) {
            return true;
        }
        return DIALOGUE_HINTS.stream().anyMatch(text::contains);
    }

    private SignalCounts signalCounts(String text) {
        return new SignalCounts(
                countHint(text, KINSHIP_HINTS),
                countHint(text, AUTHORITY_HINTS),
                countHint(text, ROMANCE_HINTS),
                countHint(text, CONFLICT_HINTS),
                countHint(text, COOPERATION_HINTS)
        );
    }

    private int countHint(String text, Set<String> hints) {
        if (text == null || text.isBlank() || hints == null || hints.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String hint : hints) {
            if (text.contains(hint)) {
                hits++;
            }
        }
        return hits;
    }

    private List<Integer> findAllMatches(String text, String token) {
        if (text == null || text.isBlank() || token == null || token.isBlank()) {
            return List.of();
        }
        List<Integer> hits = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int idx = text.indexOf(token, from);
            if (idx < 0) {
                break;
            }
            hits.add(idx);
            from = idx + token.length();
        }
        return hits;
    }

    private List<String> splitParagraphs(String content) {
        String normalized = normalizeText(content).replace('。', '。');
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = Arrays.stream(content.replace("\r\n", "\n").replace('\r', '\n').split("\\n{2,}"))
                .map(this::normalizeText)
                .filter(text -> !text.isBlank())
                .toList();
        if (!paragraphs.isEmpty()) {
            return paragraphs;
        }
        return Arrays.stream(normalized.split("(?<=[。！？!?])"))
                .map(this::normalizeText)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private List<String> sliceIntoSentenceWindows(String paragraph) {
        List<String> sentences = Arrays.stream(paragraph.split("(?<=[。！？!?])"))
                .map(this::normalizeText)
                .filter(text -> !text.isBlank())
                .toList();
        if (sentences.isEmpty()) {
            return List.of(paragraph);
        }
        List<String> windows = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            StringBuilder current = new StringBuilder();
            for (int j = i; j < sentences.size() && j < i + 3; j++) {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(sentences.get(j));
                String built = current.toString().trim();
                if (built.length() >= 50 && built.length() <= 260) {
                    windows.add(built);
                }
            }
        }
        return windows.isEmpty() ? List.of(paragraph) : windows;
    }

    private String summarizeEvidence(String text) {
        String normalized = normalizeText(text);
        return normalized.length() > 90 ? normalized.substring(0, 88) + "…" : normalized;
    }

    private String uniqueGivenNameAlias(String canonical) {
        String normalized = normalizeHan(canonical);
        if (normalized.length() < 3 || normalized.length() > 4) {
            return null;
        }
        String surname = extractSurname(normalized);
        if (surname.isBlank() || normalized.length() <= surname.length()) {
            return null;
        }
        return normalized.substring(surname.length());
    }

    private String extractSurname(String name) {
        String normalized = normalizeHan(name);
        if (normalized.length() < 2) {
            return "";
        }
        if (normalized.length() >= 3) {
            String maybeCompound = normalized.substring(0, 2);
            if (COMPOUND_SURNAMES.contains(maybeCompound)) {
                return maybeCompound;
            }
        }
        return normalized.substring(0, 1);
    }

    private List<String> orderedAliases(Set<String> aliases, String canonical) {
        return aliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .filter(alias -> canonical == null || !alias.equals(canonical))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .toList();
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r", "")
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeHan(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Matcher matcher = HANS_PATTERN.matcher(text);
        while (matcher.find()) {
            sb.append(matcher.group());
        }
        return sb.toString();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public record ChapterSource(String chapterId, String title, String content) {
    }

    public record BootstrapResult(Map<String, Object> aliasIndex,
                                  Map<String, Object> chapterMentionIndex,
                                  Map<String, Object> relationCandidateIndex,
                                  Map<String, Object> stats,
                                  Map<String, List<String>> generatedStrongAliases) {
        static BootstrapResult empty(int chapters, int characters) {
            return new BootstrapResult(
                    Map.of("built_at", OffsetDateTime.now().toString(), "characters", Map.of()),
                    Map.of("built_at", OffsetDateTime.now().toString(), "chapters", Map.of()),
                    Map.of("built_at", OffsetDateTime.now().toString(), "pairs", List.of()),
                    Map.of(
                            "characters_indexed", characters,
                            "chapters_indexed", chapters,
                            "relation_pairs", 0,
                            "top_relations", List.of()
                    ),
                    Map.of()
            );
        }
    }

    private record AliasToken(String text, double weight, String type) {
    }

    private record MentionEvidence(int hits, double score, List<String> matchTypes) {
        static MentionEvidence empty() {
            return new MentionEvidence(0, 0.0d, List.of());
        }
    }

    private record TextWindow(String text, String source) {
    }

    private record SignalCounts(int kinship, int authority, int romance, int conflict, int cooperation) {
    }

    private interface PairConsumer {
        void accept(String left, String right);
    }

    private static final class AliasProfile {
        private final String canonical;
        private final LinkedHashSet<String> strongAliases;
        private final LinkedHashSet<String> weakAliases;
        private final LinkedHashSet<String> chapterHits = new LinkedHashSet<>();

        private AliasProfile(String canonical, LinkedHashSet<String> strongAliases, LinkedHashSet<String> weakAliases) {
            this.canonical = canonical;
            this.strongAliases = strongAliases;
            this.weakAliases = weakAliases;
        }

        private String canonical() {
            return canonical;
        }

        private LinkedHashSet<String> strongAliases() {
            return strongAliases;
        }

        private LinkedHashSet<String> weakAliases() {
            return weakAliases;
        }

        private LinkedHashSet<String> chapterHits() {
            return chapterHits;
        }
    }

    private static final class ChapterMentionAggregate {
        private final String chapterId;
        private final String title;
        private final Map<String, CharacterMention> mentions = new LinkedHashMap<>();

        private ChapterMentionAggregate(String chapterId, String title) {
            this.chapterId = chapterId;
            this.title = title;
        }

        private void bump(String canonical, MentionEvidence evidence, String source) {
            mentions.computeIfAbsent(canonical, CharacterMention::new)
                    .bump(evidence, source);
        }

        private String chapterId() {
            return chapterId;
        }

        private Map<String, CharacterMention> mentions() {
            return mentions;
        }

        private List<Map<String, Object>> topCharacters() {
            return mentions.values().stream()
                    .sorted(Comparator.comparingDouble(CharacterMention::score).reversed())
                    .limit(MAX_CHAPTER_CHARACTERS)
                    .map(CharacterMention::toMap)
                    .toList();
        }
    }

    private static final class CharacterMention {
        private final String name;
        private int hits;
        private double score;
        private final LinkedHashSet<String> matchTypes = new LinkedHashSet<>();
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();

        private CharacterMention(String name) {
            this.name = name;
        }

        private void bump(MentionEvidence evidence, String source) {
            hits += evidence.hits();
            score += evidence.score();
            if (evidence.matchTypes() != null) {
                matchTypes.addAll(evidence.matchTypes());
            }
            if (source != null && !source.isBlank()) {
                sources.add(source);
            }
        }

        private double score() {
            return score;
        }

        private Map<String, Object> toMap() {
            return Map.of(
                    "name", name,
                    "hits", hits,
                    "score", Math.round(score * 100.0d) / 100.0d,
                    "match_types", new ArrayList<>(matchTypes),
                    "sources", new ArrayList<>(sources)
            );
        }
    }

    private static final class RelationCandidate {
        private final String left;
        private final String right;
        private final LinkedHashSet<String> chapters = new LinkedHashSet<>();
        private final List<Map<String, Object>> evidence = new ArrayList<>();
        private int chapterCooccurrence;
        private int windowHits;
        private int dialogueHits;
        private int kinshipHints;
        private int authorityHints;
        private int romanceHints;
        private int conflictHints;
        private int cooperationHints;

        private RelationCandidate(String left, String right) {
            this.left = left;
            this.right = right;
        }

        private void chapterCooccurrence(String chapter) {
            chapterCooccurrence++;
            if (chapter != null && !chapter.isBlank()) {
                chapters.add(chapter);
            }
        }

        private void windowCooccurrence(String chapter,
                                        String source,
                                        int windowIndex,
                                        String excerpt,
                                        boolean dialogue,
                                        SignalCounts signals) {
            if (chapter != null && !chapter.isBlank()) {
                chapters.add(chapter);
            }
            windowHits++;
            if (dialogue) {
                dialogueHits++;
            }
            kinshipHints += signals.kinship();
            authorityHints += signals.authority();
            romanceHints += signals.romance();
            conflictHints += signals.conflict();
            cooperationHints += signals.cooperation();
            if (evidence.size() < MAX_EVIDENCE_PER_PAIR) {
                evidence.add(Map.of(
                        "chapter", chapter,
                        "source", source,
                        "window_index", windowIndex,
                        "excerpt", excerpt
                ));
            }
        }

        private double score() {
            return chapterCooccurrence * 1.15d
                    + windowHits * 2.6d
                    + dialogueHits * 1.8d
                    + kinshipHints * 3.2d
                    + authorityHints * 1.9d
                    + romanceHints * 2.1d
                    + conflictHints * 2.0d
                    + cooperationHints * 1.7d;
        }

        private String dominantLabel() {
            if (kinshipHints > 0) {
                return "亲属/家庭";
            }
            if (romanceHints > 0 && romanceHints >= conflictHints) {
                return "亲密倾向";
            }
            if (conflictHints > cooperationHints && conflictHints > 0) {
                return "对立/冲突";
            }
            if (authorityHints > 0) {
                return "身份/权力";
            }
            if (cooperationHints > 0) {
                return "合作/照应";
            }
            return "高频互动";
        }

        private List<Map<String, Object>> labels() {
            List<Map<String, Object>> labels = new ArrayList<>();
            if (kinshipHints > 0) {
                labels.add(label("kinship", "亲属/家庭", 0.65d + kinshipHints * 0.08d));
            }
            if (authorityHints > 0) {
                labels.add(label("authority", "身份/权力", 0.55d + authorityHints * 0.07d));
            }
            if (romanceHints > 0) {
                labels.add(label("romance", "亲密倾向", 0.55d + romanceHints * 0.08d));
            }
            if (conflictHints > 0) {
                labels.add(label("conflict", "对立/冲突", 0.55d + conflictHints * 0.07d));
            }
            if (cooperationHints > 0) {
                labels.add(label("cooperation", "合作/照应", 0.52d + cooperationHints * 0.07d));
            }
            if (labels.isEmpty()) {
                labels.add(label("interaction", "高频互动", 0.50d + Math.min(windowHits, 4) * 0.06d));
            }
            return labels;
        }

        private Map<String, Object> label(String type, String text, double score) {
            return Map.of(
                    "type", type,
                    "label", text,
                    "score", Math.min(0.98d, Math.round(score * 100.0d) / 100.0d)
            );
        }

        private Map<String, Object> toMap() {
            return Map.of(
                    "left", left,
                    "right", right,
                    "score", Math.round(score() * 100.0d) / 100.0d,
                    "chapters", new ArrayList<>(chapters),
                    "signals", Map.of(
                            "chapter_cooccurrence", chapterCooccurrence,
                            "window_cooccurrence", windowHits,
                            "dialogue_exchange", dialogueHits,
                            "kinship_hints", kinshipHints,
                            "authority_hints", authorityHints,
                            "romance_hints", romanceHints,
                            "conflict_hints", conflictHints,
                            "cooperation_hints", cooperationHints
                    ),
                    "dominant_label", dominantLabel(),
                    "labels", labels(),
                    "evidence", evidence
            );
        }
    }
}
