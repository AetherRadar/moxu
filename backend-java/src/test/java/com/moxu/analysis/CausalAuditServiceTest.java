package com.moxu.analysis;

import com.moxu.llm.*;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;
import static com.moxu.analysis.CausalModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CausalAuditServiceTest {
    @TempDir Path directory;
    AnalysisStorage storage;
    DraftStorage drafts;
    LlmGateway gateway;
    CausalExtractor extractor;
    CausalAuditService service;
    private <T extends BaseStorage> T configured(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString());
        return value;
    }
    @BeforeEach void setup() {
        storage = configured(new AnalysisStorage());
        drafts = configured(new DraftStorage());
        gateway = mock(LlmGateway.class);
        when(gateway.analysisFingerprint()).thenReturn("model-a");
        extractor = mock(CausalExtractor.class);
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(empty());
        service = new CausalAuditService(storage, drafts, gateway, extractor);
    }
    Extraction empty() { return new Extraction("complete", List.of(), List.of(), List.of()); }
    void write(String chapter, String text) throws Exception {
        drafts.saveCurrentDraft("book", chapter, text, text.length(), null, false);
    }
    Map<String, Object> run() throws Exception { return run(200000); }
    Map<String, Object> run(long budget) throws Exception {
        String revision = service.status("book", "V1C200").get("revision").toString();
        return service.run("book", "V1C200", new CausalAuditService.Request(revision, budget, 30));
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> findings(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("findings");
    }
    void suspicious(boolean inferred) throws Exception {
        write("V1C1", "Alice died");
        write("V1C2", "Alice walks");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            Source s = call.getArgument(0);
            if (s == null || !Set.of("V1C1", "V1C2").contains(s.chapter())) return empty();
            Claim claim = new Claim("Alice", "alive", s.chapter().equals("V1C1") ? "false" : "true",
                    s.text(), 0.95, inferred && s.chapter().equals("V1C2"));
            return new Extraction("complete", List.of(), List.of(new Event(s.text(), "main", s.text(),
                    s.chapter().equals("V1C1") ? List.of() : List.of(claim),
                    s.chapter().equals("V1C1") ? List.of(claim) : List.of())), List.of());
        });
        when(extractor.verify(any(), anyString(), anyString())).thenReturn(
                new Verdict("conflict", "incompatible evidence", "Alice died", "Alice walks", 0.95));
    }
    @Test void emptyBookNeedsNoModelConsent() throws Exception {
        assertEquals(true, service.status("book", "V1C1").get("ready_for_generation"));
        verifyNoInteractions(extractor);
    }
    @Test void completedReportIsSharedAcrossGenerationTargetsWithZeroCalls() throws Exception {
        write("V1C1", "One");
        write("V1C250", "Future chapter");
        var report = run();
        assertEquals("complete", report.get("status"));
        assertEquals(2, report.get("total_chapters"));
        assertEquals(true, report.get("ready_for_generation"));
        clearInvocations(extractor);
        assertEquals(report.get("job_id"), service.run("book", "V1C201",
                new CausalAuditService.Request(report.get("revision").toString(), null, null)).get("job_id"));
        verifyNoInteractions(extractor);
    }
    @Test void editedChapterReextractsOnlyItsSegmentsAndRetainsOtherCache() throws Exception {
        for (int i = 1; i <= 5; i++) write("V1C" + i, "Scene " + i);
        var old = run();
        clearInvocations(extractor);
        write("V1C2", "Edited scene");
        assertEquals("stale", service.status("book", "V1C200").get("status"));
        assertThrows(ResponseStatusException.class, () -> service.run("book", "V1C200",
                new CausalAuditService.Request(old.get("revision").toString(), null, null)));
        var next = run();
        assertEquals(4, next.get("cached_segments"));
        verify(extractor).extract(argThat(s -> s.chapter().equals("V1C2")), anyInt(), anyInt());
        verifyNoMoreInteractions(extractor);
    }

    @Test void unchangedSegmentContentReusesExtractionAcrossAFullChapterHashChange() throws Exception {
        String first = "a".repeat(7999) + "\n";
        write("V1C1", first + "Unchanged tail");
        run();
        clearInvocations(extractor);
        write("V1C1", "b".repeat(7999) + "\nUnchanged tail");
        var report = run();
        assertEquals("complete", report.get("status"));
        assertEquals(1, report.get("cached_segments"));
        verify(extractor).extract(any(), eq(0), eq(8000));
        verifyNoMoreInteractions(extractor);
    }
    @Test void providerFailureRemainsPartialAndResumesFromCachedSegmentsAfterRestart() throws Exception {
        write("V1C1", "One"); write("V1C2", "Two");
        when(extractor.extract(argThat(s -> s != null && s.chapter().equals("V1C2")), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("bad provider response"));
        var partial = run();
        assertEquals("partial", partial.get("status"));
        assertEquals(false, partial.get("ready_for_generation"));
        reset(extractor);
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(empty());
        service = new CausalAuditService(storage, drafts, gateway, extractor);
        var resumed = run();
        assertEquals("complete", resumed.get("status"));
        assertEquals(partial.get("job_id"), resumed.get("job_id"));
        assertEquals(1, resumed.get("cached_segments"));
        verify(extractor).extract(argThat(s -> s.chapter().equals("V1C2")), anyInt(), anyInt());
    }
    @Test void tokenBudgetUsesDurableLedgerAndCanResumeWithHigherCumulativeLimit() throws Exception {
        write("V1C1", "One"); write("V1C2", "Two");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            var ticket = AnalysisContext.beforeCall(List.of(), 40);
            ticket.finish("fake", LlmResponse.builder().totalTokens(50).build(), false);
            return empty();
        });
        var paused = run(100);
        assertEquals("paused", paused.get("status"));
        assertEquals("token_budget", paused.get("pause_reason"));
        assertEquals(50L, storage.spent("book", paused.get("job_id").toString()));
        var resumed = run(300);
        assertEquals("complete", resumed.get("status"));
        assertEquals(1, resumed.get("cached_segments"));
        assertEquals(100L, storage.spent("book", resumed.get("job_id").toString()));
    }
    @Test void cancellingBookTaskFromAnotherTargetStopsFutureCallsAndCanResume() throws Exception {
        write("V1C1", "One"); write("V1C2", "Two");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            assertEquals("running", service.status("book", "V1C201").get("status"));
            assertThrows(ResponseStatusException.class, () -> service.run("book", "V1C201",
                    new CausalAuditService.Request("anything", null, null)));
            service.cancel("book", "V1C201");
            return empty();
        });
        var paused = run();
        assertEquals("paused", paused.get("status"));
        assertEquals("cancelled", paused.get("pause_reason"));
        reset(extractor);
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(empty());
        assertEquals("complete", run().get("status"));
    }
    @Test void manuscriptEditDuringAuditCannotProduceAFreshCompleteReport() throws Exception {
        write("V1C1", "One");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            write("V1C1", "Changed during extraction");
            return empty();
        });
        var result = run();
        assertEquals("stale", result.get("status"));
        assertEquals(false, result.get("ready_for_generation"));
        assertThrows(ResponseStatusException.class, () -> service.graph("book", "V1C200"));
    }
    @Test void explicitDecisionsUnblockButEditsAndModelChangesInvalidateApproval() throws Exception {
        suspicious(false);
        var report = run();
        var finding = findings(report).getFirst();
        assertEquals("supported_conflict", finding.get("classification"));
        assertEquals(false, report.get("ready_for_generation"));
        var bad = new CausalAuditService.Decision(report.get("revision").toString(), finding.get("id").toString(), "false_positive", "");
        assertThrows(ResponseStatusException.class, () -> service.decide("book", "V1C200", bad));
        var decision = new CausalAuditService.Decision(bad.revision(), bad.finding_id(), "false_positive", "Supported resurrection");
        assertEquals(true, service.decide("book", "V1C200", decision).get("ready_for_generation"));
        assertEquals(true, service.status("book", "V1C201").get("ready_for_generation"));
        assertEquals(false, service.decide("book", "V1C201",
                new CausalAuditService.Decision(bad.revision(), bad.finding_id(), "reopen", "")).get("ready_for_generation"));
        service.decide("book", "V1C200", decision);
        when(gateway.analysisFingerprint()).thenReturn("model-b");
        assertEquals("stale", service.status("book", "V1C200").get("status"));
        assertThrows(ResponseStatusException.class, () -> service.decide("book", "V1C200", decision));
        when(gateway.analysisFingerprint()).thenReturn("model-a");
        write("V1C1", "Alice disappeared");
        assertThrows(ResponseStatusException.class, () -> service.decide("book", "V1C200", decision));
    }
    @Test void inferredDependenciesCannotBeUpgradedToSupportedConflicts() throws Exception {
        suspicious(true);
        assertEquals("insufficient_evidence", findings(run()).getFirst().get("classification"));
    }
    @Test void explainedFindingsNeedNoManualDecisionButCoverageGapsDo() throws Exception {
        suspicious(false);
        when(extractor.verify(any(), anyString(), anyString())).thenReturn(
                new Verdict("explained", "Resurrection", "Alice died", "Alice walks", 0.95));
        assertEquals(true, run().get("ready_for_generation"));
        write("V1C3", "Uncertain scene");
        doReturn(new Extraction("partial", List.of(), List.of(), List.of("ambiguous narrator")))
                .when(extractor).extract(argThat(s -> s != null && s.chapter().equals("V1C3")), anyInt(), anyInt());
        var result = run();
        assertEquals("complete", result.get("status"));
        assertEquals(false, result.get("ready_for_generation"));
        assertEquals(1L, result.get("unresolved"));
    }
    @Test void unrelatedEditsReusePairVerificationButBridgeEditsInvalidateIt() throws Exception {
        suspicious(false);
        write("V1C3", "Unrelated");
        run();
        clearInvocations(extractor);
        write("V1C3", "Still unrelated");
        run();
        verify(extractor, never()).verify(any(), anyString(), anyString());
        write("V1C1", "Alice died yesterday");
        run();
        verify(extractor).verify(any(), anyString(), anyString());
    }
    @Test void persistedRunningTaskWithoutWorkerIsPausedNotReady() throws Exception {
        write("V1C1", "One");
        var report = new LinkedHashMap<>(run());
        report.put("status", "running");
        storage.causalReport("book", "V0C0", report);
        assertEquals("paused", service.status("book", "V1C200").get("status"));
        assertEquals(false, service.status("book", "V1C200").get("ready_for_generation"));
    }
    @Test void unreadableManuscriptCannotBeMistakenForAnEmptyBook() throws Exception {
        java.nio.file.Files.createDirectories(directory.resolve("book/drafts/V1C1/final.md"));
        assertThrows(java.io.IOException.class, () -> service.status("book", "V1C200"));
        verifyNoInteractions(extractor);
    }
    @Test void ambiguousCanonicalChapterDirectoriesFailClosed() throws Exception {
        write("V1C1", "One");
        java.nio.file.Files.createDirectories(directory.resolve("book/drafts/V01C01"));
        assertThrows(java.io.IOException.class, () -> service.status("book", "V1C200"));
    }
    @Test void modelSwitchDuringCallCannotPoisonTheOldModelsExtractionCache() throws Exception {
        write("V1C1", "One");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            when(gateway.analysisFingerprint()).thenReturn("model-b");
            return empty();
        });
        assertEquals("stale", run().get("status"));
        when(gateway.analysisFingerprint()).thenReturn("model-a");
        reset(extractor);
        when(extractor.extract(any(), anyInt(), anyInt())).thenReturn(empty());
        var report = run();
        assertEquals("complete", report.get("status"));
        assertEquals(0, report.get("cached_segments"));
        verify(extractor).extract(any(), anyInt(), anyInt());
    }
    @Test void staleSourcesStillExposeTheActiveJobForCancellation() throws Exception {
        write("V1C1", "One");
        when(extractor.extract(any(), anyInt(), anyInt())).thenAnswer(call -> {
            write("V1C1", "Changed");
            var status = service.status("book", "V1C201");
            assertEquals("stale", status.get("status"));
            assertEquals(true, status.get("active"));
            service.cancel("book", "V1C201");
            return empty();
        });
        var report = run();
        assertEquals("stale", report.get("status"));
        assertEquals(false, report.get("active"));
    }
    @Test void resumingVerificationPreservesTheOriginalImpactBaseline() throws Exception {
        suspicious(false);
        when(extractor.verify(any(), anyString(), anyString())).thenThrow(new AnalysisContext.Paused("cancelled"));
        var paused = run();
        assertEquals("paused", paused.get("status"));
        assertEquals(2, paused.get("impacted_events"));
        doReturn(new Verdict("uncertain", "uncertain", "", "", 0.4))
                .when(extractor).verify(any(), anyString(), anyString());
        var resumed = run();
        assertEquals("complete", resumed.get("status"));
        assertEquals(2, resumed.get("impacted_events"));
    }
}
