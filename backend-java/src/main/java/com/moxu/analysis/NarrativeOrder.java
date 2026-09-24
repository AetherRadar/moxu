package com.moxu.analysis;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Only explicit calendar evidence may reorder objective events. Relative time stays uncertain. */
final class NarrativeOrder {
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})(?:-|年)(\\d{1,2})(?:-|月)(\\d{1,2})(?:日)?(?!\\d)");
    private NarrativeOrder() {}

    static boolean hasExplicitDate(String time, String quote) {
        try {
            LocalDate expected = LocalDate.parse(time);
            var matcher = DATE.matcher(quote);
            while (matcher.find()) {
                LocalDate actual = LocalDate.of(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
                if (actual.equals(expected)) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }
}
