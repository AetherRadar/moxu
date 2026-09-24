package com.moxu.analysis;

import java.util.*;

/** Extractive preview only. Never substitutes for the source used to admit canon facts. */
public final class ChapterDigest {
    private ChapterDigest() {}
    public static String extract(String content, int maxChars) {
        if (maxChars < 64) throw new IllegalArgumentException("Digest budget is too small");
        if (content.length() <= maxChars) return content;
        String[] paragraphs = content.split("(?<=[。！？.!?])\\s*|\\R+");
        if (paragraphs.length < 12) {
            List<String> windows = new ArrayList<>();
            int length = maxChars / 12 - 1;
            for (int i = 0; i < 12; i++) {
                int start = (int) ((long) i * (content.length() - length) / 11);
                windows.add(content.substring(start, start + length));
            }
            return String.join("\n", windows);
        }
        TreeMap<Integer, String> selected = new TreeMap<>();
        int used = 0;
        // Sample the whole chapter, including the ending, without inventing transitions.
        for (int i = 0; i < 12 && paragraphs.length > 0; i++) {
            int index = (int) ((long) i * (paragraphs.length - 1) / 11);
            if (selected.containsKey(index)) continue;
            String sentence = paragraphs[index].strip();
            int allowance = Math.min(maxChars / 12, maxChars - used - 1);
            if (allowance < 1) break;
            if (sentence.length() > allowance) sentence = sentence.substring(0, allowance);
            selected.put(index, sentence);
            used += sentence.length() + 1;
        }
        return String.join("\n", selected.values());
    }
}
