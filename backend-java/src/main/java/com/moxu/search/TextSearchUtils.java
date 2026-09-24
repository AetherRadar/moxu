package com.moxu.search;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级中文/英文检索工具。
 *
 * <p>核心目标：
 * <ul>
 *     <li>中文优先使用 Jieba 词级分词，再补充词内 2-gram / 3-gram 兜底召回；</li>
 *     <li>统一 DraftStorage / Context / Evidence / Bindings 的词项抽取；</li>
 *     <li>提供更适合小说检索的词级 token + BM25 风格评分。</li>
 * </ul>
 */
public final class TextSearchUtils {

    private static final Pattern CJK_BLOCK_PATTERN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Set<String> COMBINATION_STOPWORDS = Set.of(
            "的", "了", "着", "地", "得", "和", "与", "及", "或", "而", "但",
            "于", "把", "被", "从", "向", "对", "给", "将", "让",
            "里", "中", "上", "下", "前", "后", "来", "去", "内", "外"
    );
    private static final Set<String> ATTACHABLE_SUFFIX_CHARS = Set.of(
            "镇", "城", "村", "乡", "山", "河", "湖", "海", "宗", "门", "派",
            "王", "帝", "后", "宫", "殿", "楼", "阁", "界", "域", "国", "岛",
            "堡", "府", "州", "县", "市"
    );
    private static final Set<String> DERIVED_TOKEN_EDGE_STOPWORDS = Set.of(
            "的", "了", "着", "地", "得", "和", "与", "及", "或", "而", "但",
            "于", "把", "被", "从", "向", "对", "给", "将", "让",
            "里", "中", "上", "下", "前", "后", "来", "去", "内", "外", "不"
    );
    private static final double QUERY_WEIGHT_ASCII = 1.0;
    private static final double QUERY_WEIGHT_SINGLE_CJK = 0.6;
    private static final double QUERY_WEIGHT_SEGMENT = 1.0;
    private static final double QUERY_WEIGHT_NESTED_SEGMENT = 0.9;
    private static final double QUERY_WEIGHT_ADJACENT_COMBO = 1.1;
    private static final double QUERY_WEIGHT_LEXICON = 1.35;
    private static final double QUERY_WEIGHT_PREFIX_DERIVED = 0.82;
    private static final double QUERY_WEIGHT_FALLBACK_NGRAM = 0.65;
    private static final double QUERY_WEIGHT_SHORT_BLOCK = 1.0;
    private static final JiebaSegmenter JIEBA_SEGMENTER = createJiebaSegmenter();

    private TextSearchUtils() {
    }

    /** Request-local statistics: no cross-project or stale-text cache. */
    public static final class Scorer {
        private final ProjectLexicon lexicon;
        private final String query;
        private final Map<String, Double> weights;
        private final double totalWeight;
        private final Map<String, DocumentTerms> documents = new LinkedHashMap<>();

