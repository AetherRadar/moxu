package com.moxu.quality;

import com.moxu.analysis.AnalysisStorage;
import com.moxu.storage.DraftStorage;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Source-grounded local warnings, not a semantic proof of narrative consistency. */
public final class ContinuityProtection {
    private ContinuityProtection() {}
    private static final String NUM = "[0-9零〇一二两三四五六七八九十]{1,4}";
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![\\d:：零〇一二两三四五六七八九十百千])(?:(凌晨|早上|上午|中午|下午|晚上|晚间)\\s*)?"
                    + "(?:([01]?\\d|2[0-3])[:：]([0-5]\\d)(?![\\d:：])|(" + NUM
                    + ")[点时](" + NUM + ")分)");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(学号|编号|房号|房间号)\\s*(?:是|为|：|:)?\\s*([0-9零〇一二两三四五六七八九十]{1,16})(?![0-9零〇一二两三四五六七八九十])\\s*号?");
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "凌晨|清晨|一早|早晨|早上|上午|中午|下午|傍晚|黄昏|深夜|午夜|夜晚|次日|第二天|当天|翌日|[0-9]{4}年");
    private static final Pattern DURATION = Pattern.compile(
            "(?<![0-9零〇一二两三四五六七八九十百千])(" + NUM + ")(秒钟?|分钟|小时)");
    private static final Pattern RETROSPECTIVE = Pattern.compile(
            "昨天|前天|此前|之前|上次|当初|原先|原本|曾经|记得|回想|那通|那次");
    private static final int MAX_ISSUES = 30;
    private static final String VERSION = "continuity-local-v2";

    public record Issue(String type, String severity, String evidence, int offset, String message) {}
    public record Report(String revision, String source_chapter, String source_hash, String draft_hash,
                         String coverage, boolean truncated, List<Issue> issues, String reference_excerpts,
                         String semantic_status) {
        public Report(String revision, String sourceChapter, String sourceHash, String draftHash,
                      String coverage, boolean truncated, List<Issue> issues, String excerpts) {
            this(revision, sourceChapter, sourceHash, draftHash, coverage, truncated, issues, excerpts, "not_requested");
        }
    }

    public static Report review(DraftStorage storage, String project, String chapter, String draft) throws IOException {
        if (draft == null) throw new IllegalArgumentException("Draft content is required");
        var source = storage.readPreviousChapterSource(project, chapter);
        return review(project, chapter, source.map(DraftStorage.ChapterEnding::chapter).orElse(""),
                source.map(DraftStorage.ChapterEnding::text).orElse(""), draft);
    }

    public static Report review(String project, String chapter, String sourceChapter, String source, String draft) {
        String sourceHash = AnalysisStorage.hash(source);
        String draftHash = AnalysisStorage.hash(draft);
        String revision = AnalysisStorage.hash(String.join("\u0000",
                VERSION, project, chapter, sourceChapter, sourceHash, draftHash));
        List<Issue> issues = new ArrayList<>();
        for (var issue : ProseChecks.check(draft)) {
            issues.add(new Issue(Objects.toString(issue.get("type")), "warning",
                    Objects.toString(issue.get("evidence")), ((Number) issue.get("offset")).intValue(),
                    Objects.toString(issue.get("message"))));
        }
        // New fictional events may legitimately introduce values. Never label absence as contradiction.
        if (!sourceChapter.isBlank()) {
            Set<String> clocks = clockValues(source);
            Set<String> seen = new HashSet<>();
            var match = CLOCK.matcher(draft);
            while (match.find()) {
                String value = clockValue(match);
                if (value == null || clocks.contains(value) || !seen.add(value)) continue;
                issues.add(new Issue("unverified_clock", "warning", match.group(), match.start(),
                        "前章正文未出现这一精确时刻；若指向前文事件，请核对时间，若为新情节请确认。"));
            }
            Set<String> ids = identifierValues(source);
            seen.clear();
            match = IDENTIFIER.matcher(draft);
            while (match.find()) {
                String value = identifierValue(match);
                if (value == null || ids.contains(value) || !seen.add(value)) continue;
                issues.add(new Issue("unverified_identifier", "warning", match.group(), match.start(),
                        "前章正文未出现这一编号；请核对是否沿用旧编号，或确认为新设定。"));
            }
            Set<Integer> durations = durationValues(source);
            seen.clear();
            match = DURATION.matcher(draft);
            while (match.find()) {
                int seconds = durationSeconds(match);
                int begin = match.start();
                while (begin > 0 && match.start() - begin < 120 && !boundary(draft.charAt(begin - 1))) begin--;
                if (seconds < 0 || durations.contains(seconds)
                        || !RETROSPECTIVE.matcher(draft.substring(begin, match.start())).find()
                        || !seen.add(String.valueOf(seconds))) continue;
                issues.add(new Issue("unverified_past_duration", "warning", match.group(), match.start(),
                        "回述前文时新增了未在前章找到的精确时长；请对照原文核对，不能把新数值当作旧事实。"));
            }
        }
        issues.sort(Comparator.comparingInt(Issue::offset));
        return new Report(revision, sourceChapter, sourceHash, draftHash, "local_rules_only",
                issues.size() > MAX_ISSUES, List.copyOf(issues.subList(0, Math.min(issues.size(), MAX_ISSUES))),
                issues.isEmpty() ? "" : anchors(source, "", 600));
    }

    /** Diverse verbatim excerpts outside the protected ending, with a separate bounded budget. */
    public static String anchors(String source, String ending, int budget) {
        if (budget <= 0) return "";
        List<String> snippets = new ArrayList<>();
        int used = 0;
        for (Pattern pattern : List.of(TIME_ANCHOR, CLOCK, IDENTIFIER, DURATION)) {
            Matcher match = pattern.matcher(source);
            int added = 0;
            while (match.find() && added < 2) {
                int begin = match.start(), end = match.end();
                while (begin > 0 && match.start() - begin < 60 && !boundary(source.charAt(begin - 1))) begin--;
                while (end < source.length() && end - begin < 180 && !boundary(source.charAt(end))) end++;
                if (end < source.length() && boundary(source.charAt(end))) end++;
                if (begin > 0 && Character.isLowSurrogate(source.charAt(begin))) begin++;
                if (end < source.length() && Character.isLowSurrogate(source.charAt(end))) end--;
                String snippet = source.substring(begin, end).strip();
                if (snippet.isBlank() || ending.contains(snippet)
                        || snippets.stream().anyMatch(old -> old.contains(snippet))) continue;
                if (used + snippet.length() + 3 > budget) continue;
                snippets.add(snippet);
                used += snippet.length() + 3;
                added++;
            }
        }
        return snippets.isEmpty() ? "" : "- " + String.join("\n- ", snippets);
    }

    private static boolean boundary(char ch) { return "。！？\n\r".indexOf(ch) >= 0; }

    private static Set<Integer> durationValues(String source) {
        Set<Integer> result = new HashSet<>();
        var match = DURATION.matcher(source);
        while (match.find()) {
            int seconds = durationSeconds(match);
            if (seconds >= 0) result.add(seconds);
        }
        return result;
    }

    private static int durationSeconds(Matcher match) {
        int value = number(match.group(1));
        if (value < 0) return -1;
        return value * switch (match.group(2)) { case "小时" -> 3600; case "分钟" -> 60; default -> 1; };
    }

    private static Set<String> clockValues(String source) {
        Set<String> result = new HashSet<>();
        var match = CLOCK.matcher(source);
        while (match.find()) {
            String value = clockValue(match);
            if (value != null) result.add(value);
        }
        return result;
    }

    private static String clockValue(Matcher match) {
        int hour = number(match.group(2) == null ? match.group(4) : match.group(2));
        int minute = number(match.group(3) == null ? match.group(5) : match.group(3));
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
        String period = Objects.toString(match.group(1), "");
        if (Set.of("下午", "晚上", "晚间", "中午").contains(period) && hour < 12) hour += 12;
        if (Set.of("凌晨", "早上", "上午").contains(period) && hour == 12) hour = 0;
        return hour + ":" + minute;
    }

    private static Set<String> identifierValues(String source) {
        Set<String> result = new HashSet<>();
        var match = IDENTIFIER.matcher(source);
        while (match.find()) {
            String value = identifierValue(match);
            if (value != null) result.add(value);
        }
        return result;
    }

    private static String identifierValue(Matcher match) {
        String raw = match.group(2);
        if (raw.matches("[0-9]+")) return match.group(1).replace("房间号", "房号") + ":" + raw;
        if (raw.length() > 4) return null;
        int value = number(raw);
        return value < 0 ? null : match.group(1).replace("房间号", "房号") + ":" + value;
    }

    private static int number(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.matches("[0-9]+")) return Integer.parseInt(text);
        String normalized = text.replace('两', '二').replace('〇', '零');
        String digits = "零一二三四五六七八九";
        if (normalized.matches("[零一二三四五六七八九]+")) {
            int result = 0;
            for (char ch : normalized.toCharArray()) result = result * 10 + digits.indexOf(ch);
            return result;
        }
        if (normalized.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) {
            String[] pieces = normalized.split("十", -1);
            return (pieces[0].isEmpty() ? 1 : digits.indexOf(pieces[0])) * 10
                    + (pieces[1].isEmpty() ? 0 : digits.indexOf(pieces[1]));
        }
        return -1;
    }
}
