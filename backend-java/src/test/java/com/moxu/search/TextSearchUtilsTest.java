package com.moxu.search;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TextSearchUtilsTest {

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
