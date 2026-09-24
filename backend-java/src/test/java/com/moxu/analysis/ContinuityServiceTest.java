package com.moxu.analysis;

import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContinuityServiceTest {
    @TempDir Path directory;
    AnalysisStorage storage;
    DraftStorage drafts;
    CanonStorage canon;
    AnalysisJobService jobs;
    CausalAuditService causal;
    ContinuityService service;
    private <T extends BaseStorage> T configured(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString());
        return value;
    }
    @BeforeEach void setup() throws Exception {
        storage = configured(new AnalysisStorage());
        drafts = configured(new DraftStorage());
        canon = configured(new CanonStorage());
        jobs = mock(AnalysisJobService.class);
        causal = mock(CausalAuditService.class);
        when(causal.status(anyString(), anyString())).thenReturn(Map.of("ready_for_generation", true));
        service = new ContinuityService(storage, drafts, canon, jobs, configured(new MemoryPackStorage()), causal);
        when(jobs.run(eq("book"), any(), eq(false))).thenAnswer(call -> {
            AnalysisJobRequest request = call.getArgument(1);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String chapter : request.getChapters()) {
                Map<String, Object> analysis = new LinkedHashMap<>(candidate());
                analysis.put("_source_hash", AnalysisStorage.hash(text(chapter)));
                analysis.put("_title_hash", AnalysisStorage.hash(""));
                rows.add(Map.of("chapter", chapter, "success", true, "analysis", analysis));
            }
            return Map.of("status", "complete", "results", rows, "job_id", request.getJobId());
        });
        when(jobs.saveForContinuity(eq("book"), anyList(), any())).thenAnswer(call -> {
            List<Map<String, Object>> items = call.getArgument(1);
            for (Map<String, Object> item : items) {
                @SuppressWarnings("unchecked") Map<String, Object> analysis = (Map<String, Object>) item.get("analysis");
                storage.applied("book", item.get("chapter").toString(), Map.of("source_hash", analysis.get("_source_hash"),
                        "title_hash", analysis.get("_title_hash"), "semantic", ContinuityService.semantic(analysis)));
                assertEquals(List.of(), analysis.get("proposals"), "Auto-sync cannot overwrite cards");
            }
            return Map.of("success", true);
        });
    }
    Map<String, Object> candidate() {
        return Map.of("facts", List.of(Map.of("statement", "Alice owns the key", "entity", "Alice")),
                "character_states", List.of(), "timeline_events", List.of(), "summary", Map.of());
    }
    String text(String chapter) { return drafts.getLatestDraft("book", chapter).orElseThrow().getContent(); }
    void write(String chapter, String text) throws Exception {
        drafts.saveCurrentDraft("book", chapter, text, text.length(), null, false);
    }
    void checkpoint(String chapter) throws Exception {
        storage.applied("book", chapter, Map.of("source_hash", AnalysisStorage.hash(text(chapter)),
                "title_hash", AnalysisStorage.hash(""), "semantic", ContinuityService.semantic(candidate())));
    }
    Map<String, Object> sync(String target) throws Exception {
        var plan = service.inspect("book", target);
        return service.sync("book", target, new ContinuityService.Request(plan.get("revision").toString(), null, null, null));
    }

    @Test void unchangedSourcesUseNoModelAndFutureDraftsAreExcluded() throws Exception {
        write("V1C1", "First"); checkpoint("V1C1");
        write("V1C20", "Future unarchived chapter");
        assertEquals("ready", service.inspect("book", "V1C2").get("status"));
        service.requireReady("book", "V1C2");
        verifyNoInteractions(jobs);
    }
    @Test void causalAuditBlocksAllReadyPathsAndCannotBeAcknowledgedAway() throws Exception {
        write("V1C1", "First"); checkpoint("V1C1");
        when(causal.status(anyString(), anyString())).thenReturn(Map.of("ready_for_generation", false, "status", "stale"));
        var plan = service.inspect("book", "V1C2");
        assertEquals("needs_causal", plan.get("status"));
        assertThrows(ResponseStatusException.class, () -> service.requireReady("book", "V1C2"));
        assertThrows(ResponseStatusException.class, () -> service.acknowledge("book", "V1C2", plan.get("revision").toString()));
        assertEquals("needs_causal", sync("V1C2").get("status"));
        when(causal.status(anyString(), anyString())).thenReturn(Map.of("ready_for_generation", true));
        service.requireReady("book", "V1C2");
    }
    @Test void causalCompletionDoesNotErasePendingSemanticReview() throws Exception {
        write("V1C1", "Alice acquired the key.");
        when(causal.status(anyString(), anyString())).thenReturn(Map.of("ready_for_generation", false));
        assertEquals("needs_causal", sync("V1C2").get("status"));
        when(causal.status(anyString(), anyString())).thenReturn(Map.of("ready_for_generation", true));
        var plan = service.inspect("book", "V1C2");
        assertEquals("needs_review", plan.get("status"));
        service.acknowledge("book", "V1C2", plan.get("revision").toString());
        service.requireReady("book", "V1C2");
    }
    @Test void multipleEditsInTwoHundredChaptersOnlyExtractEditedSources() throws Exception {
        for (int i = 1; i <= 199; i++) { write("V1C" + i, "Alice scene " + i); checkpoint("V1C" + i); }
        write("V1C4", "Alice scene 4 with different punctuation.");
        write("V1C80", "Alice scene 80 with different wording.");
        var result = sync("V1C200");
        assertEquals("ready", result.get("status"), "Identical semantic data does not propagate a factual change");
        verify(jobs).run(eq("book"), argThat(r -> r.getChapters().equals(List.of("V1C4", "V1C80"))), eq(false));
        assertEquals(List.of(), result.get("affected_chapters"));
    }
    @Test void semanticChangeRequiresReviewAndFindsUnchangedRelatedChapters() throws Exception {
        write("V1C1", "Alice acquired the key.");
        write("V1C2", "Unrelated landscape."); checkpoint("V1C2");
        write("V1C3", "Alice opens a door."); checkpoint("V1C3");
        var result = sync("V1C4");
        assertEquals("needs_review", result.get("status"));
        assertEquals(List.of("V1C3"), result.get("affected_chapters"));
        assertThrows(ResponseStatusException.class, () -> service.requireReady("book", "V1C4"));
        // Switching the generation target cannot bypass the unresolved review.
        assertEquals("needs_review", service.inspect("book", "V1C5").get("status"));
        service.acknowledge("book", "V1C4", result.get("revision").toString());
        service.requireReady("book", "V1C4");
    }
    @Test void editingAgainInvalidatesAnOldConfirmation() throws Exception {
        write("V1C1", "Alice acquired the key.");
        var result = sync("V1C2");
        write("V1C1", "Alice lost the key.");
        assertThrows(ResponseStatusException.class,
                () -> service.acknowledge("book", "V1C2", result.get("revision").toString()));
        assertEquals("needs_sync", service.inspect("book", "V1C2").get("status"));
    }
    @Test void failedOrBudgetPausedAnalysisNeverAppliesCandidates() throws Exception {
        write("V1C1", "Alice acquired the key.");
        when(jobs.run(eq("book"), any(), eq(false))).thenReturn(Map.of("status", "paused"));
        var plan = sync("V1C2");
        assertEquals("paused", plan.get("status"));
        verify(jobs, never()).saveForContinuity(anyString(), anyList(), any());
        assertThrows(ResponseStatusException.class, () -> service.requireReady("book", "V1C2"));
    }
    @Test void contentChangingDuringExtractionCannotBeApplied() throws Exception {
        write("V1C1", "Alice acquired the key.");
        when(jobs.run(eq("book"), any(), eq(false))).thenAnswer(call -> {
            write("V1C1", "Another edit");
            return Map.of("status", "complete", "results", List.of());
        });
        assertThrows(ResponseStatusException.class, () -> sync("V1C2"));
        verify(jobs, never()).saveForContinuity(anyString(), anyList(), any());
    }
    @Test void clearedSourceBlocksGenerationInsteadOfLeavingGhostFactsUnnoticed() throws Exception {
        write("V1C1", "Alice acquired the key."); checkpoint("V1C1");
        write("V1C1", "");
        assertEquals("blocked", service.inspect("book", "V1C2").get("status"));
        assertThrows(ResponseStatusException.class, () -> service.requireReady("book", "V1C2"));
    }
    @Test void completedArchiveSeedsLegacyCheckpointWithoutModelCalls() throws Exception {
        write("V1C1", "Archived source");
        drafts.saveArchiveStatus("book", "V1C1", Map.of("status", "complete",
                "content_hash", AnalysisStorage.hash(text("V1C1")), "title_hash", AnalysisStorage.hash("")));
        assertEquals("ready", service.inspect("book", "V1C2").get("status"));
        verifyNoInteractions(jobs);
    }
    @Test void confirmedRemovalCleansDerivedDataWithoutModelCallsAndCanResumeGeneration() throws Exception {
        write("V1C1", "Alice acquired the key."); checkpoint("V1C1");
        canon.saveFact("book", com.moxu.model.entity.Fact.builder().id("old").statement("Alice owns key")
                .introducedIn("V1C1").source("V1C1").build());
        write("V1C1", "");
        var plan = service.inspect("book", "V1C2");
        var cleaned = service.cleanRemoved("book", "V1C2", plan.get("revision").toString());
        assertEquals("needs_review", cleaned.get("status"));
        assertTrue(canon.listFacts("book").isEmpty());
        assertEquals("", text("V1C1"));
        service.acknowledge("book", "V1C2", cleaned.get("revision").toString());
        service.requireReady("book", "V1C2");
        verifyNoInteractions(jobs);
    }
    @Test void failedSaveRemainsResumableAndCannotBeAcknowledgedFromAnotherTarget() throws Exception {
        write("V1C1", "Alice acquired the key.");
        when(jobs.saveForContinuity(eq("book"), anyList(), any())).thenAnswer(call -> {
            checkpoint("V1C1");
            return Map.of("success", false);
        });
        var failed = sync("V1C2");
        assertEquals("partial", failed.get("status"));
        assertEquals(List.of("V1C1"), failed.get("changed_chapters"));
        assertEquals("blocked", service.inspect("book", "V1C3").get("status"));
        assertThrows(ResponseStatusException.class,
                () -> service.acknowledge("book", "V1C2", failed.get("revision").toString()));
        when(jobs.saveForContinuity(eq("book"), anyList(), any())).thenReturn(Map.of("success", true));
        var resumed = sync("V1C2");
        assertEquals(failed.get("job_id"), resumed.get("job_id"));
        assertEquals("needs_review", resumed.get("status"));
        service.acknowledge("book", "V1C2", resumed.get("revision").toString());
        service.requireReady("book", "V1C2");
    }
    @Test void titleOnlyEditAndManualCardChangesInvalidateApproval() throws Exception {
        write("V1C1", "Alice scene"); checkpoint("V1C1");
        assertEquals("ready", service.inspect("book", "V1C2").get("status"));
        drafts.saveChapterSummary("book", com.moxu.model.entity.ChapterSummary.builder()
                .chapter("V1C1").title("Renamed").build());
        assertEquals("needs_sync", service.inspect("book", "V1C2").get("status"));
    }
    @Test void entityFreeChangesUseConservativeReviewAndNumericChapterOrder() throws Exception {
        assertTrue(ContinuityService.compare("V1C9", "V1C10") < 0);
        assertTrue(ContinuityService.compare("V1C100", "V2C1") < 0);
        assertEquals(ContinuityService.semantic(Map.of("facts", List.of(Map.of("statement", "x", "evidence", "old")))),
                ContinuityService.semantic(Map.of("facts", List.of(Map.of("statement", "x", "evidence", "new")))));
    }
}
