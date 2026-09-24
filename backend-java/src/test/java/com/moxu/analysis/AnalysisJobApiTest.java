package com.moxu.analysis;

import com.moxu.agent.EditorAgent;
import com.moxu.controller.SessionController;
import com.moxu.orchestrator.*;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisJobApiTest {
    MockMvc mvc;
    AnalysisJobService jobs;
    ContinuityService continuity;
    CausalAuditService causal;
    @BeforeEach void setup() {
        var pool = mock(OrchestratorPool.class);
        when(pool.getOrchestrator(anyString())).thenReturn(mock(Orchestrator.class));
        jobs = mock(AnalysisJobService.class);
        continuity = mock(ContinuityService.class);
        causal = mock(CausalAuditService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionController(pool, mock(EditorAgent.class),
                mock(DraftStorage.class), mock(MemoryPackStorage.class), jobs, continuity, causal)).build();
    }
    @Test void rejectsInvalidBudgetsAndChapterPathsBeforeStarting() throws Exception {
        for (String json : new String[] {
                "{\"chapters\":[\"../secret\"]}", "{\"chapters\":[\"V1C1\"],\"max_tokens\":-1}",
                "{\"chapters\":[\"V1C1\"],\"max_minutes\":0}", "{\"chapters\":[\"V1C1\"],\"max_calls_per_chapter\":100}"}) {
            mvc.perform(post("/projects/book/session/analyze-batch").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jobs);
    }
    @Test void routesExistingBatchEndpointThroughDurableJobs() throws Exception {
        when(jobs.run(eq("book"), any(), eq(false))).thenReturn(Map.of("status", "paused", "job_id", "fixture"));
        mvc.perform(post("/projects/book/session/analyze-batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chapters\":[\"V1C1\"],\"combined\":true,\"max_tokens\":10000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("paused"));
        verify(jobs).run(eq("book"), argThat(request -> Boolean.TRUE.equals(request.getCombined())
                && request.getMaxTokens() == 10000L), eq(false));
    }
    @Test void refusesGenerationBeforeStaleSourcesAreSynchronized() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "stale"))
                .when(continuity).requireReady("book", "V1C2");
        mvc.perform(post("/projects/book/session/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chapter\":\"V1C2\"}")).andExpect(status().isConflict());
        mvc.perform(get("/projects/book/session/stream-draft").param("chapter", "V1C2"))
                .andExpect(status().isConflict());
    }
    @Test void validatesCausalBudgetsAndDecisionEnumsBeforeInvokingService() throws Exception {
        for (String json : new String[] {
                "{\"revision\":\"\"}", "{\"revision\":\"r\",\"max_tokens\":-1}",
                "{\"revision\":\"r\",\"max_minutes\":0}", "{\"revision\":\"r\",\"max_minutes\":1441}"}) {
            mvc.perform(post("/projects/book/session/continuity/V1C2/causal/run")
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        }
        for (String json : new String[] {
                "{\"revision\":\"r\",\"finding_id\":\"f\",\"resolution\":\"approve_all\"}",
                "{\"revision\":\"r\",\"finding_id\":\"f\"}"}) {
            mvc.perform(post("/projects/book/session/continuity/V1C2/causal/decisions")
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(causal);
    }
    @Test void routesCausalStatusAndResumeWithExactRevision() throws Exception {
        when(causal.status("book", "V1C2")).thenReturn(Map.of("status", "paused"));
        when(causal.run(eq("book"), eq("V1C2"), any())).thenReturn(Map.of("status", "complete"));
        mvc.perform(get("/projects/book/session/continuity/V1C2/causal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("paused"));
        mvc.perform(post("/projects/book/session/continuity/V1C2/causal/run")
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":\"r\",\"max_tokens\":300000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("complete"));
        verify(causal).run("book", "V1C2", new CausalAuditService.Request("r", 300000L, null));
    }
}
