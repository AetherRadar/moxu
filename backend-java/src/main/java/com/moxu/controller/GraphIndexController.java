package com.moxu.controller;

import com.moxu.storage.GraphIndexStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导书关系索引读取接口。
 */
@RestController
@RequestMapping("/projects/{projectId}/graph-indexes")
@RequiredArgsConstructor
public class GraphIndexController {

    private final GraphIndexStorage graphIndexStorage;

    @GetMapping("/relationships")
    public Map<String, Object> getRelationshipIndexes(@PathVariable String projectId) {
        Map<String, Object> aliasIndex = graphIndexStorage.getCharacterAliasIndex(projectId)
                .orElseGet(() -> Map.of("built_at", "", "characters", Map.of()));
        Map<String, Object> chapterMentions = graphIndexStorage.getChapterMentionsIndex(projectId)
                .orElseGet(() -> Map.of("built_at", "", "chapters", Map.of()));
        Map<String, Object> relationCandidates = graphIndexStorage.getRelationCandidatesIndex(projectId)
                .orElseGet(() -> Map.of("built_at", "", "pairs", List.of()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("built", !Map.of().equals(aliasIndex.getOrDefault("characters", Map.of()))
                || !Map.of().equals(chapterMentions.getOrDefault("chapters", Map.of()))
                || !List.of().equals(relationCandidates.getOrDefault("pairs", List.of())));
        response.put("alias_index", aliasIndex);
        response.put("chapter_mentions", chapterMentions);
        response.put("relation_candidates", relationCandidates);
        response.put("stats", Map.of(
                "characters_indexed", mapSize(aliasIndex.get("characters")),
                "chapters_indexed", mapSize(chapterMentions.get("chapters")),
                "relation_pairs", listSize(relationCandidates.get("pairs"))
        ));
        return response;
    }

    @SuppressWarnings("unchecked")
    private int mapSize(Object value) {
        return value instanceof Map<?, ?> map ? map.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }
}
