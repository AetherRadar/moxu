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
        for (Map<String, Object> fact : maps(updates.get("facts"))) {
            String evidence = Objects.toString(fact.get("evidence"), "").trim();
            String certainty = Objects.toString(fact.get("certainty"), "");
            String reason = "";
            int offset = source == null ? -1 : source.indexOf(evidence);
            if (evidence.length() < 6 || offset < 0) reason = "缺少可定位的正文原句";
            else if (!"explicit".equals(certainty)) reason = "尚未标为正文明确证实";
            else {
                // Inspect the whole sentence, so quoting only the tail cannot erase “像是”.
                int begin = offset;
                int end = offset + evidence.length();
                while (begin > 0 && "。！？\n".indexOf(source.charAt(begin - 1)) < 0) begin--;
                while (end < source.length() && "。！？\n".indexOf(source.charAt(end)) < 0) end++;
                if (UNCERTAIN.matcher(source.substring(begin, end)).find()
                        || UNCERTAIN.matcher(Objects.toString(fact.get("statement"), "")).find()) {
                    reason = "原文含推测或条件表述，需人工核实";
                }
            }
            if (reason.isEmpty()) verified.add(fact);
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
