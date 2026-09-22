package com.moxu.controller;

import com.moxu.context.ContextItem;
import com.moxu.context.ContextSelectEngine;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.StyleCard;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.search.TextSearchUtils;
import com.moxu.storage.CardStorage;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 证据控制器 - 证据搜索
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/evidence")
@RequiredArgsConstructor
public class EvidenceController {

    private static final double COMBINED_QUERY_WEIGHT = 0.55;
    private static final double FUSION_MIN_MATCH_SCORE = 0.12;
    private static final double FUSION_SECONDARY_WEIGHT = 0.38;
    private static final double FUSION_COVERAGE_BONUS = 0.55;
    private static final double FUSION_MULTI_QUERY_BONUS = 0.12;
    
    private final ContextSelectEngine selectEngine;
    private final CardStorage cardStorage;
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;
    private final MemoryPackStorage memoryPackStorage;
    private final LlmGateway llmGateway;
    private final ProjectLexiconResolver projectLexiconResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 搜索证据
     * POST /projects/{projectId}/evidence/search
     */
    @PostMapping("/search")
    public Map<String, Object> searchEvidence(@PathVariable String projectId,
                                               @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) request.getOrDefault("queries", List.of());
        @SuppressWarnings("unchecked")
        List<String> requestedTypes = (List<String>) request.get("types");
        List<String> types = normalizeTypes(requestedTypes);
        int limit = request.get("limit") instanceof Number limitNumber ? limitNumber.intValue() : 12;
        boolean includeTextChunks = !Boolean.FALSE.equals(request.get("include_text_chunks"));
        @SuppressWarnings("unchecked")
        List<String> seedEntities = (List<String>) request.getOrDefault("seed_entities", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> quotas = (Map<String, Map<String, Integer>>) request.get("quotas");
        @SuppressWarnings("unchecked")
        List<String> textChunkChapters = (List<String>) request.get("text_chunk_chapters");
        @SuppressWarnings("unchecked")
        List<String> excludeChapters = (List<String>) request.get("text_chunk_exclude_chapters");
        boolean rebuild = Boolean.TRUE.equals(request.get("rebuild"));
        boolean semanticRerank = Boolean.TRUE.equals(request.get("semantic_rerank"));
        String rerankQuery = request.get("rerank_query") instanceof String rerankQueryText ? rerankQueryText : null;
        int rerankTopK = request.get("rerank_top_k") instanceof Number rerankTopKNumber ? rerankTopKNumber.intValue() : 16;
        Integer round = request.get("round") instanceof Number roundNumber ? roundNumber.intValue() : null;
        String note = request.get("note") instanceof String noteText ? noteText : "";
        
        // 合并查询
        List<String> mergedQueries = new ArrayList<>();
        if (queries != null) mergedQueries.addAll(queries);
        if (seedEntities != null) mergedQueries.addAll(seedEntities);
        mergedQueries = mergedQueries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        ProjectLexicon projectLexicon = resolveProjectLexicon(projectId);
        Map<String, Double> queryWeights = buildQueryWeights(mergedQueries, projectLexicon);
        String combinedQuery = mergedQueries.stream()
                .collect(Collectors.joining(" "));
        
        List<String> selectTypes = new ArrayList<>();
        if (types.contains("character")) selectTypes.add("character");
        if (types.contains("world_entity") || types.contains("world_rule")) selectTypes.add("world");
        if (types.contains("fact")) selectTypes.add("fact");
        if (types.contains("summary")) selectTypes.add("summary");
        if (types.contains("style")) selectTypes.add("style");
        if (types.contains("text_chunk")) selectTypes.add("text_chunk");

        List<Map<String, Object>> resultItems = new ArrayList<>(searchContextEvidence(
                projectId,
                mergedQueries,
                combinedQuery,
                queryWeights,
                projectLexicon,
                selectTypes,
                Math.max(limit * 2, 24),
                textChunkChapters,
                excludeChapters
        ));

        if (types.contains("summary") && !mergedQueries.isEmpty()) {
            resultItems.addAll(searchSummaryEvidence(
                    projectId,
                    mergedQueries,
                    combinedQuery,
                    queryWeights,
                    projectLexicon,
                    textChunkChapters
            ));
        }

        if (types.contains("style") && !mergedQueries.isEmpty()) {
            searchStyleEvidence(projectId, mergedQueries, combinedQuery, queryWeights, projectLexicon).ifPresent(resultItems::add);
        }

        if (includeTextChunks && types.contains("text_chunk") && !mergedQueries.isEmpty()) {
            List<Map<String, Object>> textChunks = searchTextChunkEvidence(
                    projectId,
                    Math.max(limit, rerankTopK),
                    mergedQueries,
                    combinedQuery,
                    queryWeights,
                    textChunkChapters,
                    excludeChapters,
                    rebuild
            );
            if (semanticRerank) {
                textChunks = rerankTextChunks(
                        textChunks,
                        (rerankQuery != null && !rerankQuery.isBlank()) ? rerankQuery : combinedQuery,
                        rerankTopK
                );
            }
            for (Map<String, Object> chunk : textChunks) {
                resultItems.add(toChunkEvidenceItem(chunk));
            }
        }

        if (types.contains("memory") && !mergedQueries.isEmpty()) {
            resultItems.addAll(searchMemoryEvidence(
                    projectId,
                    mergedQueries,
                    combinedQuery,
                    queryWeights,
                    projectLexicon,
                    textChunkChapters
            ));
        }

        // 去重：避免 ContextSelectEngine 和 draftStorage 两条路径产生重复 text_chunk
        resultItems = dedupeById(resultItems);

        Map<String, Map<String, Integer>> mergedQuotas = mergeQuotas(types, quotas);
        resultItems = applyTypeQuotas(resultItems, mergedQuotas, limit);
        
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", resultItems.size());
        stats.put("types", countTypes(resultItems));
        stats.put("queries", mergedQueries);
        stats.put("hits", resultItems.size());
        stats.put("top_sources", extractTopSources(resultItems, 3));
        stats.put("limit", limit);
        stats.put("types_requested", types);
        stats.put("semantic_rerank", semanticRerank && rerankQuery != null && !rerankQuery.isBlank());
        stats.put("rerank_query", rerankQuery != null ? rerankQuery : "");
        stats.put("rerank_top_k", rerankTopK);
        stats.put("round", round);
        stats.put("note", note);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", resultItems);
        response.put("stats", stats);
        return response;
    }
    
