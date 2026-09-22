package com.wenshape.controller;

import com.wenshape.orchestrator.Orchestrator;
import com.wenshape.orchestrator.OrchestratorPool;
import com.wenshape.storage.DraftStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DraftControllerCreateTest {
    @TempDir
    Path dataDir;

    private final DraftStorage drafts = new DraftStorage();
    private final OrchestratorPool pool = mock(OrchestratorPool.class);
    private final Orchestrator orchestrator = mock(Orchestrator.class);
    private final BindingsController bindings = mock(BindingsController.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(drafts, "dataDir", dataDir.toString());
        when(pool.getOrchestrator("book")).thenReturn(orchestrator);
        mvc = MockMvcBuilders.standaloneSetup(new DraftController(
                drafts, null, null, pool, bindings, null, null, null, null
        )).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void emptyChapterIsPersistedWithoutWaitingForAi(String content) throws Exception {
        mvc.perform(put("/projects/book/drafts/V1C1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\",\"title\":\"The Missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.chapter").value("V1C1"))
                .andExpect(jsonPath("$.title").value("The Missing"));

        assertEquals(List.of("V1C1"), drafts.listChapters("book"));
        assertEquals(content, drafts.getLatestDraft("book", "V1C1").orElseThrow().getContent());
        assertEquals("The Missing", drafts.getChapterSummary("book", "V1C1").orElseThrow().getTitle());
        verify(bindings).markBindingsStale("book", List.of("V1C1"), "draft_saved", true);
        verifyNoInteractions(pool, orchestrator);
    }

    @Test
    void savingWrittenContentStillRefreshesArtifacts() throws Exception {
        mvc.perform(put("/projects/book/drafts/V1C1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"The bell rang.\",\"title\":\"The Missing\"}"))
                .andExpect(status().isOk());
        verify(orchestrator).refreshDraftArtifacts("book", "V1C1");
        assertEquals("The bell rang.", drafts.getLatestDraft("book", "V1C1").orElseThrow().getContent());
    }
}
