package com.moxu.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目级检索词典。
 *
 * <p>它不是全局 userdict，而是“某一个小说项目自己的专名表”：
 * 角色名、别名、地名、组织名、场景标题等都可以归到这里。
 *
 * <p>当前第一阶段主要用于：
 * <ul>
 *     <li>给中文 tokenizer 补充项目专名，避免被切碎；</li>
 *     <li>让索引构建和查询阶段使用同一套项目术语；</li>
 *     <li>为 text_chunk_index 提供稳定的 lexicon fingerprint。</li>
 * </ul>
 */
public final class ProjectLexicon {

    private static final Pattern SEARCHABLE_TERM_PATTERN = Pattern.compile(".*([\\p{IsHan}]|[a-z0-9_]).*");
    private static final ProjectLexicon EMPTY = new ProjectLexicon(List.of(), Set.of(), "empty");

    private final List<String> orderedTerms;
    private final Set<String> terms;
    private final String fingerprint;

    private ProjectLexicon(List<String> orderedTerms, Set<String> terms, String fingerprint) {
        this.orderedTerms = orderedTerms;
        this.terms = terms;
        this.fingerprint = fingerprint;
    }

    public static ProjectLexicon empty() {
        return EMPTY;
    }

    public static ProjectLexicon fromTerms(Collection<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) {
            return EMPTY;
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTerms) {
            String term = normalizeTerm(raw);
            if (!term.isBlank()) {
                normalized.add(term);
            }
        }
        if (normalized.isEmpty()) {
            return EMPTY;
        }

        List<String> ordered = new ArrayList<>(normalized);
        ordered.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));

        Set<String> termSet = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
        List<String> orderedTerms = Collections.unmodifiableList(ordered);
        String fingerprint = fingerprintOf(orderedTerms);
        return new ProjectLexicon(orderedTerms, termSet, fingerprint);
    }

    public List<String> orderedTerms() {
        return orderedTerms;
    }

    public Set<String> terms() {
        return terms;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int size() {
        return terms.size();
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    private static String normalizeTerm(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('\u3000', ' ');
        if (normalized.isBlank() || normalized.length() < 2) {
            return "";
        }
        if (!SEARCHABLE_TERM_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
    }

    private static String fingerprintOf(List<String> orderedTerms) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String term : orderedTerms) {
                digest.update(term.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return Integer.toHexString(orderedTerms.hashCode());
        }
    }
}
