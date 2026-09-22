package com.moxu.orchestrator;

import com.moxu.agent.ArchivistAgent;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChapterArchiveTest {
    @TempDir Path directory;
    private <T extends BaseStorage> T storage(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString()); return value;
    }

    @Test void failedExtractionPersistsPartialStatusAndRetrySkipsCompletedStages() throws Exception {
        var drafts = storage(new DraftStorage());
        var canon = storage(new CanonStorage());
        var memory = storage(new MemoryPackStorage());
        var agent = mock(ArchivistAgent.class);
        String prose = "保险日期早于沉船三天。编号143到147之间缺了一页。";
        when(agent.generateChapterSummary(any(), any(), any(), any())).thenReturn(ChapterSummary.builder().briefSummary("日期有误").build());
        when(agent.generateVolumeSummary(any(), any(), any())).thenReturn(Map.of("volume_id", "V1"));
        when(agent.extractCanonUpdates(any(), any(), any()))
                .thenThrow(new IllegalStateException("relay unavailable"))
                .thenReturn(Map.of("facts", List.of(Map.of("statement", "保险日期早于沉船三天", "evidence", "保险日期早于沉船三天。", "certainty", "explicit")),
                        "timeline_events", List.of(), "character_states", List.of()));
        var archive = new ChapterArchive(agent, drafts, canon, memory);
        var first = archive.run("book", "V1C2", prose, "失踪的三页", message -> {});
        assertEquals("partial", first.get("status"));
        assertEquals("partial", drafts.getArchiveStatus("book", "V1C2").get("status"));
        var review = (Map<?, ?>)first.get("review");
        assertEquals("partial", review.get("status"));
        assertEquals(1, review.get("conflict_count"));
        var second = archive.run("book", "V1C2", prose, "失踪的三页", message -> {});
        assertEquals("complete", second.get("status"));
        archive.run("book", "V1C2", prose, "失踪的三页", message -> {});
        verify(agent, times(1)).generateChapterSummary(any(), any(), any(), any());
        verify(agent, times(1)).generateVolumeSummary(any(), any(), any());
        verify(agent, times(2)).extractCanonUpdates(any(), any(), any());
        assertEquals(1, canon.listFacts("book").size());
        assertEquals(Boolean.TRUE, memory.readPack("book", "V1C2").get("context_stale"));
        assertEquals(List.of("保险日期早于沉船三天"), drafts.getChapterSummary("book", "V1C2").orElseThrow().getNewFacts());
        assertEquals("保险日期早于沉船三天。", canon.listFacts("book").get(0).getEvidence());
    }

    @Test void emptyConflictsOnExtractionFailureAreUnknownAndFallbackSummaryIsNotSaved() throws Exception {
        var drafts = storage(new DraftStorage());
        var agent = mock(ArchivistAgent.class);
        when(agent.generateChapterSummary(any(), any(), any(), any())).thenReturn(ChapterSummary.builder().generationStatus("fallback").build());
        when(agent.extractCanonUpdates(any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        var archive = new ChapterArchive(agent, drafts, storage(new CanonStorage()), storage(new MemoryPackStorage()));
        var result = archive.run("book", "V1C1", "原有正文。", "第一章", message -> {});
        assertEquals("partial", result.get("status"));
        assertNull(((Map<?, ?>)result.get("review")).get("has_conflicts"));
        assertTrue(drafts.getChapterSummary("book", "V1C1").isEmpty());
        verify(agent, never()).generateVolumeSummary(any(), any(), any());
    }
}
