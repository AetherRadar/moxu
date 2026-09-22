package com.moxu.context;

import com.moxu.model.entity.ChapterSummary;
import com.moxu.agent.ArchivistAgent;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChapterMemoryBoundaryTest {
    @TempDir Path root;

    @Test void writingMemoryExcludesFutureAndUnwrittenPlansBeforeRanking() throws Exception {
        var drafts = new DraftStorage() {
            @Override public Path getProjectPath(String id) { return root.resolve(id); }
        };
        for (String chapter : List.of("V1C1", "V1C2", "V1C3", "V1C10")) {
            drafts.saveCurrentDraft("book", chapter, chapter.equals("V1C2") ? "" : "海燕号已经完成的调查正文", null, null, false);
            drafts.saveChapterSummary("book", ChapterSummary.builder().chapter(chapter)
                    .briefSummary("海燕号调查 " + chapter).build());
        }
        var engine = new ContextSelectEngine(mock(CardStorage.class), mock(CanonStorage.class), drafts, null);
        assertTrue(engine.retrievalSelectForChapter("book", "V1C1", "海燕号", List.of("summary"), 10).isEmpty());
        var results = engine.retrievalSelectForChapter("book", "V1C3", "海燕号", List.of("summary"), 10);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().getContent().contains("V1C1"));
        assertEquals(List.of("V1C1"), drafts.writtenChaptersBefore("book", "V1C3"));
    }

    @Test void volumeArchiveDoesNotSummarizeUnwrittenChapterPlans() {
        var archivist = new ArchivistAgent(mock(LlmGateway.class), mock(CardStorage.class),
                mock(DraftStorage.class), mock(ContextSelectEngine.class), mock(CanonStorage.class)) {
            @Override protected LlmResponse callLlm(List<Map<String, String>> messages) {
                String input = messages.getLast().get("content");
                assertTrue(input.contains("已经找到保险单"));
                assertFalse(input.contains("未来揭晓主谋"));
                return LlmResponse.builder().content("{\"brief_summary\":\"已经找到保险单\"}").build();
            }
        };
        var result = archivist.generateVolumeSummary("book", "V1", List.of(
                ChapterSummary.builder().chapter("V1C1").wordCount(1200).briefSummary("已经找到保险单").build(),
                ChapterSummary.builder().chapter("V1C6").wordCount(0).briefSummary("未来揭晓主谋").build()));
        assertEquals(1, result.get("chapter_count"));
        assertEquals(1200, result.get("total_word_count"));
    }
}