    /**
     * 重建证据索引
     * POST /projects/{projectId}/evidence/rebuild
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuildEvidence(@PathVariable String projectId) {
        log.info("Rebuilding evidence index for project: {}", projectId);
        Map<String, Object> textChunkMeta = draftStorage.rebuildTextChunkIndex(projectId);
        int factCount = canonStorage.listFacts(projectId).size();
        int summaryCount = draftStorage.listChapterSummaries(projectId, null).size();
        int characterCount = cardStorage.listCharacterCards(projectId).size();
        int worldCount = cardStorage.listWorldCards(projectId).size();
        boolean hasStyle = cardStorage.getStyleCard(projectId).isPresent();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rebuilt_at", System.currentTimeMillis());
        meta.put("text_chunks", textChunkMeta);
        meta.put("facts", Map.of(
                "index_name", "facts",
                "built_at", System.currentTimeMillis() / 1000.0,
                "item_count", factCount,
                "details", Map.of("source", "canon")
        ));
        meta.put("summaries", Map.of(
                "index_name", "summaries",
                "built_at", System.currentTimeMillis() / 1000.0,
                "item_count", summaryCount,
                "details", Map.of("source", "summaries")
        ));
        meta.put("cards", Map.of(
                "index_name", "cards",
                "built_at", System.currentTimeMillis() / 1000.0,
                "item_count", characterCount + worldCount + (hasStyle ? 1 : 0),
                "details", Map.of(
                        "characters", characterCount,
                        "world", worldCount,
                        "style", hasStyle ? 1 : 0
                )
        ));
        return Map.of(
                "success", true,
                "meta", meta
        );
    }

    private Map<String, Integer> countTypes(List<Map<String, Object>> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            if (type.isBlank()) {
                continue;
            }
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return counts;
    }

    private Map<String, Object> toEvidenceItem(ContextItem item) {
        if (item == null || item.getType() == null || item.getContent() == null || item.getContent().isBlank()) {
            return null;
        }
        Map<String, Object> metadata = item.getMetadata() != null ? new LinkedHashMap<>(item.getMetadata()) : Map.of();
        String type = resolveEvidenceType(item.getType().getValue(), metadata);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("type", type);
        map.put("text", item.getContent());
        map.put("score", item.getRelevanceScore());
        map.put("source", extractEvidenceSource(metadata));
        Object scopeValue = metadata.get("scope");
        map.put("scope", scopeValue == null ? null : scopeValue.toString().trim());
        map.put("entities", extractStringList(metadata.get("entities")));
        map.put("meta", metadata.get("meta") instanceof Map<?, ?> meta ? castStringObjectMap(meta) : Map.of());
        return map;
    }

    private Map<String, Object> toChunkEvidenceItem(Map<String, Object> chunk) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "text_chunk:" + chunk.get("chapter") + ":" + chunk.get("chunk_index"));
        item.put("type", "text_chunk");
        item.put("text", chunk.get("text"));
        item.put("score", chunk.get("score"));
        item.put("source", chunk.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of());
        item.put("scope", "chapter");
        item.put("entities", List.of());
        item.put("meta", chunk.get("meta") instanceof Map<?, ?> meta ? castStringObjectMap(meta) : Map.of());
        return item;
    }

    private String resolveEvidenceType(String rawType, Map<String, Object> metadata) {
        String evidenceType = String.valueOf(metadata.getOrDefault("evidence_type", "")).trim();
        if (!evidenceType.isBlank()) {
            return evidenceType;
        }
        String type = rawType == null ? "" : rawType.trim();
        if ("character_card".equals(type)) return "character";
        if ("world_card".equals(type)) return "world_entity";
        if ("chapter_summary".equals(type)) return "summary";
        if ("style_card".equals(type)) return "style";
        return type;
    }

    private Map<String, Object> extractEvidenceSource(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (Set.of("evidence_type", "scope", "entities", "meta").contains(key)) {
                continue;
            }
            source.put(key, entry.getValue());
        }
        return source;
    }

    private List<String> extractStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = item == null ? "" : item.toString().trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private List<Map<String, Object>> searchContextEvidence(String projectId,
                                                            List<String> queries,
                                                            String combinedQuery,
                                                            Map<String, Double> queryWeights,
                                                            ProjectLexicon projectLexicon,
                                                            List<String> selectTypes,
                                                            int limit,
                                                            List<String> textChunkChapters,
                                                            List<String> excludeChapters) {
        if (queries == null || queries.isEmpty() || selectTypes == null || selectTypes.isEmpty()) {
            return List.of();
        }
        int perQueryLimit = Math.max(6, Math.min(limit, 12));
        Map<String, FusedItemAccumulator> merged = new LinkedHashMap<>();
        for (String query : queries) {
            List<ContextItem> hits = selectEngine.retrievalSelect(
                    projectId,
                    query,
                    selectTypes,
                    perQueryLimit,
                    textChunkChapters,
                    excludeChapters
            );
            for (ContextItem hit : hits) {
                Map<String, Object> item = toEvidenceItem(hit);
                if (item == null) {
                    continue;
                }
                accumulateFusedItem(merged, item, query, hit.getRelevanceScore(), false);
            }
        }
        if (queries.size() > 1 && !combinedQuery.isBlank()) {
            List<ContextItem> hits = selectEngine.retrievalSelect(
                    projectId,
                    combinedQuery,
                    selectTypes,
                    Math.max(6, Math.min(limit, 10)),
                    textChunkChapters,
                    excludeChapters
            );
            for (ContextItem hit : hits) {
                Map<String, Object> item = toEvidenceItem(hit);
                if (item == null) {
                    continue;
                }
                accumulateFusedItem(merged, item, combinedQuery, hit.getRelevanceScore(), true);
            }
        }
        return finalizeFusedItems(merged, queryWeights, Math.max(limit, 16));
    }

    private List<Map<String, Object>> searchSummaryEvidence(String projectId,
                                                            List<String> queries,
                                                            String combinedQuery,
                                                            Map<String, Double> queryWeights,
                                                            ProjectLexicon projectLexicon,
                                                            List<String> chapterFilter) {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> allowedChapters = chapterFilter == null ? null : new LinkedHashSet<>(chapterFilter);
        List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, null);
        Map<String, Double> idfTable = TextSearchUtils.buildIdfTable(
                summaries.stream()
                        .map(this::buildSummaryText)
                        .filter(Objects::nonNull)
                        .filter(s -> !s.isBlank())
                        .toList(),
                projectLexicon
        );
        for (ChapterSummary summary : summaries) {
            if (summary == null || summary.getChapter() == null || summary.getChapter().isBlank()) {
                continue;
            }
            if (allowedChapters != null && !allowedChapters.isEmpty() && !allowedChapters.contains(summary.getChapter())) {
                continue;
            }
            String text = buildSummaryText(summary);
            FusionScore fusion = scoreAcrossQueries(queries, combinedQuery, queryWeights, text, idfTable, projectLexicon);
            if (fusion.fusedScore <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "summary:" + summary.getChapter());
            item.put("type", "summary");
            item.put("text", text);
            item.put("score", fusion.fusedScore);
            item.put("source", Map.of("chapter", summary.getChapter(), "field", "brief_summary"));
            item.put("scope", "chapter");
            item.put("entities", List.of());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("title", summary.getTitle() != null ? summary.getTitle() : "");
            applyFusionMeta(meta, fusion);
            item.put("meta", meta);
            items.add(item);
        }
        items.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        return items;
    }

    private Optional<Map<String, Object>> searchStyleEvidence(String projectId,
                                                              List<String> queries,
                                                              String combinedQuery,
                                                              Map<String, Double> queryWeights,
                                                              ProjectLexicon projectLexicon) {
        Optional<StyleCard> styleOpt = cardStorage.getStyleCard(projectId);
        if (styleOpt.isEmpty() || styleOpt.get().getStyle() == null || styleOpt.get().getStyle().isBlank()) {
            return Optional.empty();
        }
        String text = styleOpt.get().getStyle().trim();
        FusionScore fusion = scoreAcrossQueries(queries, combinedQuery, queryWeights, text, Map.of(), projectLexicon);
        if (fusion.fusedScore <= 0) {
            return Optional.empty();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "style_card");
        item.put("type", "style");
        item.put("text", text);
        item.put("score", fusion.fusedScore);
        item.put("source", Map.of("field", "style"));
        item.put("scope", "global");
        item.put("entities", List.of());
        Map<String, Object> meta = new LinkedHashMap<>();
        applyFusionMeta(meta, fusion);
        item.put("meta", meta);
        return Optional.of(item);
    }

    private List<Map<String, Object>> dedupeById(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            if (item == null) continue;
            String id = String.valueOf(item.getOrDefault("id", ""));
            if (id.isBlank()) continue;
            Map<String, Object> existing = deduped.get(id);
            if (existing == null) {
                deduped.put(id, item);
                continue;
            }
            double a = existing.get("score") instanceof Number existingScore ? existingScore.doubleValue() : 0.0;
            double b = item.get("score") instanceof Number itemScore ? itemScore.doubleValue() : 0.0;
            if (b > a) {
                deduped.put(id, item);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Map<String, Object>> rerankTextChunks(List<Map<String, Object>> chunks, String query, int rerankTopK) {
        if (chunks == null || chunks.isEmpty() || query == null || query.isBlank()) {
            return chunks == null ? List.of() : chunks;
        }
        if (chunks.size() < 3) {
            return chunks;
        }
        int topK = Math.min(Math.max(3, rerankTopK), chunks.size());
        List<Map<String, Object>> candidates = new ArrayList<>(chunks.subList(0, topK));

        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> c = candidates.get(i);
            String id = "c" + i;
            c.put("_rerank_id", id);
            String text = String.valueOf(c.getOrDefault("text", ""));
            String clipped = text.length() > 500 ? text.substring(0, 500) : text;
            payload.append(id).append(": ").append(clipped.replace("\n", " ")).append("\n");
        }

        String prompt = """
                请根据查询对候选段落进行相关性重排，仅返回 JSON：
                {"ranked_ids":["c2","c0","c1"]}
                查询：
                %s
                候选：
                %s
                """.formatted(query, payload);
        try {
            String provider = llmGateway.getProviderForAgent("writer");
            LlmResponse response = llmGateway.chat(List.of(
                    Map.of("role", "user", "content", prompt)
            ), provider, 0.0, 1200);
            Map<String, Object> json = parseJsonObject(response.getContent());
            Object idsObj = json.get("ranked_ids");
            if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
                return chunks;
            }

            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> c : candidates) {
                byId.put(String.valueOf(c.get("_rerank_id")), c);
            }
            List<Map<String, Object>> reranked = new ArrayList<>();
            for (Object idObj : ids) {
                String id = String.valueOf(idObj);
                Map<String, Object> hit = byId.get(id);
                if (hit != null) {
                    reranked.add(hit);
                }
            }
            // append未参与重排的剩余项，保持结果数量稳定
            Set<String> used = reranked.stream().map(c -> String.valueOf(c.get("_rerank_id"))).collect(Collectors.toSet());
            for (Map<String, Object> c : chunks) {
                String id = String.valueOf(c.getOrDefault("_rerank_id", ""));
                if (id.isBlank() || !used.contains(id)) {
                    reranked.add(c);
                }
            }
            for (Map<String, Object> c : reranked) {
                c.remove("_rerank_id");
            }
            return reranked;
        } catch (Exception e) {
            log.debug("semantic rerank skipped: {}", e.getMessage());
            for (Map<String, Object> c : chunks) {
                c.remove("_rerank_id");
            }
            return chunks;
        }
    }

    private Map<String, Object> parseJsonObject(String raw) throws Exception {
        String content = raw == null ? "" : raw.trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    private List<Map<String, Object>> searchMemoryEvidence(String projectId,
                                                           List<String> queries,
                                                           String combinedQuery,
                                                           Map<String, Double> queryWeights,
                                                           ProjectLexicon projectLexicon,
                                                           List<String> chapters) {
        List<String> cleaned = queries == null ? List.of() : queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> chapterCandidates = chapters != null && !chapters.isEmpty()
                ? chapters
                : draftStorage.listChapters(projectId).stream().sorted().skip(Math.max(0, draftStorage.listChapters(projectId).size() - 6L)).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> candidateItems = new ArrayList<>();
        for (String chapter : chapterCandidates) {
            Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
            if (pack == null || pack.isEmpty()) {
                continue;
            }
            candidateItems.addAll(extractMemoryItems(chapter, pack));
        }
        Map<String, Double> idfTable = TextSearchUtils.buildIdfTable(
                candidateItems.stream()
                        .map(item -> String.valueOf(item.getOrDefault("text", "")))
                        .filter(s -> !s.isBlank())
                        .toList(),
                projectLexicon
        );
        for (Map<String, Object> item : candidateItems) {
            String text = String.valueOf(item.getOrDefault("text", ""));
            FusionScore fusion = scoreAcrossQueries(cleaned, combinedQuery, queryWeights, text, idfTable, projectLexicon);
            if (fusion.fusedScore <= 0) {
                continue;
            }
            item.put("score", fusion.fusedScore);
            Map<String, Object> meta = item.get("meta") instanceof Map<?, ?> existing
                    ? castStringObjectMap(existing)
                    : new LinkedHashMap<>();
            applyFusionMeta(meta, fusion);
            item.put("meta", meta);
            results.add(item);
        }
        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        return results.size() > 8 ? new ArrayList<>(results.subList(0, 8)) : results;
    }

    private List<Map<String, Object>> extractMemoryItems(String chapter, Map<String, Object> pack) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object payloadObj = pack.get("payload");
        if (payloadObj instanceof Map<?, ?> payload) {
            String workingMemory = stringValue(payload, "working_memory", "");
            if (!workingMemory.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":working_memory", chapter, workingMemory, "working_memory"));
            }
            Object evidencePack = payload.get("evidence_pack");
            if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> evidenceItems) {
                int count = 0;
                for (Object item : evidenceItems) {
                    if (item instanceof Map<?, ?> ev) {
                        String text = stringValue(ev, "text", "");
                        if (!text.isBlank()) {
                            items.add(memoryItem("memory:" + chapter + ":evidence:" + count, chapter, text, "evidence_pack"));
                            if (++count >= 4) break;
                        }
                    }
                }
            }
        }
        Object digestObj = pack.get("chapter_digest");
        if (digestObj instanceof Map<?, ?> digest) {
            String summary = stringValue(digest, "summary", "");
            if (!summary.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":summary", chapter, summary, "chapter_digest"));
            }
            String tail = stringValue(digest, "tail_excerpt", "");
            if (!tail.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":tail", chapter, tail, "tail_excerpt"));
            }
        }
        return items;
    }

    private Map<String, Object> memoryItem(String id, String chapter, String text, String field) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "memory");
        item.put("text", text);
        item.put("score", 0.0);
        item.put("source", Map.of("chapter", chapter, "field", field));
        item.put("scope", "chapter");
        item.put("entities", List.of());
        item.put("meta", Map.of());
        return item;
    }

    private List<String> normalizeTypes(List<String> requestedTypes) {
        List<String> defaults = List.of(
                "fact",
                "summary",
                "character",
                "world_rule",
                "world_entity",
                "style",
                "text_chunk",
                "memory"
        );
        if (requestedTypes == null || requestedTypes.isEmpty()) {
            return defaults;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : requestedTypes) {
            String type = raw == null ? "" : raw.trim();
            if (type.isBlank()) {
                continue;
            }
            if ("world".equals(type) || "world_entity".equals(type) || "world_rule".equals(type)) {
                normalized.add("world_entity");
                normalized.add("world_rule");
                continue;
            }
            if ("chapter_summary".equals(type)) {
                normalized.add("summary");
                continue;
            }
            normalized.add(type);
        }
        return normalized.isEmpty() ? defaults : new ArrayList<>(normalized);
    }

    private String buildSummaryText(ChapterSummary summary) {
        String title = summary.getTitle() == null ? "" : summary.getTitle().trim();
        String brief = summary.getBriefSummary() == null ? "" : summary.getBriefSummary().trim();
        if (title.isBlank()) {
            return brief;
        }
        if (brief.isBlank()) {
            return title;
        }
        return title + "\n" + brief;
    }

    private List<Map<String, Object>> searchTextChunkEvidence(String projectId,
                                                              int limit,
                                                              List<String> queries,
                                                              String combinedQuery,
                                                              Map<String, Double> queryWeights,
                                                              List<String> chapters,
                                                              List<String> excludeChapters,
                                                              boolean rebuild) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        int perQueryLimit = Math.max(6, Math.min(limit, 10));
        Map<String, FusedItemAccumulator> merged = new LinkedHashMap<>();
        for (String query : queries) {
            List<Map<String, Object>> hits = draftStorage.searchTextChunks(
                    projectId,
                    query,
                    perQueryLimit,
                    chapters,
                    excludeChapters,
                    rebuild
            );
            for (Map<String, Object> hit : hits) {
                accumulateFusedItem(merged, normalizeTextChunkHit(hit), query, numberValue(hit.get("score")), false);
            }
        }
        if (queries.size() > 1 && !combinedQuery.isBlank()) {
            List<Map<String, Object>> hits = draftStorage.searchTextChunks(
                    projectId,
                    combinedQuery,
                    Math.max(5, Math.min(limit, 8)),
                    chapters,
                    excludeChapters,
                    rebuild
            );
            for (Map<String, Object> hit : hits) {
                accumulateFusedItem(merged, normalizeTextChunkHit(hit), combinedQuery, numberValue(hit.get("score")), true);
            }
        }
        return finalizeFusedItems(merged, queryWeights, Math.max(limit, 12));
    }

    private Map<String, Object> normalizeTextChunkHit(Map<String, Object> hit) {
        Map<String, Object> normalized = new LinkedHashMap<>(hit);
        normalized.put("id", "text_chunk:" + hit.get("chapter") + ":" + hit.get("chunk_index"));
        normalized.putIfAbsent("type", "text_chunk");
        normalized.putIfAbsent("scope", "chapter");
        normalized.putIfAbsent("entities", List.of());
        normalized.putIfAbsent("meta", Map.of());
        return normalized;
    }

    private void accumulateFusedItem(Map<String, FusedItemAccumulator> merged,
                                     Map<String, Object> item,
                                     String query,
                                     double score,
                                     boolean combinedQuery) {
        if (item == null || score <= 0) {
            return;
        }
        String id = String.valueOf(item.getOrDefault("id", "")).trim();
        if (id.isBlank()) {
            return;
        }
        FusedItemAccumulator accumulator = merged.computeIfAbsent(id, ignored -> new FusedItemAccumulator(copyItem(item)));
        accumulator.updateBest(copyItem(item), score);
        if (combinedQuery) {
            accumulator.combinedScore = Math.max(accumulator.combinedScore, score);
        } else {
            accumulator.queryScores.merge(query, score, Math::max);
        }
    }

    private List<Map<String, Object>> finalizeFusedItems(Map<String, FusedItemAccumulator> merged,
                                                         Map<String, Double> queryWeights,
                                                         int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (FusedItemAccumulator accumulator : merged.values()) {
            FusionScore fusion = fuseQueryScores(accumulator.queryScores, accumulator.combinedScore, queryWeights);
            if (fusion.fusedScore <= 0) {
                continue;
            }
            Map<String, Object> item = copyItem(accumulator.bestItem);
            item.put("score", fusion.fusedScore);
            Map<String, Object> meta = item.get("meta") instanceof Map<?, ?> existing
                    ? castStringObjectMap(existing)
                    : new LinkedHashMap<>();
            applyFusionMeta(meta, fusion);
            item.put("meta", meta);
            results.add(item);
        }
        results.sort((a, b) -> Double.compare(
                numberValue(b.get("score")),
                numberValue(a.get("score"))
        ));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    private FusionScore scoreAcrossQueries(List<String> queries,
                                           String combinedQuery,
                                           Map<String, Double> queryWeights,
                                           String text,
                                           Map<String, Double> idfTable,
                                           ProjectLexicon projectLexicon) {
        if (queries == null || queries.isEmpty() || text == null || text.isBlank()) {
            return FusionScore.empty();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            double score = TextSearchUtils.calculateHybridScore(query, text, idfTable, projectLexicon);
            if (score > 0) {
                scores.put(query, score);
            }
        }
        double combinedScore = 0.0;
        if (combinedQuery != null && !combinedQuery.isBlank() && queries.size() > 1) {
            combinedScore = TextSearchUtils.calculateHybridScore(combinedQuery, text, idfTable, projectLexicon);
        }
        return fuseQueryScores(scores, combinedScore, queryWeights);
    }

    private FusionScore fuseQueryScores(Map<String, Double> queryScores,
                                        double combinedScore,
                                        Map<String, Double> queryWeights) {
        if (queryScores == null || queryScores.isEmpty()) {
            return FusionScore.empty();
        }
        double totalWeight = queryWeights == null || queryWeights.isEmpty()
                ? Math.max(queryScores.size(), 1)
                : queryWeights.values().stream().mapToDouble(Double::doubleValue).sum();

        double bestRaw = 0.0;
        String bestQuery = "";
        for (Map.Entry<String, Double> entry : queryScores.entrySet()) {
            if (entry.getValue() > bestRaw) {
                bestRaw = entry.getValue();
                bestQuery = entry.getKey();
            }
        }
        if (bestRaw <= 0) {
            return FusionScore.empty();
        }

        double meaningfulFloor = Math.max(FUSION_MIN_MATCH_SCORE, bestRaw * 0.35);
        Map<String, Double> meaningfulScores = new LinkedHashMap<>();
        double bestWeighted = 0.0;
        double weightedTotal = 0.0;
        double matchedWeight = 0.0;

        for (Map.Entry<String, Double> entry : queryScores.entrySet()) {
            double rawScore = entry.getValue() == null ? 0.0 : entry.getValue();
            if (rawScore < meaningfulFloor) {
                continue;
            }
            String query = entry.getKey();
            double weight = queryWeights == null || queryWeights.isEmpty()
                    ? 1.0
                    : queryWeights.getOrDefault(query, 1.0);
            double weightedScore = rawScore * weight;
            meaningfulScores.put(query, rawScore);
            matchedWeight += weight;
            weightedTotal += weightedScore;
            bestWeighted = Math.max(bestWeighted, weightedScore);
        }

        if (meaningfulScores.isEmpty()) {
            double weight = queryWeights == null || queryWeights.isEmpty()
                    ? 1.0
                    : queryWeights.getOrDefault(bestQuery, 1.0);
            meaningfulScores.put(bestQuery, bestRaw);
            matchedWeight = weight;
            bestWeighted = bestRaw * weight;
            weightedTotal = bestWeighted;
        }

        double coverage = totalWeight <= 0 ? 0.0 : matchedWeight / totalWeight;
        double secondary = Math.max(0.0, weightedTotal - bestWeighted);
        double multiBonus = Math.min(FUSION_MULTI_QUERY_BONUS * Math.max(0, meaningfulScores.size() - 1), 0.36);
        double combinedBonus = combinedScore <= 0
                ? 0.0
                : Math.min(combinedScore * COMBINED_QUERY_WEIGHT * 0.18, bestWeighted * 0.28 + 0.18);
        double fused = bestWeighted
                + Math.min(secondary * FUSION_SECONDARY_WEIGHT, bestWeighted * 0.9 + 0.45)
                + coverage * FUSION_COVERAGE_BONUS
                + multiBonus
                + combinedBonus;
        return new FusionScore(fused, bestRaw, combinedScore, coverage, meaningfulScores);
    }

    private void applyFusionMeta(Map<String, Object> meta, FusionScore fusion) {
        if (meta == null || fusion == null || fusion.fusedScore <= 0) {
            return;
        }
        meta.put("matched_queries", new ArrayList<>(fusion.queryScores.keySet()));
        if (!fusion.queryScores.isEmpty()) {
            Map<String, Double> queryScores = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : fusion.queryScores.entrySet()) {
                queryScores.put(entry.getKey(), round(entry.getValue()));
            }
            meta.put("query_scores", queryScores);
        }
        if (fusion.combinedScore > 0) {
            meta.put("combined_query_score", round(fusion.combinedScore));
        }
        meta.put("query_coverage", round(fusion.coverage));
    }

    private Map<String, Double> buildQueryWeights(List<String> queries, ProjectLexicon projectLexicon) {
        if (queries == null || queries.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> tokenIdf = TextSearchUtils.buildIdfTable(queries, projectLexicon);
        Map<String, Double> rawWeights = new LinkedHashMap<>();
        double total = 0.0;
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            Set<String> terms = TextSearchUtils.tokenSet(query, projectLexicon);
            double avgIdf = terms.isEmpty()
                    ? 1.0
                    : terms.stream().mapToDouble(term -> tokenIdf.getOrDefault(term, 1.0)).average().orElse(1.0);
            double lengthBonus = Math.min(0.25, Math.max(0, terms.size() - 1) * 0.04);
            double weight = 0.6 + avgIdf + lengthBonus;
            rawWeights.put(query, weight);
            total += weight;
        }
        if (rawWeights.isEmpty()) {
            return Map.of();
        }
        double avg = total / rawWeights.size();
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : rawWeights.entrySet()) {
            normalized.put(entry.getKey(), clamp(entry.getValue() / Math.max(avg, 1e-6), 0.8, 1.6));
        }
        return normalized;
    }

    private ProjectLexicon resolveProjectLexicon(String projectId) {
        return projectLexiconResolver == null ? ProjectLexicon.empty() : projectLexiconResolver.resolve(projectId);
    }

    private Map<String, Object> copyItem(Map<String, Object> item) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : item.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), castStringObjectMap(map));
            } else if (value instanceof List<?> list) {
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private Map<String, Map<String, Integer>> mergeQuotas(List<String> types, Map<String, Map<String, Integer>> custom) {
        Map<String, Map<String, Integer>> defaults = new LinkedHashMap<>();
        defaults.put("fact", quota(3, 8));
        defaults.put("summary", quota(0, 4));
        defaults.put("text_chunk", quota(3, 8));
        defaults.put("character", quota(0, 6));
        defaults.put("world_entity", quota(0, 4));
        defaults.put("world_rule", quota(0, 4));
        defaults.put("style", quota(0, 2));
        defaults.put("memory", quota(0, 4));

        Map<String, Map<String, Integer>> merged = new LinkedHashMap<>();
        for (String type : types) {
            if (defaults.containsKey(type)) {
                merged.put(type, new LinkedHashMap<>(defaults.get(type)));
            }
        }
        if (custom != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : custom.entrySet()) {
                merged.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
                Map<String, Integer> target = merged.get(entry.getKey());
                if (entry.getValue().containsKey("min")) target.put("min", entry.getValue().get("min"));
                if (entry.getValue().containsKey("max")) target.put("max", entry.getValue().get("max"));
            }
        }
        return merged;
    }

    private Map<String, Integer> quota(int min, int max) {
        return new LinkedHashMap<>(Map.of("min", min, "max", max));
    }

    private List<Map<String, Object>> applyTypeQuotas(List<Map<String, Object>> scored,
                                                      Map<String, Map<String, Integer>> quotas,
                                                      int limit) {
        if (limit <= 0 || scored == null || scored.isEmpty()) {
            return List.of();
        }
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (Map<String, Object> item : scored) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(item);
        }
        for (List<Map<String, Object>> items : byType.values()) {
            items.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0.0)).doubleValue()
            ));
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> usedIds = new LinkedHashSet<>();

        for (Map.Entry<String, Map<String, Integer>> entry : quotas.entrySet()) {
            String type = entry.getKey();
            int min = Math.max(entry.getValue().getOrDefault("min", 0), 0);
            List<Map<String, Object>> items = byType.getOrDefault(type, List.of());
            for (int i = 0; i < Math.min(min, items.size()) && selected.size() < limit; i++) {
                Map<String, Object> item = items.get(i);
                String id = String.valueOf(item.getOrDefault("id", ""));
                if (usedIds.add(id)) {
                    selected.add(item);
                    counts.put(type, counts.getOrDefault(type, 0) + 1);
                }
            }
        }

        List<Map<String, Object>> remaining = new ArrayList<>(scored);
        remaining.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        for (Map<String, Object> item : remaining) {
            if (selected.size() >= limit) break;
            String id = String.valueOf(item.getOrDefault("id", ""));
            if (!usedIds.add(id)) continue;
            String type = String.valueOf(item.getOrDefault("type", ""));
            int max = quotas.getOrDefault(type, Map.of()).getOrDefault("max", Integer.MAX_VALUE);
            if (counts.getOrDefault(type, 0) >= max) continue;
            selected.add(item);
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return selected;
    }

    private List<Map<String, Object>> extractTopSources(List<Map<String, Object>> items, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        for (Map<String, Object> item : sorted) {
            if (result.size() >= limit) break;
            if ("memory".equals(String.valueOf(item.getOrDefault("type", "")).trim())) {
                continue;
            }
            String text = String.valueOf(item.getOrDefault("text", "")).trim();
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
            Map<String, Object> sourceSummary = new LinkedHashMap<>();
            for (String key : List.of("chapter", "draft", "path", "paragraph", "field", "fact_id", "card", "introduced_in")) {
                Object value = source.get(key);
                if (value != null) {
                    sourceSummary.put(key, value);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", item.getOrDefault("type", ""));
            entry.put("score", ((Number) item.getOrDefault("score", 0.0)).doubleValue());
            entry.put("snippet", text.length() > 80 ? text.substring(0, 80) : text);
            entry.put("source", sourceSummary);
            String key = entry.toString();
            if (seen.add(key)) {
                result.add(entry);
            }
        }
        return result;
    }

    private Map<String, Object> castStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String stringValue(Map<?, ?> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static final class FusedItemAccumulator {
        private Map<String, Object> bestItem;
        private double bestRawScore;
        private double combinedScore;
        private final Map<String, Double> queryScores = new LinkedHashMap<>();

        private FusedItemAccumulator(Map<String, Object> bestItem) {
            this.bestItem = bestItem;
        }

        private void updateBest(Map<String, Object> item, double score) {
            if (score >= bestRawScore) {
                this.bestRawScore = score;
                this.bestItem = item;
            }
        }
    }

    private record FusionScore(double fusedScore,
                               double bestRaw,
                               double combinedScore,
                               double coverage,
                               Map<String, Double> queryScores) {
        private static FusionScore empty() {
            return new FusionScore(0.0, 0.0, 0.0, 0.0, Map.of());
        }
    }
}
