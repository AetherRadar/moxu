package com.moxu.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.VolumeStorage;
import com.moxu.storage.ProjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutlineGenerationFailureTest {
    @TempDir Path root;
    private LlmGateway gateway;
    private OutlineController controller;

    @BeforeEach void setup() {
        var drafts = mock(DraftStorage.class);
        var volumes = mock(VolumeStorage.class);
        var projects = mock(ProjectStorage.class);
        gateway = mock(LlmGateway.class);
        when(drafts.getProjectPath("book")).thenReturn(root);
        when(gateway.getProviderForAgent("writer")).thenReturn("small-model");
        controller = new OutlineController(drafts, volumes, projects, gateway, new ObjectMapper());
    }

    @Test void upstreamFailureIsNotReportedAsSuccessfulTemplate() {
        when(gateway.chatOnce(anyList(), eq("small-model"), eq(0.45), isNull())).thenThrow(new RuntimeException("offline"));
        var error = assertThrows(ResponseStatusException.class, () -> controller.generateMasterOutlineCandidates("book", Map.of()));
        assertEquals(502, error.getStatusCode().value());
    }

    @Test void truncatedOutputCannotBecomeACandidate() {
        when(gateway.chatOnce(anyList(), eq("small-model"), eq(0.45), isNull())).thenReturn(LlmResponse.builder().content("{\"candidates\":[").finishReason("length").build());
        assertThrows(ResponseStatusException.class, () -> controller.generateMasterOutlineCandidates("book", Map.of()));
        assertThrows(ResponseStatusException.class, () -> controller.generateVolumeOutlineCandidates("book", "V1", Map.of()));
    }

    @Test void successfulCandidatesUseConfiguredTokenBudget() {
        when(gateway.chatOnce(anyList(), eq("small-model"), eq(0.45), isNull())).thenReturn(LlmResponse.builder().content("{\"candidates\":[{\"route_id\":\"a\",\"label\":\"证据链\",\"outline_content\":\"真正生成的三卷总纲\",\"planned_volume_count_hint\":3}]}").finishReason("stop").build());
        var result = controller.generateMasterOutlineCandidates("book", Map.of());
        assertEquals(true, result.get("success"));
        var candidates = (List<?>) result.get("candidates");
        assertEquals(1, candidates.size());
        assertEquals("真正生成的三卷总纲", ((Map<?,?>) candidates.get(0)).get("outline_content"));
        verify(gateway).chatOnce(anyList(), eq("small-model"), eq(0.45), isNull());
    }

    @Test void failedChapterPlanDoesNotSilentlyParseTheVolumeAsGeneratedChapters() {
        when(gateway.streamChat(anyList(), eq("small-model"), eq(0.4), isNull()))
                .thenReturn(Flux.error(new RuntimeException("offline")));
        when(gateway.chatOnce(anyList(), eq("small-model"), eq(0.4), isNull()))
                .thenThrow(new RuntimeException("offline"));
        var error = assertThrows(ResponseStatusException.class, () -> controller.planVolumeChapters(
                "book", "V1", Map.of("content", "第1章：修复保险单。第2章：调查轮机部。")));
        assertEquals(502, error.getStatusCode().value());
    }

    @Test void chapterPlanRequiresModelGeneratedChapters() {
        when(gateway.streamChat(anyList(), eq("small-model"), eq(0.4), isNull()))
                .thenReturn(Flux.just("{\"chapters\":[]}"));
        assertThrows(ResponseStatusException.class, () -> controller.planVolumeChapters(
                "book", "V1", Map.of("content", "第1章：修复保险单。")));
    }

    @Test void boundedPlanPreservesChapterNumbersAndRejectsPartialResults() {
        when(gateway.streamChat(anyList(), eq("small-model"), eq(0.4), isNull()))
                .thenReturn(Flux.just("{\"chapters\":[{\"chapter_no\":7,\"title\":\"新线索\",\"goal\":\"调查\"}]}"));
        var body = Map.<String, Object>of("content", "二十章分卷纲", "start_chapter", 7, "chapter_count", 1);
        var result = controller.planVolumeChapters("book", "V1", body);
        assertEquals(7, ((Map<?, ?>) ((List<?>) result.get("chapters")).get(0)).get("chapter_no"));
        assertThrows(ResponseStatusException.class, () -> controller.planVolumeChapters("book", "V1",
                Map.of("content", "二十章分卷纲", "start_chapter", 7, "chapter_count", 2)));
        assertThrows(ResponseStatusException.class, () -> controller.planVolumeChapters("book", "V1",
                Map.of("content", "二十章分卷纲", "start_chapter", 1, "chapter_count", 1)));
    }

    @Test void invalidRangeIsRejectedBeforeCallingModel() {
        var error = assertThrows(ResponseStatusException.class, () -> controller.planVolumeChapters("book", "V1",
                Map.of("content", "分卷纲", "start_chapter", -1, "chapter_count", 30)));
        assertEquals(400, error.getStatusCode().value());
        verify(gateway, never()).streamChat(anyList(), anyString(), any(), any());
    }
}
