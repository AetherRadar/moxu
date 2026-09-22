package com.moxu.proposal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proposal 后处理收口器。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>统一不同提案来源的最终出口</li>
 *   <li>做最末端的规范化、去重、排序与限量</li>
 *   <li>保证前端看到的 proposals 行为一致</li>
 * </ul>
 */
public final class ProposalPostProcessor {

    private ProposalPostProcessor() {
    }

    public static List<Map<String, Object>> finalizeProposals(List<Map<String, Object>> proposals, int maxProposals) {
        if (proposals == null || proposals.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(1, maxProposals);
        LinkedHashMap<String, Map<String, Object>> deduped = new LinkedHashMap<>();

        for (Map<String, Object> raw : proposals) {
            Map<String, Object> normalized = normalizeProposal(raw);
            if (normalized == null) {
                continue;
            }

            String key = proposalKey(normalized);
            Map<String, Object> existing = deduped.get(key);
            if (existing == null) {
                deduped.put(key, normalized);
                continue;
            }

            if (proposalConfidence(normalized) > proposalConfidence(existing)) {
                mergeMissingFields(normalized, existing);
                deduped.put(key, normalized);
            } else {
                mergeMissingFields(existing, normalized);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(deduped.values());
        result.sort(Comparator.comparingDouble(ProposalPostProcessor::proposalConfidence).reversed());
        if (result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    private static Map<String, Object> normalizeProposal(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String name = trimValue(raw.get("name"));
        String type = normalizeType(raw.get("type"));
        if (name.isBlank() || type.isBlank()) {
            return null;
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(raw);
        normalized.put("name", name);
        normalized.put("type", type);
        normalized.put("description", trimValue(raw.get("description")));
        normalized.put("rationale", trimValue(raw.get("rationale")));
        normalized.put("source_text", trimValue(raw.get("source_text")));
        normalized.put("confidence_explanation", trimValue(raw.get("confidence_explanation")));
        normalized.put("source_kind", trimValue(raw.get("source_kind")));
        normalized.put("evidence_kinds", normalizeStringList(raw.get("evidence_kinds")));
        return normalized;
    }

    private static void mergeMissingFields(Map<String, Object> target, Map<String, Object> fallback) {
        fillIfBlank(target, fallback, "description");
        fillIfBlank(target, fallback, "rationale");
        fillIfBlank(target, fallback, "source_text");
        fillIfBlank(target, fallback, "confidence_explanation");
        fillIfBlank(target, fallback, "source_kind");
        if (!(target.get("aliases") instanceof List<?>) && fallback.get("aliases") instanceof List<?>) {
            target.put("aliases", fallback.get("aliases"));
        }
        if (!(target.get("stars") instanceof Number) && fallback.get("stars") instanceof Number) {
            target.put("stars", fallback.get("stars"));
        }
        target.put("evidence_kinds", mergeStringLists(target.get("evidence_kinds"), fallback.get("evidence_kinds")));
    }

    private static void fillIfBlank(Map<String, Object> target, Map<String, Object> fallback, String key) {
        String current = trimValue(target.get(key));
        if (!current.isBlank()) {
            return;
        }
        String replacement = trimValue(fallback.get(key));
        if (!replacement.isBlank()) {
            target.put(key, replacement);
        }
    }

    private static String proposalKey(Map<String, Object> proposal) {
        String type = trimValue(proposal.get("type")).toLowerCase(Locale.ROOT);
        String name = normalizeName(trimValue(proposal.get("name")));
        return type + "::" + name;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(Object value) {
        String raw = trimValue(value);
        if (raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "character" -> "Character";
            case "world" -> "World";
            default -> raw;
        };
    }

    private static String trimValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> normalizeStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            String text = trimValue(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return new ArrayList<>(result);
    }

    private static List<String> mergeStringLists(Object primary, Object secondary) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(normalizeStringList(primary));
        merged.addAll(normalizeStringList(secondary));
        return new ArrayList<>(merged);
    }

    private static double proposalConfidence(Map<String, Object> proposal) {
        Object value = proposal.get("confidence");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.0d;
        }
    }
}
