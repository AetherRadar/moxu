package com.moxu.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.agent.*;
import com.moxu.context.ContextSelectEngine;
import com.moxu.llm.LlmGateway;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.orchestrator.Orchestrator;
import com.moxu.storage.*;
import com.moxu.websocket.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutlineWritingHandoffTest {
    @TempDir Path root;

    private DraftStorage drafts() {
        return new DraftStorage() {
            @Override public Path getProjectPath(String id) { return root.resolve(id); }
        };
    }

    @Test void appliedPlanRetainsBoundariesAndBlankGoalReachesArchivist() throws Exception {
        var drafts = drafts();
        var gateway = mock(LlmGateway.class);
        var controller = new OutlineController(drafts, mock(VolumeStorage.class), mock(ProjectStorage.class), gateway, new ObjectMapper());
        controller.applyVolumeChapterPlan("book", "V1", Map.of("chapters", List.of(Map.of(
                "chapter_no", 1, "title", "残页", "summary", "陆沉修复海燕号保险单。",
                "goal", "发现涂改日期", "key_beats", List.of("侧光显影", "记录纸张批号"),
                "ending_hook", "找到港口编号", "must_not_include", List.of("不得揭晓主谋", "不得出现轮机部缺页"),
                "outline_content", "只写陆沉在档案室的调查。"))));
        String goal = drafts.resolveWritingGoal("book", "V1C1", "");
        assertTrue(goal.contains("陆沉"));
        assertTrue(goal.contains("侧光显影"));
        assertTrue(goal.contains("不得揭晓主谋"));
        assertTrue(goal.contains("只写陆沉在档案室的调查"));

        var archivist = mock(ArchivistAgent.class);
        when(archivist.execute(eq("book"), eq("V1C1"), anyMap())).thenAnswer(call -> {
            Map<String, Object> context = call.getArgument(2);
            assertEquals(goal, context.get("chapter_goal"));
            throw new IllegalStateException("stop after observing the handoff");
        });
        var orchestrator = new Orchestrator(gateway, mock(CardStorage.class), drafts,
                mock(CanonStorage.class), mock(MemoryPackStorage.class), mock(WriterAgent.class),
                archivist, mock(EditorAgent.class), mock(ContextSelectEngine.class),
                mock(SessionWebSocketHandler.class), mock(TraceWebSocketHandler.class), mock(BindingsController.class));
        orchestrator.startSession("book", "V1C1", "残页", null, 1500, List.of());
        verify(archivist).execute(eq("book"), eq("V1C1"), anyMap());
    }

    @Test void explicitGoalWinsAndMissingOutlineFallsBackToOwnSummary() throws Exception {
        var drafts = drafts();
        drafts.saveChapterSummary("book", ChapterSummary.builder().chapter("V1C2").briefSummary("追查名册缺页").build());
        assertEquals("追查名册缺页", drafts.resolveWritingGoal("book", "V1C2", null));
        assertEquals("新的作者要求", drafts.resolveWritingGoal("book", "V1C2", "新的作者要求"));
        assertEquals("", drafts.resolveWritingGoal("book", "V1C1", ""));
    }
}
