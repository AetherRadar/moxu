package com.moxu.analysis;

import com.moxu.llm.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.moxu.analysis.CausalModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CausalExtractorTest {
    LlmGateway gateway = mock(LlmGateway.class);
    CausalExtractor extractor = new CausalExtractor(gateway);
    Source source = new Source("V1C1", "Alice home", "hash");
    String valid = """
            {"coverage":"complete","aliases":[],"events":[{"label":"home","frame":"main","quote":"Alice home",
            "requires":[],"effects":[{"entity":"Alice","property":"location","value":"home","quote":"Alice home",
            "confidence":0.9,"inferred":false}]}],"uncertainties":[]}
            """;
    void reply(String text, String reason) {
        when(gateway.chatOnce(anyList(), nullable(String.class), anyDouble(), anyInt()))
                .thenReturn(LlmResponse.builder().content(text).finishReason(reason).build());
    }
    @Test void acceptsLiteralEvidenceAndLocatesUtf16Offsets() {
        reply(valid, "stop");
        assertEquals(1, extractor.extract(source, 0, source.text().length()).events().size());
        assertEquals(6, CausalExtractor.evidence(source, 0, 10, "home").start());
    }
    @Test void rejectsMissingFieldsUnknownFieldsDuplicatesAndInventedEvidence() {
        for (String text : List.of(
                valid.replace("\"inferred\":false", "\"unknown\":false"),
                valid.replace("\"confidence\":0.9,", ""),
                valid.replace("\"inferred\":false", "\"inferred\":null"),
                valid.replace("\"coverage\":\"complete\"", "\"coverage\":\"complete\",\"coverage\":\"partial\""),
                valid.replace("\"quote\":\"Alice home\"", "\"quote\":\"Alice away\""),
                valid + "{}")) {
            reply(text, "stop");
            assertThrows(IllegalStateException.class, () -> extractor.extract(source, 0, 10));
        }
    }
    @Test void refusesTruncatedOrMalformedResponses() {
        reply(valid, "length");
        assertThrows(IllegalStateException.class, () -> extractor.extract(source, 0, 10));
        reply("```json\n" + valid + "\n```", "stop");
        assertThrows(IllegalStateException.class, () -> extractor.extract(source, 0, 10));
    }
    @Test void coversEveryCharacterWithoutSplittingSurrogatePairs() {
        String text = "a".repeat(7999) + "\uD83D\uDE00" + "\nb".repeat(9000);
        int position = 0;
        for (int[] range : CausalExtractor.segments(text)) {
            assertEquals(position, range[0]);
            assertTrue(range[1] > range[0]);
            assertFalse(Character.isHighSurrogate(text.charAt(range[1] - 1)));
            position = range[1];
        }
        assertEquals(text.length(), position);
    }
    @Test void verificationNeedsBothLiteralCitationsForAConflictOrExplanation() {
        var evidence = CausalExtractor.evidence(source, 0, 10, "Alice home");
        var candidate = new Candidate("id", "state_mismatch", "a", "b", "location", "home", "away", evidence, evidence);
        reply("""
                {"verdict":"conflict","explanation":"incompatible","priorQuote":"invented",
                "currentQuote":"Alice home","confidence":0.9}
                """, "stop");
        assertThrows(IllegalStateException.class, () -> extractor.verify(candidate, "Alice away", "Alice home"));
        reply("""
                {"verdict":"uncertain","explanation":"not enough context","priorQuote":"",
                "currentQuote":"","confidence":0.4}
                """, "stop");
        assertEquals("uncertain", extractor.verify(candidate, "Alice away", "Alice home").verdict());
    }

    @Test void rejectsInventedCalendarDatesAndAcceptsExplicitChineseCalendarEvidence() {
        Source dated = new Source("V1C1", "2001年1月2日 Alice home", "hash");
        String narrative = """
                "narrative":{"time":"2001-01-02","timeQuote":"2001年1月2日","perspective":"","scope":""},
                """;
        reply(valid.replace("\"frame\":\"main\",", "\"frame\":\"main\"," + narrative), "stop");
        assertEquals("2001-01-02", extractor.extract(dated, 0, dated.text().length()).events().getFirst().narrative().time());
        reply(valid.replace("\"frame\":\"main\",", "\"frame\":\"main\"," + narrative.replace("2001-01-02", "2001-01-03")), "stop");
        assertThrows(IllegalStateException.class, () -> extractor.extract(dated, 0, dated.text().length()));
    }
}
