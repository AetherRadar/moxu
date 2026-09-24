package com.moxu.quality;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative admission gate; candidates remain available for human review. */
public final class CanonEvidenceGuard {
    private CanonEvidenceGuard() {}
    private static final Pattern UNCERTAIN = Pattern.compile("像是|似乎|仿佛|或许|也许|可能|猜测|猜想|怀疑|推测|据说|传闻|如果|假如");

    public static Map<String, Object> apply(Map<String, Object> updates, String source) {
        Map<String, Object> result = new LinkedHashMap<>(updates);
        List<Map<String, Object>> verified = new ArrayList<>();
        List<Map<String, Object>> pending = maps(updates.get("pending_facts"));
        Normalized normalizedSource = normalize(source == null ? "" : source);
        for (Map<String, Object> fact : maps(updates.get("facts"))) {
            String evidence = Objects.toString(fact.get("evidence"), "").trim();
            String certainty = Objects.toString(fact.get("certainty"), "");
            String reason = "";
            int offset = source == null ? -1 : source.indexOf(evidence);
            int evidenceEnd = offset + evidence.length();
            String normalizedEvidence = normalize(evidence).text();
            if (offset < 0 && !normalizedEvidence.isEmpty()) {
                int match = normalizedSource.text().indexOf(normalizedEvidence);
                if (match >= 0) {
                    offset = normalizedSource.offsets()[match];
                    evidenceEnd = normalizedSource.offsets()[match + normalizedEvidence.length() - 1] + 1;
                }
            }
            if (normalizedEvidence.length() < 6 || offset < 0) reason = "缺少可定位的正文原句";
            else if (!"explicit".equals(certainty)) reason = "尚未标为正文明确证实";
            else {
                // Use original offsets; a wrapped line must not hide a sentence's qualifier.
                int begin = offset;
                int end = evidenceEnd;
                while (begin > 0 && "。！？.!?".indexOf(source.charAt(begin - 1)) < 0) begin--;
                while (end < source.length() && "。！？.!?".indexOf(source.charAt(end)) < 0) end++;
                if (UNCERTAIN.matcher(normalize(source.substring(begin, end)).text()).find()
                        || UNCERTAIN.matcher(normalize(Objects.toString(fact.get("statement"), "")).text()).find()) {
                    reason = "原文含推测或条件表述，需人工核实";
                }
            }
            if (reason.isEmpty()) {
                Map<String, Object> located = new LinkedHashMap<>(fact);
                located.put("evidence", source.substring(offset, evidenceEnd));
                verified.add(located);
            }
            else {
                Map<String, Object> candidate = new LinkedHashMap<>(fact);
                candidate.put("review_reason", reason);
                candidate.put("certainty", "unverified");
                pending.add(candidate);
            }
        }
        result.put("facts", verified);
        result.put("pending_facts", pending);
        return result;
    }

    private record Normalized(String text, int[] offsets) {}

    /** Ignore layout only, retaining word boundaries and a map back to the exact source. */
    private static Normalized normalize(String value) {
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                int next = i + 1;
                while (next < value.length() && (Character.isWhitespace(value.charAt(next))
                        || Character.isSpaceChar(value.charAt(next)))) next++;
                if (i > 0 && next < value.length()
                        && wordCharacter(value.charAt(i - 1)) && wordCharacter(value.charAt(next))) {
                    offsets[text.length()] = i;
                    text.append(' ');
                }
                i = next - 1;
            } else {
                offsets[text.length()] = i;
                text.append(ch);
            }
        }
        return new Normalized(text.toString(), offsets);
    }

    private static boolean wordCharacter(char ch) {
        return (Character.isLetterOrDigit(ch) && !Character.isIdeographic(ch)) || ch == '_';
    }

    public static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>();
                map.forEach((key, val) -> item.put(String.valueOf(key), val));
                result.add(item);
            }
        }
        return result;
    }
}
