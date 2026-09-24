package com.moxu.analysis;

import com.moxu.agent.ArchivistAgent;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.orchestrator.*;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisJobServiceTest {
    @TempDir Path directory;
    AnalysisStorage storage;
    DraftStorage drafts;
    MemoryPackStorage memory;
    Orchestrator orchestrator;
    LlmGateway gateway;
    ArchivistAgent archivist;
    AnalysisJobService service;

    private <T extends BaseStorage> T configured(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString());
        return value;
    }
    @BeforeEach void setup() throws Exception {
        storage = configured(new AnalysisStorage());
        drafts = configured(new DraftStorage());
        memory = configured(new MemoryPackStorage());
        orchestrator = mock(Orchestrator.class);
        gateway = mock(LlmGateway.class);
        archivist = mock(ArchivistAgent.class);
        when(gateway.analysisFingerprint()).thenReturn("model-a");
        var pool = mock(OrchestratorPool.class);
        when(pool.getOrchestrator("book")).thenReturn(orchestrator);
        when(orchestrator.analyzeChapter(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var ticket = AnalysisContext.beforeCall(List.of(Map.of("content", invocation.getArgument(2))), 10);
            ticket.finish("fixture", LlmResponse.builder().promptTokens(30).completionTokens(10).totalTokens(40).build(), false);
            return Map.of("success", true, "analysis", Map.of("summary", Map.of("brief_summary", "An event occurred"),
                    "facts", List.of(), "timeline_events", List.of(), "character_states", List.of()));
        });
        when(orchestrator.saveAnalysis(anyString(), anyString(), anyMap(), anyBoolean(), eq(false)))
                .thenReturn(Map.of("success", true, "stats", Map.of()));
        when(archivist.generateVolumeSummary(anyString(), anyString(), anyList())).thenAnswer(invocation ->
                Map.of("generation_status", "complete", "volume_id", invocation.getArgument(1),
                        "brief_summary", "A reviewed synopsis"));
        service = new AnalysisJobService(storage, drafts, memory, pool, gateway, archivist, new AnalysisProperties());
        prose("V1C1", "First chapter source.");
        prose("V1C2", "Second chapter source.");
    }
    private void prose(String chapter, String content) throws Exception {
        drafts.saveCurrentDraft("book", chapter, content, content.length(), null, false);
    }
    private AnalysisJobRequest request(String... chapters) {
        var request = new AnalysisJobRequest();
        request.setChapters(List.of(chapters));
        return request;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> job) { return (List<Map<String, Object>>) job.get("results"); }

    @Test void repeatUsesDurableCandidatesWithoutModelCallsAndNeverWritesCanonInPreview() throws Exception {
        var first = service.run("book", request("V1C1", "V1C2"), false);
        var second = service.run("book", request("V1C1", "V1C2"), false);
        assertEquals("complete", first.get("status"));
        assertEquals(2L, second.get("skipped_count"));
        verify(orchestrator, times(2)).analyzeChapter(anyString(), anyString(), anyString(), anyString());
        verify(orchestrator, never()).saveAnalysis(anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean());
        assertTrue(storage.entry("book", "V1C1").containsKey("title_hash"));
        assertEquals(0L, ((Map<?, ?>) second.get("metrics")).get("budget_tokens"));
        assertEquals(80, storage.spent("book", first.get("job_id").toString()));
    }

    @Test void sourceTitleModelAndCardChangesInvalidateTheCache() throws Exception {
        service.run("book", request("V1C1"), false);
        prose("V1C1", "Changed source.");
        assertEquals(0L, service.run("book", request("V1C1"), false).get("skipped_count"));
        drafts.saveChapterSummary("book", ChapterSummary.builder().chapter("V1C1").title("New title").build());
        assertEquals(0L, service.run("book", request("V1C1"), false).get("skipped_count"));
        when(gateway.analysisFingerprint()).thenReturn("model-b");
        assertEquals(0L, service.run("book", request("V1C1"), false).get("skipped_count"));
        Path card = directory.resolve("book/cards/fixture.yaml");
        Files.createDirectories(card.getParent());
        Files.writeString(card, "name: fixture");
        assertEquals(0L, service.run("book", request("V1C1"), false).get("skipped_count"));
        verify(orchestrator, times(5)).analyzeChapter(anyString(), anyString(), anyString(), anyString());
    }

    @Test void oldPromptCandidateIsRebuiltOnlyForRequestedChapters() throws Exception {
        service.run("book", request("V1C1", "V1C2"), false);
        var entry = new LinkedHashMap<>(storage.entry("book", "V1C1"));
        entry.put("fingerprint", storage.fingerprint(Map.of(
                "source", AnalysisStorage.hash("First chapter source."), "title", "",
                "model", "model-a", "language", "zh", "prompt", "analysis-v1",
                "combined", false, "compact", true, "output_tokens", 4000, "fallback", true,
                "cards", storage.cardsFingerprint("book"))));
        storage.entry("book", "V1C1", entry);
        assertEquals(0L, service.run("book", request("V1C1"), false).get("skipped_count"));
        assertEquals(1L, service.run("book", request("V1C1"), false).get("skipped_count"));
        verify(orchestrator, times(2)).analyzeChapter(eq("book"), eq("V1C1"), anyString(), anyString());
        verify(orchestrator, times(1)).analyzeChapter(eq("book"), eq("V1C2"), anyString(), anyString());
    }

    @Test void budgetStopsBeforeTheCallAndResumesWithoutRepeatingCompletedChapter() throws Exception {
        var request = request("V1C1", "V1C2");
        request.setMaxTokens(85L);
        var first = service.run("book", request, false);
        assertEquals("paused", first.get("status"));
        assertEquals("token_budget", first.get("pause_reason"));
        assertEquals(List.of("V1C2"), first.get("remaining_chapters"));
        request.setJobId(first.get("job_id").toString());
        request.setMaxTokens(1000L);
        var resumed = service.run("book", request, false);
        assertEquals("complete", resumed.get("status"));
        assertEquals(1L, resumed.get("skipped_count"));
        verify(orchestrator, times(3)).analyzeChapter(anyString(), anyString(), anyString(), anyString());
        assertEquals(80L, ((Map<?, ?>) resumed.get("metrics")).get("budget_tokens"));
    }

    @Test void failureDoesNotCacheAndRetryOnlyRepeatsFailedChapter() throws Exception {
        when(orchestrator.analyzeChapter(eq("book"), eq("V1C2"), anyString(), anyString()))
                .thenReturn(Map.of("success", false)).thenReturn(Map.of("success", true, "analysis", Map.of("summary", Map.of())));
        var request = request("V1C1", "V1C2");
        var failed = service.run("book", request, false);
        assertEquals("partial", failed.get("status"));
        assertTrue(storage.entry("book", "V1C2").isEmpty());
        request.setJobId(failed.get("job_id").toString());
        assertEquals("complete", service.run("book", request, false).get("status"));
        verify(orchestrator, times(1)).analyzeChapter(eq("book"), eq("V1C1"), anyString(), anyString());
    }

    @Test void resumeInheritsOmittedSettingsAndBudgets() throws Exception {
        var initial = request("V1C1");
        initial.setCombined(true);
        initial.setMaxTokens(1L);
        initial.setMaxMinutes(7);
        initial.setMaxCallsPerChapter(3);
        var paused = service.run("book", initial, false);
        var resume = new AnalysisJobRequest();
        resume.setJobId(paused.get("job_id").toString());
        var again = service.run("book", resume, false);
        assertEquals("paused", again.get("status"));
        assertEquals(true, again.get("combined"));
        assertEquals(1L, again.get("max_tokens"));
        assertEquals(7, again.get("max_minutes"));
        assertEquals(3, again.get("max_calls_per_chapter"));
    }

    @Test void batchSaveRejectsAnyStaleCandidateBeforeSavingOtherChapters() throws Exception {
        var preview = service.run("book", request("V1C1", "V1C2"), false);
        prose("V1C2", "Changed after preview");
        assertThrows(ResponseStatusException.class, () -> service.saveReviewed("book", rows(preview), true));
        verify(orchestrator, never()).saveAnalysis(anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean());
    }

    @Test void resumedJobCannotChangeScopeOrSwitchPreviewToOverwrite() throws Exception {
        var request = request("V1C1");
        request.setJobId(service.run("book", request, false).get("job_id").toString());
        assertThrows(ResponseStatusException.class, () -> service.run("book", request, true));
        request.setChapters(List.of("V1C2"));
        assertThrows(ResponseStatusException.class, () -> service.run("book", request, false));
    }

    @Test void editedSourceRejectsOldCandidateBeforeAnySave() throws Exception {
        var result = service.run("book", request("V1C1"), false);
        @SuppressWarnings("unchecked") var candidate = (Map<String, Object>) rows(result).getFirst().get("analysis");
        prose("V1C1", "New source after the preview");
        assertThrows(ResponseStatusException.class,
                () -> service.saveCandidate("book", "V1C1", candidate, true, orchestrator));
        verify(orchestrator, never()).saveAnalysis(anyString(), anyString(), anyMap(), anyBoolean(), anyBoolean());
    }

    @Test void restartedProcessReportsRunningJobAsInterrupted() throws Exception {
        String id = UUID.randomUUID().toString();
        storage.job("book", id, Map.of("job_id", id, "status", "running"));
        assertEquals("interrupted", service.status("book", id).get("pause_reason"));
    }

    @Test void volumeReductionIsSkippedUnlessItsInputsChange() throws Exception {
        drafts.saveChapterSummary("book", ChapterSummary.builder().chapter("V1C1").volumeId("V1")
                .title("Title").briefSummary("Summary").wordCount(20).build());
        service.refreshVolume("book", "V1");
        service.refreshVolume("book", "V1");
        verify(archivist, times(1)).generateVolumeSummary(anyString(), anyString(), anyList());
        when(gateway.analysisFingerprint()).thenReturn("changed-model");
        service.refreshVolume("book", "V1");
        verify(archivist, times(2)).generateVolumeSummary(anyString(), anyString(), anyList());
    }

    @RepeatedTest(5) void mapReduceCachesUnchangedGroupsAndLeavesCanonAlone() throws Exception {
        var request = request("V1C1", "V1C2");
        request.setBookSummary(true);
        var first = service.run("book", request, false);
        assertEquals("complete", first.get("status"), first::toString);
        var second = service.run("book", request, false);
        assertEquals("complete", second.get("status"), second::toString);
        verify(archivist, times(1)).generateVolumeSummary(anyString(), anyString(), anyList());
        assertEquals(true, storage.bookSummary("book").get("requires_review"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {10, 100})
    void controlledBookSizesReuseEveryUnchangedChapter(int count) throws Exception {
        List<String> chapters = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String chapter = "V1C" + i;
            chapters.add(chapter);
            prose(chapter, "Synthetic scene " + i + ". No private manuscript is used.");
        }
        var request = new AnalysisJobRequest();
        request.setChapters(chapters);
        var first = service.run("book", request, false);
        var cached = service.run("book", request, false);
        assertEquals("complete", first.get("status"), first::toString);
        assertEquals("complete", cached.get("status"), cached::toString);
        assertEquals((long) count, cached.get("skipped_count"));
        assertEquals(0L, ((Map<?, ?>) cached.get("metrics")).get("budget_tokens"));
        prose("V1C" + count, "Only this chapter was rewritten.");
        var changed = service.run("book", request, false);
        assertEquals((long) count - 1, changed.get("skipped_count"));
        assertEquals(40L, ((Map<?, ?>) changed.get("metrics")).get("budget_tokens"));
        verify(orchestrator, times(count + 1)).analyzeChapter(anyString(), anyString(), anyString(), anyString());
    }

    @Test void rewriteScopeSkipsUnchangedExtractionAndInvalidatesOnlyAtOrAfterBoundary() throws Exception {
        for (String chapter : List.of("V1C1", "V1C2", "V1C10", "V2C1"))
            memory.writePack("book", chapter, new LinkedHashMap<>(Map.of("payload", Map.of())));
        memory.invalidateFrom("book", "V1C2");
        assertNull(memory.readPack("book", "V1C1").get("context_stale"));
        assertEquals(true, memory.readPack("book", "V1C2").get("context_stale"));
        assertEquals(true, memory.readPack("book", "V1C10").get("context_stale"));
        assertEquals(true, memory.readPack("book", "V2C1").get("context_stale"));
        var request = request("V1C1", "V1C2");
        request.setFromChapter("V1C2");
        assertEquals(List.of("V1C2"), service.run("book", request, false).get("chapters"));
    }

    @Test void fingerprintsIgnoreMapInsertionOrderAndPathsAreValidated() throws Exception {
        Map<String, Object> left = new LinkedHashMap<>(), right = new LinkedHashMap<>();
        left.put("a", 1); left.put("b", 2);
        right.put("b", 2); right.put("a", 1);
        assertEquals(storage.fingerprint(left), storage.fingerprint(right));
        assertThrows(IllegalArgumentException.class, () -> storage.job("../escape", UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> storage.entry("book", "../escape"));
        assertThrows(IllegalArgumentException.class, () -> storage.job("book", "../escape"));
    }
}
