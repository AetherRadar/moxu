package com.moxu.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.llm.LlmGateway;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static com.moxu.analysis.CausalModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftSemanticReviewServiceTest {
    @TempDir Path directory;
    DraftStorage drafts;
    AnalysisStorage storage;
    LlmGateway gateway;
    CausalExtractor extractor;
    DraftSemanticReviewService service;
    private <T extends BaseStorage> T configured(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString());
        return value;
    }
    @BeforeEach void setup() {
        drafts = configured(new DraftStorage());
        storage = configured(new AnalysisStorage());
        gateway = mock(LlmGateway.class);
        extractor = mock(CausalExtractor.class);
        when(gateway.analysisFingerprint()).thenReturn("model");
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(empty());
        service = new DraftSemanticReviewService(drafts, storage, gateway, extractor);
    }
    Extraction empty() { return new Extraction("complete", List.of(), List.of(), List.of()); }
    void previous(String chapter, String text, Extraction extraction) throws Exception {
        drafts.saveCurrentDraft("book", chapter, text, null, null, false);
        String key = storage.fingerprint(List.of(CausalExtractor.VERSION, "model", chapter, AnalysisStorage.hash(text)));
        storage.causalCache("book", "extraction", key,
                new ObjectMapper().convertValue(extraction, new TypeReference<Map<String, Object>>() {}));
    }
    com.moxu.quality.ContinuityProtection.Report run(String text) throws Exception {
        var review = service.review("book", "V1C3", text);
        return service.run("book", "V1C3", text, review.revision(), 60000);
    }
    Extraction event(String quote, boolean requires, String value) {
        var fact = new Claim("phone", "call:first:status", value, quote, 0.95, false);
        return new Extraction("complete", List.of(), List.of(new Event(quote, "main", quote,
                requires ? List.of(fact) : List.of(), requires ? List.of() : List.of(fact))), List.of());
    }

    @Test void readOnlyReviewDoesNotCallModelAndMarksUncheckedCoverage() throws Exception {
        var report = service.review("book", "V1C3", "Draft");
        assertEquals("unchecked", report.semantic_status());
        assertEquals("semantic_unchecked", report.issues().getFirst().type());
        assertEquals("", report.reference_excerpts());
        verifyNoInteractions(extractor);
    }

    @Test void freshSemanticResultReusesCacheWithoutAnotherPaidCall() throws Exception {
        previous("V1C1", "Earlier", empty());
        var result = run("Draft");
        assertEquals("complete", result.semantic_status());
        assertEquals("local_and_semantic", result.coverage());
        assertTrue(result.issues().isEmpty());
        clearInvocations(extractor);
        assertEquals(result.revision(), run("Draft").revision());
        verifyNoInteractions(extractor);
    }

    @Test void earlierEditsInvalidateReviewEvenWhenNearestPredecessorIsUnchanged() throws Exception {
        previous("V1C1", "Earlier", empty());
        previous("V1C2", "Nearest", empty());
        var before = run("Draft");
        previous("V1C1", "Edited early chapter", empty());
        var after = service.review("book", "V1C3", "Draft");
        assertNotEquals(before.revision(), after.revision());
        assertEquals("unchecked", after.semantic_status());
        assertThrows(ResponseStatusException.class,
                () -> service.run("book", "V1C3", "Draft", before.revision(), 60000));
    }

    @Test void futureSourceIsNeitherReadNorUsedToExplainCurrentDraft() throws Exception {
        previous("V1C1", "Earlier", empty());
        var before = run("Draft");
        Files.createDirectories(directory.resolve("book/drafts/V1C200/final.md"));
        assertEquals(before.revision(), service.review("book", "V1C3", "Draft").revision());
    }

    @Test void missingPrefixCacheRequiresSynchronizationBeforeAnyPaidCall() throws Exception {
        drafts.saveCurrentDraft("book", "V1C1", "Unanalyzed", null, null, false);
        assertThrows(ResponseStatusException.class, () -> run("Draft"));
        verifyNoInteractions(extractor);
    }

    @Test void catchesMissedCallRetoldAsAnsweredWithSourceEvidence() throws Exception {
        previous("V1C1", "Missed call", event("Missed call", false, "missed"));
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(event("Answered call", true, "answered"));
        when(extractor.verify(any(), anyString(), anyString()))
                .thenReturn(new Verdict("conflict", "Call status changed", "Missed call", "Answered call", 0.95));
        var report = run("Answered call");
        assertEquals("semantic_state_mismatch", report.issues().getFirst().type());
        assertTrue(report.reference_excerpts().contains("[V1C1] Missed call"));
        assertTrue(report.issues().getFirst().message().contains("Call status changed"));
    }

    @Test void historicalDraftCanIntroduceAConflictAtAnEarlierNarratedChapter() throws Exception {
        String present = "2001-01-02 Phone exists";
        var needsPhone = new Claim("phone", "alive", "true", present, 0.95, false);
        previous("V1C1", present, new Extraction("complete", List.of(), List.of(new Event("present", "main",
                present, List.of(needsPhone), List.of(), new Narrative("2001-01-02", "2001-01-02", "", ""))), List.of()));
        String historical = "2001-01-01 Phone destroyed";
        var destroyed = new Claim("phone", "alive", "false", historical, 0.95, false);
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(new Extraction("complete", List.of(),
                List.of(new Event("past", "flashback", historical, List.of(), List.of(destroyed),
                        new Narrative("2001-01-01", "2001-01-01", "", ""))), List.of()));
        when(extractor.verify(any(), anyString(), anyString()))
                .thenReturn(new Verdict("conflict", "Historical change conflicts with present", historical, present, 0.95));
        var report = run(historical);
        assertEquals("complete", report.semantic_status());
        assertTrue(report.issues().stream().anyMatch(i -> i.type().equals("semantic_state_mismatch")
                && i.evidence().contains("[V1C1]")));
    }

    @Test void exhaustedBudgetIsPartialAndCannotMasqueradeAsCleanCoverage() throws Exception {
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            AnalysisContext.beforeCall(List.of(Map.of("role", "user", "content", "x".repeat(2000))), 8000);
            return empty();
        });
        var before = service.review("book", "V1C3", "Draft");
        var result = service.run("book", "V1C3", "Draft", before.revision(), 1000);
        assertEquals("partial", result.semantic_status());
        assertEquals("local_rules_only", result.coverage());
        assertFalse(result.issues().isEmpty());
    }

    @Test void providerFailureNeverSilentlyApprovesAndAllowsRetry() throws Exception {
        when(extractor.extract(any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("offline"));
        assertEquals("partial", run("Draft").semantic_status());
        doReturn(empty()).when(extractor).extract(any(), anyInt(), anyInt());
        assertEquals("complete", run("Draft").semantic_status());
    }

    @Test void sourceEditDuringPaidReviewRejectsResult() throws Exception {
        previous("V1C1", "Earlier", empty());
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            previous("V1C1", "Changed during call", empty());
            return empty();
        });
        assertThrows(ResponseStatusException.class, () -> run("Draft"));
        assertEquals("unchecked", service.review("book", "V1C3", "Draft").semantic_status());
    }

    @Test void modelAndDraftChangesInvalidateCachedApproval() throws Exception {
        var before = run("Draft");
        assertNotEquals(before.revision(), service.review("book", "V1C3", "Edited").revision());
        when(gateway.analysisFingerprint()).thenReturn("other-model");
        assertEquals("unchecked", service.review("book", "V1C3", "Draft").semantic_status());
    }

    @Test void oversizedDraftAndUnlimitedBudgetAreRejectedBeforeCalls() throws Exception {
        assertThrows(ResponseStatusException.class, () -> run("x".repeat(16001)));
        var before = service.review("book", "V1C3", "Draft");
        assertThrows(ResponseStatusException.class,
                () -> service.run("book", "V1C3", "Draft", before.revision(), 0));
        verifyNoInteractions(extractor);
    }
}
