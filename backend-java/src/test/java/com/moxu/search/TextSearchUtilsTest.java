package com.moxu.search;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextSearchUtilsTest {

    @Test
    void preparedScoringMatchesTheExistingFormulaAcrossLanguagesAndLexicons() {
        List<String> documents = List.of("Alice owns the silver key.", "ALICE alice has 3 keys",
                "奥莉诺在碎石镇的酒馆里喝茶。", "白银审判骑士团不得进入碎石镇。",
                "碎石镇。碎石镇。", "王方失去法器并与宗门外援失联", "", " \n ", "!!!");
        for (ProjectLexicon lexicon : List.of(ProjectLexicon.empty(),
                ProjectLexicon.fromTerms(List.of("白银审判骑士团", "奥莉诺")))) {
            Map<String, Double> idf = TextSearchUtils.buildIdfTable(documents, lexicon);
            for (String query : List.of("Alice silver", "奥莉诺 碎石镇", "白银审判骑士团", "王方 法器", "", "!!!")) {
                var scorer = TextSearchUtils.scorer(query, lexicon);
                assertEquals(idf, scorer.buildIdfTable(documents));
                for (String document : documents) {
                    for (boolean useIdf : List.of(false, true)) {
                        Map<String, Double> weights = useIdf ? idf : null;
                        double overlap = TextSearchUtils.calculateOverlapScore(query, document, lexicon);
                        double expected = overlap <= 0 ? 0 : overlap * 0.35
                                + TextSearchUtils.calculateBm25Score(query, document, weights, lexicon) * 0.65;
                        assertEquals(expected, scorer.score(document, weights), 1e-12);
                        assertEquals(expected, TextSearchUtils.calculateHybridScore(query, document, weights, lexicon), 1e-12);
                    }
                }
            }
        }
    }

    @Test
    void deterministicRandomCorpusKeepsScoresAndPreparesEachDistinctDocumentOnce() {
        Random random = new Random(731);
        String[] words = {"Alice", "silver", "key", "碎石镇", "酒馆", "奥莉诺", "王方", "不得", "代价", "42"};
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < 12; j++) text.append(words[random.nextInt(words.length)]).append(' ');
            documents.add(text.toString());
        }
        String query = "奥莉诺 Alice key";
        var scorer = TextSearchUtils.scorer(query, ProjectLexicon.empty());
        var idf = scorer.buildIdfTable(documents);
        for (int repeat = 0; repeat < 3; repeat++) {
            for (String text : documents) {
                double overlap = TextSearchUtils.calculateOverlapScore(query, text);
                double expected = overlap <= 0 ? 0 : overlap * 0.35
                        + TextSearchUtils.calculateBm25Score(query, text, idf) * 0.65;
                assertEquals(expected, scorer.score(text, idf), 1e-12);
            }
        }
        assertEquals(100, scorer.preparedDocumentCount());
    }

    @Test
    void requestCachesKeepProjectLexiconsIsolatedAndKeepDuplicateIdfDocuments() {
        String text = "白银审判骑士团抵达";
        var empty = TextSearchUtils.scorer("白银审判骑士团", ProjectLexicon.empty());
        var withName = TextSearchUtils.scorer("白银审判骑士团",
                ProjectLexicon.fromTerms(List.of("白银审判骑士团")));
        var docs = List.of(text, text, "无关背景");
        assertEquals(TextSearchUtils.buildIdfTable(docs), empty.buildIdfTable(docs));
        assertTrue(withName.buildIdfTable(docs).containsKey("白银审判骑士团"));
        assertEquals(2, empty.preparedDocumentCount());
        assertEquals(2, withName.preparedDocumentCount());
        assertEquals(0, empty.score(null, null));
        assertEquals(0, TextSearchUtils.scorer(null, null).score(text, null));
    }

    @Test
    void chineseTokenizerUsesWordBoundariesBeforeSubgramFallback() {
        Set<String> tokens = TextSearchUtils.tokenSet("碎石镇的清晨从来不是安静的");

        assertTrue(tokens.contains("碎石"));
        assertTrue(tokens.contains("碎石镇"));
        assertTrue(tokens.contains("清晨"));
        assertTrue(tokens.contains("从来"));
        assertTrue(tokens.contains("安静"));
        assertFalse(tokens.contains("石镇"));

        assertFalse(tokens.contains("镇的"));
        assertFalse(tokens.contains("的清"));
        assertFalse(tokens.contains("晨从"));
    }

    @Test
    void tokenizerKeepsUsefulEntityPrefixButDropsLowValueTailSubgram() {
        Set<String> tokens = TextSearchUtils.tokenSet("奥莉诺女王");

        assertTrue(tokens.contains("奥莉"));
        assertTrue(tokens.contains("奥莉诺"));
        assertTrue(tokens.contains("女王"));
        assertTrue(tokens.contains("奥莉诺女王"));
        assertFalse(tokens.contains("莉诺"));
    }

    @Test
    void hybridScoreCanMatchChineseTermThatUsedToBeCutAcrossBoundary() {
        double score = TextSearchUtils.calculateHybridScore(
                "酒馆",
                "与远处酒馆里传来的醉汉笑骂声混在一起。"
        );

        assertTrue(score > 0.0);
    }

    @Test
    void tokenizerAvoidsCrossWordNoiseForSentenceSearch() {
        Set<String> tokens = TextSearchUtils.tokenSet("与远处酒馆里传来的醉汉笑骂声混在一起");

        assertTrue(tokens.contains("远处"));
        assertTrue(tokens.contains("酒馆"));
        assertTrue(tokens.contains("醉汉"));
        assertTrue(tokens.contains("一起"));

        assertFalse(tokens.contains("处酒"));
        assertFalse(tokens.contains("馆里"));
        assertFalse(tokens.contains("声混"));
    }

    @Test
    void tokenizerCanInjectProjectLexiconTerms() {
        ProjectLexicon lexicon = ProjectLexicon.fromTerms(Set.of("白银审判骑士团"));

        Set<String> tokens = TextSearchUtils.tokenSet("白银审判骑士团已经抵达碎石镇。", lexicon);

        assertTrue(tokens.contains("白银审判骑士团"));
        assertTrue(tokens.contains("碎石镇"));
    }

    @Test
    void tokenizerKeepsNestedStandaloneWordInsideThreeCharToken() {
        Set<String> tokens = TextSearchUtils.tokenSet("烤面包的焦香");

        assertTrue(tokens.contains("烤面包"));
        assertTrue(tokens.contains("面包"));
        assertFalse(tokens.contains("包的"));
    }

    @Test
    void queryTokenWeightsPreferWholeEntityOverDerivedPrefix() {
        Map<String, Double> weights = TextSearchUtils.queryTokenWeights("奥莉诺女王", ProjectLexicon.empty());

        assertTrue(weights.get("奥莉诺") > weights.get("奥莉"));
        assertTrue(weights.get("奥莉诺女王") >= weights.get("奥莉诺"));
        assertTrue(weights.get("女王") > weights.get("奥莉"));
    }

    @Test
    void queryTokenWeightsPreferFullTownNameOverGenericPrefix() {
        Map<String, Double> weights = TextSearchUtils.queryTokenWeights("碎石镇", ProjectLexicon.empty());

        assertTrue(weights.get("碎石镇") > weights.get("碎石"));
        assertFalse(weights.containsKey("石镇"));
    }
}
