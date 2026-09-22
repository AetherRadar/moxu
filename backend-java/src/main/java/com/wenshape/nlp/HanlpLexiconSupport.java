package com.wenshape.nlp;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * HanLP-based lexical support for Chinese segmentation / POS / NER.
 *
 * <p>Why we keep this tiny wrapper:
 * <ul>
 *   <li>Proposal extraction should stop slicing raw 2-4 char windows directly.</li>
 *   <li>Use token boundaries and PERSON / ORG / LOC hints as the first gate.</li>
 *   <li>Keep the rest of WenShape's domain rules on top of that.</li>
 * </ul>
 */
@Slf4j
public final class HanlpLexiconSupport {

    private static final ThreadLocal<Segment> SEGMENTER = ThreadLocal.withInitial(() ->
            HanLP.newSegment()
                    .enablePartOfSpeechTagging(true)
                    .enableNameRecognize(true)
                    .enableTranslatedNameRecognize(true)
                    .enableJapaneseNameRecognize(true)
                    .enablePlaceRecognize(true)
                    .enableOrganizationRecognize(true)
    );

    private HanlpLexiconSupport() {
    }

    public static List<TokenInfo> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<Term> terms = SEGMENTER.get().seg(text);
            List<TokenInfo> result = new ArrayList<>(terms.size());
            for (Term term : terms) {
                if (term == null || term.word == null || term.word.isBlank()) {
                    continue;
                }
                result.add(new TokenInfo(term.word, term.nature == null ? "" : term.nature.toString()));
            }
            return result;
        } catch (Throwable t) {
            log.warn("HanLP tokenize failed, fallback to empty token list: {}", t.getMessage());
            return List.of();
        }
    }

    public static List<String> extractPersonTokens(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (TokenInfo token : tokenize(text)) {
            if (isPersonNature(token.nature())) {
                result.add(token.word());
            }
        }
        return new ArrayList<>(result);
    }

    public static List<String> extractWorldEntityTokens(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (TokenInfo token : tokenize(text)) {
            if (isWorldEntityNature(token.nature())) {
                result.add(token.word());
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isPersonNature(String nature) {
        if (nature == null || nature.isBlank()) {
            return false;
        }
        return nature.startsWith("nr");
    }

    public static boolean isWorldEntityNature(String nature) {
        if (nature == null || nature.isBlank()) {
            return false;
        }
        return nature.startsWith("ns")
                || nature.startsWith("nt")
                || nature.startsWith("nz")
                || "nx".equals(nature);
    }

    public static boolean isNounishNature(String nature) {
        if (nature == null || nature.isBlank()) {
            return false;
        }
        return nature.startsWith("n")
                || nature.startsWith("nz")
                || nature.startsWith("nr")
                || nature.startsWith("ns")
                || nature.startsWith("nt");
    }

    public record TokenInfo(String word, String nature) {
    }
}
