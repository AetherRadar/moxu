package com.moxu.quality;

import java.util.*;
import java.util.regex.Pattern;

/** Narrow, explainable checks; these do not certify overall narrative consistency. */
public final class ProseChecks {
    private ProseChecks() {}
    private static final Pattern MISSING_PAGES = Pattern.compile(
            "(?:编号|页码)\\s*(\\d{1,8})\\s*(?:到|至|—|-)\\s*(\\d{1,8})\\s*之间\\s*(?:缺了|缺少|缺|少了)\\s*([零一二两三四五六七八九十百\\d]+)\\s*页");

    public static List<Map<String, Object>> check(String content) {
        List<Map<String, Object>> issues = new ArrayList<>();
        var matcher = MISSING_PAGES.matcher(content == null ? "" : content);
        while (matcher.find()) {
            int from = Integer.parseInt(matcher.group(1));
            int to = Integer.parseInt(matcher.group(2));
            int claimed = number(matcher.group(3));
            if (to <= from || claimed < 0 || claimed == to - from - 1) continue;
            issues.add(Map.of("type", "page_count_mismatch", "severity", "medium",
                    "evidence", matcher.group(), "offset", matcher.start(),
                    "message", "若页码连续，" + from + " 到 " + to + " 之间应有 " + (to - from - 1)
                            + " 页，正文写为 " + claimed + " 页；请核对页码或说明跳号规则。"));
        }
        return issues;
    }

    private static int number(String value) {
        if (value.matches("\\d+")) {
            try { return Integer.parseInt(value); } catch (NumberFormatException e) { return -1; }
        }
        String digits = "零一二三四五六七八九";
        value = value.replace('两', '二');
        if (value.length() == 1 && digits.indexOf(value) >= 0) return digits.indexOf(value);
        if (value.equals("十")) return 10;
        if (value.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) {
            String[] parts = value.split("十", -1);
            return (parts[0].isEmpty() ? 1 : digits.indexOf(parts[0])) * 10
                    + (parts[1].isEmpty() ? 0 : digits.indexOf(parts[1]));
        }
        return -1;
    }
}
