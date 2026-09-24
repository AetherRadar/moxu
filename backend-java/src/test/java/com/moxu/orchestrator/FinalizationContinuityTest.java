package com.moxu.orchestrator;

import com.moxu.agent.ArchivistAgent;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.quality.ContinuityProtection;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinalizationContinuityTest {
    @TempDir Path directory;
    private <T extends BaseStorage> T storage(T item) {
        ReflectionTestUtils.setField(item, "dataDir", directory.toString());
        return item;
    }
    @Test void requiresCurrentSourceAndDraftAcknowledgementBeforeArchival() throws Exception {
        var drafts = storage(new DraftStorage());
        var archivist = mock(ArchivistAgent.class);
        when(archivist.generateChapterSummary(any(), any(), any(), any()))
                .thenReturn(ChapterSummary.builder().briefSummary("A summary").build());
        when(archivist.extractCanonUpdates(any(), any(), any()))
                .thenReturn(Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of()));
        when(archivist.generateVolumeSummary(any(), any(), any())).thenReturn(Map.of("volume_id", "V1"));
        var orchestrator = new Orchestrator(null, storage(new CardStorage()), drafts, storage(new CanonStorage()),
                storage(new MemoryPackStorage()), null, archivist, null, null, null, null, null);
        String content = "时间定格在十三点零四分。";
        drafts.saveCurrentDraft("book", "V1C1", "第二天一早。", null, null, false);
        drafts.saveCurrentDraft("book", "V1C2", content, null, null, false);
        assertThrows(ResponseStatusException.class,
                () -> orchestrator.processFeedback("book", "V1C2", "", "confirm", List.of()));
        verifyNoInteractions(archivist);
        String revision = ContinuityProtection.review(drafts, "book", "V1C2", content).revision();
        drafts.saveCurrentDraft("book", "V1C1", "第二天上午。", null, null, false);
        assertThrows(ResponseStatusException.class,
                () -> orchestrator.processFeedback("book", "V1C2", "", "confirm", List.of(), revision));
        verifyNoInteractions(archivist);
        String fresh = ContinuityProtection.review(drafts, "book", "V1C2", content).revision();
        String edited = content + "\n他检查门锁。";
        drafts.saveCurrentDraft("book", "V1C2", edited, null, null, false);
        assertThrows(ResponseStatusException.class,
                () -> orchestrator.processFeedback("book", "V1C2", "", "confirm", List.of(), fresh));
        verifyNoInteractions(archivist);
        String reviewedEdit = ContinuityProtection.review(drafts, "book", "V1C2", edited).revision();
        var result = orchestrator.processFeedback("book", "V1C2", "", "confirm", List.of(), reviewedEdit);
        assertEquals(true, result.get("success"));
        verify(archivist).extractCanonUpdates(any(), any(), any());
        assertEquals(edited, drafts.getFinalDraft("book", "V1C2").orElseThrow());
    }

    @Test void semanticGateBindsAllPrecedingChaptersAndCannotBeBypassedWithLocalRevision() throws Exception {
        var drafts = storage(new DraftStorage());
        var archivist = mock(ArchivistAgent.class);
        var gateway = mock(com.moxu.llm.LlmGateway.class);
        when(gateway.analysisFingerprint()).thenReturn("model");
        var semantic = new com.moxu.analysis.DraftSemanticReviewService(drafts,
                storage(new com.moxu.analysis.AnalysisStorage()), gateway,
                mock(com.moxu.analysis.CausalExtractor.class));
        var orchestrator = new Orchestrator(null, storage(new CardStorage()), drafts, storage(new CanonStorage()),
                storage(new MemoryPackStorage()), null, archivist, null, null, null, null, null);
        orchestrator.setSemanticReview(semantic);
        drafts.saveCurrentDraft("book", "V1C1", "Earlier", null, null, false);
        drafts.saveCurrentDraft("book", "V1C2", "Nearest", null, null, false);
        drafts.saveCurrentDraft("book", "V1C3", "Draft", null, null, false);
        String local = ContinuityProtection.review(drafts, "book", "V1C3", "Draft").revision();
        assertThrows(ResponseStatusException.class,
                () -> orchestrator.processFeedback("book", "V1C3", "", "confirm", List.of(), local));
        String review = semantic.review("book", "V1C3", "Draft").revision();
        drafts.saveCurrentDraft("book", "V1C1", "Edited early source", null, null, false);
        assertThrows(ResponseStatusException.class,
                () -> orchestrator.processFeedback("book", "V1C3", "", "confirm", List.of(), review));
        verifyNoInteractions(archivist);
    }
}
