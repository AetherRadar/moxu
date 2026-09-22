package com.wenshape.controller;

import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.context.ContextType;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.WorldCard;
import com.wenshape.search.ProjectLexiconResolver;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import com.wenshape.storage.ProjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceControllerFusionTest {

    @TempDir
    Path tempDir;

    @Test
    void multiQuerySearchKeepsEvidenceThatOnlyAppearsInSpecificSubQuery() {
        FakeContextSelectEngine selectEngine = new FakeContextSelectEngine();
        selectEngine.put("合欢宗", List.of(
                fact("F1", 1.60, "合欢宗传承已有千年。"),
                fact("F2", 1.42, "合欢宗弟子修习双修秘法。")
        ));
        selectEngine.put("天赋奇才分级", List.of(
                fact("F3", 0.92, "修仙界将天赋奇才分为天地玄黄四阶。")
        ));
        selectEngine.put("合欢宗 天赋奇才分级", List.of(
                fact("F1", 1.78, "合欢宗传承已有千年。"),
                fact("F2", 1.51, "合欢宗弟子修习双修秘法。")
        ));

        EvidenceController controller = new EvidenceController(
                selectEngine,
                new TestCardStorage(),
                new TestCanonStorage(),
                new TestDraftStorage(),
                new TestMemoryPackStorage(),
                new DummyLlmGateway(),
                resolver(new TestCardStorage())
        );

        Map<String, Object> response = controller.searchEvidence("p", new LinkedHashMap<>(Map.of(
                "queries", List.of("合欢宗", "天赋奇才分级"),
                "types", List.of("fact"),
                "limit", 3
        )));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        List<String> ids = items.stream().map(item -> String.valueOf(item.get("id"))).toList();

        assertTrue(ids.contains("F3"), "多 query 融合后，应该保留只在具体子查询中命中的证据");
    }

    @Test
    void multiQueryCoverageBoostsCrossQueryEvidenceAboveSingleQueryNoise() {
        FakeContextSelectEngine selectEngine = new FakeContextSelectEngine();
        selectEngine.put("陆玄", List.of(
                fact("F1", 1.45, "陆玄独自踏出密室。"),
                fact("F3", 1.10, "陆玄与月姬的命运在此刻交错。")
        ));
        selectEngine.put("月姬", List.of(
                fact("F2", 1.40, "月姬留下枯骨，众弟子震骇。"),
                fact("F3", 1.05, "陆玄与月姬的命运在此刻交错。")
        ));

        EvidenceController controller = new EvidenceController(
                selectEngine,
                new TestCardStorage(),
                new TestCanonStorage(),
                new TestDraftStorage(),
                new TestMemoryPackStorage(),
                new DummyLlmGateway(),
                resolver(new TestCardStorage())
        );

        Map<String, Object> response = controller.searchEvidence("p", new LinkedHashMap<>(Map.of(
                "queries", List.of("陆玄", "月姬"),
                "types", List.of("fact"),
                "limit", 1
        )));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertEquals("F3", items.getFirst().get("id"));
    }

    @Test
    void memoryEvidenceUsesProjectLexiconToPreferExactLongEntityPhrase() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.worldCards = Map.of(
                "白银审判骑士团", WorldCard.builder()
                        .name("白银审判骑士团")
                        .description("边境的精锐骑士团。")
                        .build()
        );
        TestDraftStorage draftStorage = new TestDraftStorage();
        draftStorage.chapters = List.of("V1C1");
        TestMemoryPackStorage memoryPackStorage = new TestMemoryPackStorage();
        memoryPackStorage.packs = Map.of(
                "V1C1", Map.of(
                        "payload", Map.of(
                                "working_memory", "白银审判骑士团统帅边境军。"
                        ),
                        "chapter_digest", Map.of(
                                "summary", "白银骑士团负责审判叛徒。"
                        )
                )
        );

        EvidenceController controller = new EvidenceController(
                new FakeContextSelectEngine(),
                cardStorage,
                new TestCanonStorage(),
                draftStorage,
                memoryPackStorage,
                new DummyLlmGateway(),
                resolver(cardStorage)
        );

        Map<String, Object> response = controller.searchEvidence("p", new LinkedHashMap<>(Map.of(
                "queries", List.of("白银审判骑士团"),
                "types", List.of("memory"),
                "limit", 1
        )));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertEquals("memory:V1C1:working_memory", items.getFirst().get("id"));
    }

    private ProjectLexiconResolver resolver(CardStorage cardStorage) {
        ProjectLexiconResolver resolver = new ProjectLexiconResolver(cardStorage);
        ReflectionTestUtils.setField(resolver, "dataDir", tempDir.toString());
        return resolver;
    }

    private static ContextItem fact(String id, double score, String text) {
        return ContextItem.builder()
                .id(id)
                .type(ContextType.FACT)
                .content(text)
                .relevanceScore(score)
                .metadata(Map.of(
                        "fact_id", id,
                        "introduced_in", "V1C1"
                ))
                .build();
    }

    private static class FakeContextSelectEngine extends ContextSelectEngine {
        private final Map<String, List<ContextItem>> hitsByQuery = new LinkedHashMap<>();

        private FakeContextSelectEngine() {
            super(new CardStorage(), new CanonStorage(), new DraftStorage(), new ProjectLexiconResolver(new CardStorage()));
        }

        private void put(String query, List<ContextItem> items) {
            hitsByQuery.put(query, items);
        }

        @Override
        public List<ContextItem> retrievalSelect(String projectId, String query,
                                                 List<String> itemTypes, int topK,
                                                 List<String> textChunkChapters,
                                                 List<String> textChunkExcludeChapters) {
            return hitsByQuery.getOrDefault(query, List.of()).stream()
                    .limit(topK)
                    .toList();
        }
    }

    private static class TestCardStorage extends CardStorage {
        Map<String, WorldCard> worldCards = Map.of();

        @Override
        public List<String> listWorldCards(String projectId) {
            return worldCards.keySet().stream().toList();
        }

        @Override
        public java.util.Optional<WorldCard> getWorldCard(String projectId, String cardName) {
            return java.util.Optional.ofNullable(worldCards.get(cardName));
        }
    }

    private static class TestCanonStorage extends CanonStorage {
    }

    private static class TestMemoryPackStorage extends MemoryPackStorage {
        Map<String, Map<String, Object>> packs = Map.of();

        @Override
        public Map<String, Object> readPack(String projectId, String chapter) {
            return packs.getOrDefault(chapter, Map.of());
        }
    }

    private static class TestDraftStorage extends DraftStorage {
        List<String> chapters = List.of();

        @Override
        public List<ChapterSummary> listChapterSummaries(String projectId, String volumeId) {
            return List.of();
        }

        @Override
        public List<String> listChapters(String projectId) {
            return chapters;
        }

        @Override
        public List<Map<String, Object>> searchTextChunks(String projectId, String query, int limit,
                                                          List<String> chapters, List<String> excludeChapters,
                                                          boolean rebuild) {
            return List.of();
        }
    }

    private static class DummyLlmGateway extends LlmGateway {
        private DummyLlmGateway() {
            super(new ProjectStorage());
        }
    }
}
