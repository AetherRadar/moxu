package com.moxu.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.model.entity.WorldCard;
import com.moxu.search.ProjectLexiconResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DraftStorageTextChunkSearchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void rebuiltIndexContainsUsefulChineseTermsAndSearchCanHitThem() throws Exception {
        DraftStorage storage = new DraftStorage();
        CardStorage cardStorage = new CardStorage();
        ProjectLexiconResolver resolver = new ProjectLexiconResolver(cardStorage);
        ReflectionTestUtils.setField(storage, "dataDir", tempDir.toString());
        ReflectionTestUtils.setField(cardStorage, "dataDir", tempDir.toString());
        ReflectionTestUtils.setField(resolver, "dataDir", tempDir.toString());
        ReflectionTestUtils.setField(storage, "projectLexiconResolver", resolver);

        String projectId = "测试项目";
        String chapter = "V1C1";
        String content = """
                碎石镇的清晨从来不是安静的。

                与远处酒馆里传来的醉汉笑骂声混在一起。

                奥莉诺女王缓缓走来。

                白银审判骑士团的旗帜插在酒馆门前。
                """;

        cardStorage.saveWorldCard(projectId, WorldCard.builder()
                .name("审判团")
                .aliases(List.of("白银审判骑士团"))
                .description("边境最强大的骑士组织")
                .build());
        storage.saveFinalDraft(projectId, chapter, content);
        Map<String, Object> meta = storage.rebuildTextChunkIndex(projectId);

        assertEquals(5, ((Number) meta.get("tokenizer_version")).intValue());
        assertEquals(2, ((Number) meta.get("lexicon_term_count")).intValue());

        Path invertedPath = tempDir.resolve(projectId).resolve("text_chunk_index").resolve("inverted_index.json");
        assertTrue(Files.exists(invertedPath));

        Map<String, List<String>> inverted = objectMapper.readValue(
                Files.readString(invertedPath),
                new TypeReference<>() {
                }
        );

        assertTrue(inverted.containsKey("碎石镇"));
        assertTrue(inverted.containsKey("酒馆"));
        assertTrue(inverted.containsKey("奥莉诺"));
        assertTrue(inverted.containsKey("奥莉"));
        assertTrue(inverted.containsKey("白银审判骑士团"));

        assertFalse(inverted.containsKey("镇的"));
        assertFalse(inverted.containsKey("处酒"));
        assertFalse(inverted.containsKey("石镇"));
        assertFalse(inverted.containsKey("莉诺"));

        List<Map<String, Object>> hits = storage.searchTextChunks(projectId, "酒馆", 5, null, null, false);
        assertFalse(hits.isEmpty());
        assertTrue(String.valueOf(hits.getFirst().get("text")).contains("酒馆"));

        List<Map<String, Object>> entityHits = storage.searchTextChunks(projectId, "奥莉诺女王", 5, null, null, false);
        assertFalse(entityHits.isEmpty());
        assertTrue(String.valueOf(entityHits.getFirst().get("text")).contains("奥莉诺女王"));

        List<Map<String, Object>> shortEntityHits = storage.searchTextChunks(projectId, "奥莉", 5, null, null, false);
        assertFalse(shortEntityHits.isEmpty());
        assertTrue(String.valueOf(shortEntityHits.getFirst().get("text")).contains("奥莉诺女王"));

        List<Map<String, Object>> lexiconHits = storage.searchTextChunks(projectId, "白银审判骑士团", 5, null, null, false);
        assertFalse(lexiconHits.isEmpty());
        assertTrue(String.valueOf(lexiconHits.getFirst().get("text")).contains("白银审判骑士团"));
    }
}