        private Scorer(String query, ProjectLexicon lexicon) {
            this.lexicon = lexicon == null ? ProjectLexicon.empty() : lexicon;
            this.query = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
            this.weights = queryTokenWeights(query, this.lexicon);
            this.totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        private DocumentTerms document(String text) {
            String source = text == null ? "" : text;
            return documents.computeIfAbsent(source, value -> {
                Map<String, Integer> counts = tokenizeCounts(value, lexicon);
                return new DocumentTerms(value.toLowerCase(Locale.ROOT), counts,
                        counts.values().stream().mapToInt(Integer::intValue).sum());
            });
        }

        public Map<String, Double> buildIdfTable(Collection<String> texts) {
            if (texts == null) return Map.of();
            Map<String, Integer> df = new LinkedHashMap<>();
            int total = 0;
            for (String text : texts) {
                if (text == null || text.isBlank()) continue;
                total++;
                document(text).counts.keySet().forEach(term -> df.merge(term, 1, Integer::sum));
            }
            Map<String, Double> idf = new LinkedHashMap<>();
            for (var entry : df.entrySet())
                idf.put(entry.getKey(), Math.max(0.0, Math.log((total - entry.getValue() + 0.5)
                        / (entry.getValue() + 0.5) + 1.0)));
            return idf;
        }

        public double score(String text, Map<String, Double> idf) {
            DocumentTerms doc = document(text);
            double overlap = overlap(doc);
            if (overlap <= 0) return 0;
            double bm25 = 0;
            for (var entry : weights.entrySet()) {
                int tf = doc.counts.getOrDefault(entry.getKey(), 0);
                if (tf == 0) continue;
                double termIdf = idf == null ? 1.0 : idf.getOrDefault(entry.getKey(), 1.0);
                bm25 += entry.getValue() * termIdf * (tf * 2.2)
                        / (tf + 1.2 * (0.25 + 0.75 * (doc.length / 120.0)));
            }
            return overlap * 0.35 + bm25 * 0.65;
        }

        private double overlap(DocumentTerms doc) {
            if (totalWeight <= 0 || doc.counts.isEmpty()) return 0;
            double matched = 0;
            for (var entry : weights.entrySet())
                if (doc.counts.containsKey(entry.getKey())) matched += entry.getValue();
            if (matched <= 0) return 0;
            double score = matched / totalWeight;
            return !query.isBlank() && doc.lower.contains(query) ? Math.min(1.0, score + 0.25) : score;
        }

        public int preparedDocumentCount() { return documents.size(); }
    }

    private record DocumentTerms(String lower, Map<String, Integer> counts, int length) {}

    public static Scorer scorer(String query, ProjectLexicon lexicon) {
        return new Scorer(query, lexicon);
    }

    /**
     * 提取 token 列表（保留重复，适合 docLen / TF 统计）。
     */
    public static List<String> tokenize(String text) {
        return tokenize(text, ProjectLexicon.empty());
    }

    /**
     * 提取 token 列表（保留重复，适合 docLen / TF 统计）。
     * 会在基础分词结果上额外补入项目专名词。
     */
    public static List<String> tokenize(String text, ProjectLexicon projectLexicon) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        ProjectLexicon lexicon = projectLexicon == null ? ProjectLexicon.empty() : projectLexicon;

        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(lower);
        while (asciiMatcher.find()) {
            String token = asciiMatcher.group();
            if (token != null && !token.isBlank()) {
                tokens.add(token);
            }
        }

        Matcher cjkMatcher = CJK_BLOCK_PATTERN.matcher(lower);
        while (cjkMatcher.find()) {
            String block = cjkMatcher.group();
            if (block == null || block.isBlank()) {
                continue;
            }
            addCjkTokens(block, tokens, lexicon);
        }

        return tokens;
    }

    /**
     * 提取去重 token 集合（适合倒排索引 / overlap）。
     */
    public static Set<String> tokenSet(String text) {
        return tokenSet(text, ProjectLexicon.empty());
    }

    /**
     * 提取去重 token 集合（适合倒排索引 / overlap）。
     */
    public static Set<String> tokenSet(String text, ProjectLexicon projectLexicon) {
        return new LinkedHashSet<>(tokenize(text, projectLexicon));
    }

    /**
     * 为查询词生成“词项质量权重”：
     * <ul>
     *     <li>项目词典 / 专名最高</li>
     *     <li>正常分词词次之</li>
     *     <li>INDEX 稳定子词略低</li>
     *     <li>派生前缀与 fallback n-gram 最低</li>
     * </ul>
     *
     * <p>返回结果去重，若同一 token 来自多个来源，则取最高权重。</p>
     */
    public static Map<String, Double> queryTokenWeights(String text, ProjectLexicon projectLexicon) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Double> weights = new LinkedHashMap<>();
        ProjectLexicon lexicon = projectLexicon == null ? ProjectLexicon.empty() : projectLexicon;

        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(lower);
        while (asciiMatcher.find()) {
            putQueryWeight(weights, asciiMatcher.group(), QUERY_WEIGHT_ASCII);
        }

