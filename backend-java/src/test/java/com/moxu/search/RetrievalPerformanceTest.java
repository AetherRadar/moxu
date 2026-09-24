package com.moxu.search;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Synthetic CPU benchmark only; correctness is asserted, timing is reported without a flaky threshold. */
class RetrievalPerformanceTest {
    @Test void comparePreparedScoringWithOriginalFormula() {
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            documents.add("奥莉诺在碎石镇酒馆寻找白银审判骑士团，王方必须支付代价。记录 " + i);
        ProjectLexicon lexicon = ProjectLexicon.fromTerms(List.of("奥莉诺", "白银审判骑士团", "碎石镇"));
        String query = "奥莉诺 碎石镇 代价";
        reference(documents.subList(0, 10), query, lexicon);
        prepared(documents.subList(0, 10), query, lexicon);
        long[] before = new long[3], after = new long[3];
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            double baseline = reference(documents, query, lexicon);
            before[i] = System.nanoTime() - start;
            start = System.nanoTime();
            double optimized = prepared(documents, query, lexicon);
            after[i] = System.nanoTime() - start;
            assertEquals(baseline, optimized, 1e-9);
        }
        Arrays.sort(before); Arrays.sort(after);
        System.out.printf(Locale.ROOT,
                "RETRIEVAL_BENCHMARK documents=200 baseline_median_ms=%.3f prepared_median_ms=%.3f ratio=%.3f%n",
                before[1] / 1e6, after[1] / 1e6, (double) after[1] / before[1]);
    }
    private double reference(List<String> documents, String query, ProjectLexicon lexicon) {
        var idf = TextSearchUtils.buildIdfTable(documents, lexicon);
        double total = 0;
        for (String text : documents) {
            double overlap = TextSearchUtils.calculateOverlapScore(query, text, lexicon);
            if (overlap > 0) total += overlap * 0.35
                    + TextSearchUtils.calculateBm25Score(query, text, idf, lexicon) * 0.65;
        }
        return total;
    }
    private double prepared(List<String> documents, String query, ProjectLexicon lexicon) {
        var scorer = TextSearchUtils.scorer(query, lexicon);
        var idf = scorer.buildIdfTable(documents);
        double total = 0;
        for (String text : documents) total += scorer.score(text, idf);
        assertEquals(documents.size(), scorer.preparedDocumentCount());
        return total;
    }
}