        Matcher cjkMatcher = CJK_BLOCK_PATTERN.matcher(lower);
        while (cjkMatcher.find()) {
            String block = cjkMatcher.group();
            if (block == null || block.isBlank()) {
                continue;
            }
            addWeightedCjkQueryTokens(block, lexicon, weights);
        }

        return weights;
    }

    /**
     * 统计 token 频次。
     */
    public static Map<String, Integer> tokenizeCounts(String text) {
        return tokenizeCounts(text, ProjectLexicon.empty());
    }

    /**
     * 统计 token 频次。
     */
    public static Map<String, Integer> tokenizeCounts(String text, ProjectLexicon projectLexicon) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokenize(text, projectLexicon)) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 估算 doc length（按 token 数）。
     */
    public static int estimateDocLength(String text) {
        return estimateDocLength(text, ProjectLexicon.empty());
    }

    /**
     * 估算 doc length（按 token 数）。
     */
    public static int estimateDocLength(String text, ProjectLexicon projectLexicon) {
        return Math.max(tokenize(text, projectLexicon).size(), 1);
    }

    /**
     * 计算某 term 在 doc 中的出现次数。
     * 英文按词边界统计；中文按可重叠子串统计。
     */
    public static int countTerm(String doc, String term) {
        if (doc == null || doc.isBlank() || term == null || term.isBlank()) {
            return 0;
        }

        String lowerDoc = doc.toLowerCase(Locale.ROOT);
        String lowerTerm = term.toLowerCase(Locale.ROOT);

        if (ASCII_TOKEN_PATTERN.matcher(lowerTerm).matches()) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(lowerTerm) + "\\b", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(lowerDoc);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }

        return countOverlapping(lowerDoc, lowerTerm);
    }

    /**
     * 构建 IDF 表，供通用 BM25 打分使用。
     */
    public static Map<String, Double> buildIdfTable(Collection<String> documents) {
        return buildIdfTable(documents, ProjectLexicon.empty());
    }

    public static Map<String, Double> buildIdfTable(Collection<String> documents, ProjectLexicon projectLexicon) {
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }

        int totalDocs = 0;
        Map<String, Integer> df = new LinkedHashMap<>();
        for (String doc : documents) {
            if (doc == null || doc.isBlank()) {
                continue;
            }
            totalDocs++;
            for (String token : tokenSet(doc, projectLexicon)) {
                df.merge(token, 1, Integer::sum);
            }
        }
        if (totalDocs <= 0) {
            return Map.of();
        }

        Map<String, Double> idf = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : df.entrySet()) {
            double freq = entry.getValue();
            double value = Math.log((totalDocs - freq + 0.5) / (freq + 0.5) + 1.0);
            idf.put(entry.getKey(), Math.max(0.0, value));
        }
        return idf;
    }

    /**
     * 通用重叠分数（query token 命中比例 + 精确短语 bonus）。
     */
    public static double calculateOverlapScore(String query, String content) {
        return calculateOverlapScore(query, content, ProjectLexicon.empty());
    }

    public static double calculateOverlapScore(String query, String content, ProjectLexicon projectLexicon) {
        Map<String, Double> queryWeights = queryTokenWeights(query, projectLexicon);
        Set<String> queryTokens = queryWeights.keySet();
        Set<String> contentTokens = tokenSet(content, projectLexicon);
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0.0;
        }

        double overlap = 0.0;
        double totalWeight = 0.0;
        for (String token : queryTokens) {
            double weight = queryWeights.getOrDefault(token, 1.0);
            totalWeight += weight;
            if (contentTokens.contains(token)) {
                overlap += weight;
            }
        }
        if (overlap <= 0.0 || totalWeight <= 0.0) {
            return 0.0;
        }

        double score = overlap / totalWeight;
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (!normalizedQuery.isBlank() && normalizedContent.contains(normalizedQuery)) {
            score = Math.min(1.0, score + 0.25);
        }
        return score;
    }

    public static double calculateBm25Score(String query, String content) {
        return calculateBm25Score(query, content, null, ProjectLexicon.empty());
    }

    /**
     * 文本对文本 BM25（简化版，允许可选 IDF 表）。
     */
    public static double calculateBm25Score(String query, String content, Map<String, Double> idfTable) {
        return calculateBm25Score(query, content, idfTable, ProjectLexicon.empty());
    }

    /**
     * 文本对文本 BM25（简化版，允许可选 IDF 表）。
     */
    public static double calculateBm25Score(String query, String content,
                                            Map<String, Double> idfTable,
                                            ProjectLexicon projectLexicon) {
        Map<String, Double> queryWeights = queryTokenWeights(query, projectLexicon);
        Map<String, Integer> contentCounts = tokenizeCounts(content, projectLexicon);
        if (queryWeights.isEmpty() || contentCounts.isEmpty()) {
            return 0.0;
        }

        double k1 = 1.2;
        double b = 0.75;
        double avgDl = 120.0;
        double docLen = contentCounts.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0.0;

        for (Map.Entry<String, Double> entry : queryWeights.entrySet()) {
            String term = entry.getKey();
            double queryWeight = entry.getValue();
            int tf = contentCounts.getOrDefault(term, 0);
            if (tf <= 0) {
                continue;
            }
            double idf = idfTable == null ? 1.0 : idfTable.getOrDefault(term, 1.0);
            score += queryWeight * idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDl)));
        }
        return score;
    }

    /**
     * 适合通用证据/卡片召回的混合打分。
     */
    public static double calculateHybridScore(String query, String content) {
        return calculateHybridScore(query, content, null, ProjectLexicon.empty());
    }

    public static double calculateHybridScore(String query, String content, Map<String, Double> idfTable) {
        return calculateHybridScore(query, content, idfTable, ProjectLexicon.empty());
    }

    public static double calculateHybridScore(String query, String content,
                                              Map<String, Double> idfTable,
                                              ProjectLexicon projectLexicon) {
        return scorer(query, projectLexicon).score(content, idfTable);
    }

    /**
     * 与 Python 版 text_chunk_service 更接近的 BM25：
     * - queryTerms 为去重后的查询 token
     * - df 为语料中每个 term 的文档频率
     * - totalDocs / avgDocLen / docLen 为语料统计
     */
    public static double calculateBm25Score(String text,
                                            Collection<String> queryTerms,
                                            Map<String, Integer> df,
                                            int totalDocs,
                                            double avgDocLen,
                                            int docLen) {
        return calculateBm25Score(text, queryTerms, df, totalDocs, avgDocLen, docLen, null);
    }

    public static double calculateBm25Score(String text,
                                            Collection<String> queryTerms,
                                            Map<String, Integer> df,
                                            int totalDocs,
                                            double avgDocLen,
                                            int docLen,
                                            Map<String, Double> queryWeights) {
        if (text == null || text.isBlank() || queryTerms == null || queryTerms.isEmpty()) {
            return 0.0;
        }

        double score = 0.0;
        double k1 = 1.2;
        double b = 0.75;
        int safeTotalDocs = Math.max(totalDocs, 1);
        double safeAvgDocLen = Math.max(avgDocLen, 1.0);
        int safeDocLen = Math.max(docLen, 1);

        for (String term : queryTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            int freq = countTerm(text, term);
            if (freq <= 0) {
                continue;
            }
            int termDf = df == null ? 0 : df.getOrDefault(term, 0);
            double idf = Math.max(0.0, Math.log((safeTotalDocs - termDf + 0.5) / (termDf + 0.5) + 1.0));
            double denom = freq + k1 * (1.0 - b + b * (safeDocLen / safeAvgDocLen));
            double weight = queryWeights == null ? 1.0 : queryWeights.getOrDefault(term, 1.0);
            score += weight * idf * (freq * (k1 + 1.0) / Math.max(denom, 1e-6));
        }
        return score;
    }

    private static void addCjkTokens(String block, List<String> tokens, ProjectLexicon projectLexicon) {
        if (block.length() == 1) {
            tokens.add(block);
            return;
        }

        // 先收集“有独立证据”的可信 token：
        // - 粗粒度分词结果
        // - 分词结果的 INDEX 模式子词
        // - 相邻合法组合
        // - 项目词典专名
        LinkedHashSet<String> trustedTokens = new LinkedHashSet<>();
        List<String> segmentedWords = segmentCjkWords(block);
        for (String token : segmentedWords) {
            if (token.length() > 1) {
                addSegmentToken(token, trustedTokens);
            }
        }

        addAdjacentCombinations(segmentedWords, trustedTokens);
        addProjectLexiconTerms(block, trustedTokens, projectLexicon);

        LinkedHashSet<String> blockTokens = new LinkedHashSet<>(trustedTokens);
        if (trustedTokens.stream().noneMatch(token -> token.length() > 1)) {
            // 兜底：只有在完全拿不到可信词时才退回到 n-gram。
            addSlidingNgrams(block, blockTokens);
        } else {
            // 有可信词时，只补极少量“可解释”的派生词，避免把长词切成大量噪声尾片。
            addSelectiveDerivedTokens(trustedTokens, blockTokens);
        }
        if (block.length() <= 6) {
            blockTokens.add(block);
        }
        tokens.addAll(blockTokens);
    }

    private static void addWeightedCjkQueryTokens(String block,
                                                  ProjectLexicon projectLexicon,
                                                  LinkedHashMap<String, Double> out) {
        if (block.length() == 1) {
            putQueryWeight(out, block, QUERY_WEIGHT_SINGLE_CJK);
            return;
        }

        LinkedHashMap<String, Double> trustedWeights = new LinkedHashMap<>();
        List<String> segmentedWords = segmentCjkWords(block);
        for (String token : segmentedWords) {
            if (token.length() > 1) {
                addSegmentTokenWeights(token, trustedWeights);
            }
        }

        addAdjacentCombinationWeights(segmentedWords, trustedWeights);
        addProjectLexiconTermWeights(block, projectLexicon, trustedWeights);

        LinkedHashMap<String, Double> blockWeights = new LinkedHashMap<>(trustedWeights);
        if (trustedWeights.keySet().stream().noneMatch(token -> token.length() > 1)) {
            addSlidingNgramWeights(block, blockWeights);
        } else {
            addSelectiveDerivedTokenWeights(trustedWeights, blockWeights);
        }
        if (block.length() <= 6) {
            putQueryWeight(blockWeights, block, QUERY_WEIGHT_SHORT_BLOCK);
        }
        blockWeights.forEach((token, weight) -> putQueryWeight(out, token, weight));
    }

    private static JiebaSegmenter createJiebaSegmenter() {
        try {
            return new JiebaSegmenter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeCjkToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return CJK_BLOCK_PATTERN.matcher(normalized).matches() ? normalized : "";
    }

    private static List<String> segmentCjkWords(String text) {
        if (text == null || text.isBlank() || JIEBA_SEGMENTER == null) {
            return List.of();
        }
        List<String> segmentedWords = new ArrayList<>();
        try {
            for (String segmented : JIEBA_SEGMENTER.sentenceProcess(text)) {
                String token = normalizeCjkToken(segmented);
                if (!token.isBlank()) {
                    segmentedWords.add(token);
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return segmentedWords;
    }

    private static List<String> segmentCjkIndexWords(String text) {
        if (text == null || text.isBlank() || JIEBA_SEGMENTER == null) {
            return List.of();
        }
        LinkedHashSet<String> segmentedWords = new LinkedHashSet<>();
        try {
            for (com.huaban.analysis.jieba.SegToken segToken : JIEBA_SEGMENTER.process(text, JiebaSegmenter.SegMode.INDEX)) {
                String token = normalizeCjkToken(segToken == null ? null : segToken.word);
                if (!token.isBlank()) {
                    segmentedWords.add(token);
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return new ArrayList<>(segmentedWords);
    }

    private static void addSegmentToken(String token, LinkedHashSet<String> out) {
        out.add(token);
        addNestedSegmentTokens(token, out);
    }

    private static void addSegmentTokenWeights(String token, LinkedHashMap<String, Double> out) {
        putQueryWeight(out, token, QUERY_WEIGHT_SEGMENT);
        addNestedSegmentTokenWeights(token, out);
    }

    private static void addNestedSegmentTokens(String token, LinkedHashSet<String> out) {
        if (token == null || token.length() < 3 || token.length() > 8) {
            return;
        }
        // 对可信词再跑一遍 INDEX 模式，保留能稳定落成子词的部分。
        // 例如：
        // - 从来不 -> 从来
        // - 烤面包 -> 面包
        List<String> nestedTokens = segmentCjkIndexWords(token);
        if (nestedTokens.isEmpty()) {
            return;
        }
        for (String nested : nestedTokens) {
            if (nested.length() <= 1 || nested.length() >= token.length()) {
                continue;
            }
            if (!token.contains(nested)) {
                continue;
            }
            out.add(nested);
        }
    }

    private static void addNestedSegmentTokenWeights(String token, LinkedHashMap<String, Double> out) {
        if (token == null || token.length() < 3 || token.length() > 8) {
            return;
        }
        List<String> nestedTokens = segmentCjkIndexWords(token);
        if (nestedTokens.isEmpty()) {
            return;
        }
        for (String nested : nestedTokens) {
            if (nested.length() <= 1 || nested.length() >= token.length()) {
                continue;
            }
            if (!token.contains(nested)) {
                continue;
            }
            putQueryWeight(out, nested, QUERY_WEIGHT_NESTED_SEGMENT);
        }
    }

    private static void addAdjacentCombinations(List<String> segmentedWords, LinkedHashSet<String> out) {
        for (int i = 0; i < segmentedWords.size() - 1; i++) {
            String left = segmentedWords.get(i);
            String right = segmentedWords.get(i + 1);
            if (!canCombineAdjacentTokens(left, right)) {
                continue;
            }
            String combined = left + right;
            if (combined.length() < 2 || combined.length() > 6) {
                continue;
            }
            if (left.length() > 1 && right.length() == 1 && ATTACHABLE_SUFFIX_CHARS.contains(right)) {
                addSegmentToken(combined, out);
            } else if (left.endsWith("不") && "是".equals(right)) {
                out.add("不是");
            } else if (left.length() == 1 && right.length() == 1) {
                out.add(combined);
            }
        }
    }

    private static void addAdjacentCombinationWeights(List<String> segmentedWords, LinkedHashMap<String, Double> out) {
        for (int i = 0; i < segmentedWords.size() - 1; i++) {
            String left = segmentedWords.get(i);
            String right = segmentedWords.get(i + 1);
            if (!canCombineAdjacentTokens(left, right)) {
                continue;
            }
            String combined = left + right;
            if (combined.length() < 2 || combined.length() > 6) {
                continue;
            }
            if (left.length() > 1 && right.length() == 1 && ATTACHABLE_SUFFIX_CHARS.contains(right)) {
                putQueryWeight(out, combined, QUERY_WEIGHT_ADJACENT_COMBO);
                addNestedSegmentTokenWeights(combined, out);
            } else if (left.endsWith("不") && "是".equals(right)) {
                putQueryWeight(out, "不是", QUERY_WEIGHT_ADJACENT_COMBO);
            } else if (left.length() == 1 && right.length() == 1) {
                putQueryWeight(out, combined, QUERY_WEIGHT_ADJACENT_COMBO);
            }
        }
    }

    private static boolean canCombineAdjacentTokens(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        if (COMBINATION_STOPWORDS.contains(left) || COMBINATION_STOPWORDS.contains(right)) {
            return false;
        }
        return left.length() > 1 || right.length() == 1;
    }

    private static void addProjectLexiconTerms(String block, LinkedHashSet<String> out, ProjectLexicon projectLexicon) {
        if (projectLexicon == null || projectLexicon.isEmpty() || block == null || block.isBlank()) {
            return;
        }
        for (String term : projectLexicon.orderedTerms()) {
            if (term.length() < 2 || term.length() > block.length()) {
                continue;
            }
            if (!CJK_BLOCK_PATTERN.matcher(term).matches()) {
                continue;
            }
            if (block.contains(term)) {
                out.add(term);
            }
        }
    }

    private static void addProjectLexiconTermWeights(String block,
                                                     ProjectLexicon projectLexicon,
                                                     LinkedHashMap<String, Double> out) {
        if (projectLexicon == null || projectLexicon.isEmpty() || block == null || block.isBlank()) {
            return;
        }
        for (String term : projectLexicon.orderedTerms()) {
            if (term.length() < 2 || term.length() > block.length()) {
                continue;
            }
            if (!CJK_BLOCK_PATTERN.matcher(term).matches()) {
                continue;
            }
            if (block.contains(term)) {
                putQueryWeight(out, term, QUERY_WEIGHT_LEXICON);
            }
        }
    }

    private static void addSelectiveDerivedTokens(LinkedHashSet<String> trustedTokens, LinkedHashSet<String> out) {
        for (String token : trustedTokens) {
            if (token == null || token.length() != 3) {
                continue;
            }
            // 对 3 字可信词只补前缀 bigram，兼顾：
            // - 奥莉诺 -> 奥莉
            // - 碎石镇 -> 碎石
            // 同时避免：
            // - 奥莉诺 -> 莉诺
            // - 碎石镇 -> 石镇
            String prefix = token.substring(0, 2);
            if (shouldKeepDerivedBigram(prefix, token, 0)) {
                out.add(prefix);
            }
        }
    }

    private static void addSelectiveDerivedTokenWeights(Map<String, Double> trustedTokens,
                                                        LinkedHashMap<String, Double> out) {
        for (String token : trustedTokens.keySet()) {
            if (token == null || token.length() != 3) {
                continue;
            }
            String prefix = token.substring(0, 2);
            if (shouldKeepDerivedBigram(prefix, token, 0)) {
                putQueryWeight(out, prefix, QUERY_WEIGHT_PREFIX_DERIVED);
            }
        }
    }

    private static boolean shouldKeepDerivedBigram(String candidate, String parent, int startIndex) {
        if (candidate == null || parent == null || candidate.length() != 2 || parent.length() < 3) {
            return false;
        }
        if (hasDerivedStopwordEdge(candidate)) {
            return false;
        }
        if (startIndex > 0 && ATTACHABLE_SUFFIX_CHARS.contains(candidate.substring(1))) {
            return false;
        }
        return startIndex == 0;
    }

    private static boolean hasDerivedStopwordEdge(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return true;
        }
        String first = candidate.substring(0, 1);
        String last = candidate.substring(candidate.length() - 1);
        return DERIVED_TOKEN_EDGE_STOPWORDS.contains(first) || DERIVED_TOKEN_EDGE_STOPWORDS.contains(last);
    }

    private static void addSlidingNgrams(String text, LinkedHashSet<String> out) {
        for (int n : List.of(2, 3)) {
            if (text.length() < n) {
                continue;
            }
            for (int i = 0; i <= text.length() - n; i++) {
                String candidate = text.substring(i, i + n);
                if (hasDerivedStopwordEdge(candidate)) {
                    continue;
                }
                out.add(candidate);
            }
        }
    }

    private static void addSlidingNgramWeights(String text, LinkedHashMap<String, Double> out) {
        for (int n : List.of(2, 3)) {
            if (text.length() < n) {
                continue;
            }
            for (int i = 0; i <= text.length() - n; i++) {
                String candidate = text.substring(i, i + n);
                if (hasDerivedStopwordEdge(candidate)) {
                    continue;
                }
                putQueryWeight(out, candidate, QUERY_WEIGHT_FALLBACK_NGRAM);
            }
        }
    }

    private static void putQueryWeight(Map<String, Double> out, String token, double weight) {
        if (out == null || token == null || token.isBlank() || weight <= 0.0) {
            return;
        }
        out.merge(token, weight, Math::max);
    }

    private static int countOverlapping(String doc, String term) {
        int count = 0;
        int start = 0;
        while (true) {
            int idx = doc.indexOf(term, start);
            if (idx < 0) {
                break;
            }
            count++;
            start = idx + 1;
        }
        return count;
    }
}
