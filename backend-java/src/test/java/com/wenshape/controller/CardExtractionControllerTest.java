package com.wenshape.controller;

import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardExtractionControllerTest {
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final CardExtractionController controller = new CardExtractionController(gateway);
    private final CardExtractionController.Request request = new CardExtractionController.Request(
            List.of(new CardExtractionController.Source("master", "Outline", "Lin is a student. The bell never rings."),
                    new CardExtractionController.Source("author", "Notes", "Slow growth; a restrained third-person narration.")), "en");

    private void response(String content, String reason) {
        when(gateway.getProviderForAgent("extractor")).thenReturn("profile");
        when(gateway.chat(anyList(), anyString(), isNull(), eq(6000)))
                .thenReturn(LlmResponse.builder().content(content).finishReason(reason).build());
    }

    @Test
    void advertisesAvailableModulesWithoutCallingTheModel() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/projects/demo/cards/extract-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0]").value("character"))
                .andExpect(jsonPath("$.modules[1]").value("world"))
                .andExpect(jsonPath("$.modules[2]").value("style"));
        verifyNoInteractions(gateway);
    }

    @Test
    void advertisedModuleRoutesAcceptSourcesAndRejectEmptyRequests() throws Exception {
        response("{\"cards\":[]}", "stop");
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String module : List.of("character", "world", "style")) {
            String path = "/projects/demo/cards/extract-candidates/" + module;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                            {"sources":[{"id":"author","label":"Notes","content":"Lin is a student."}],"language":"en"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.module").value(module))
                    .andExpect(jsonPath("$.cards").isArray());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"sources\":[]}"))
                    .andExpect(status().isBadRequest());
        }
        verify(gateway, times(3)).chat(anyList(), eq("profile"), isNull(), eq(6000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEditableCandidatesAndOnlyVerifiedEvidence() {
        response("""
                {"cards":[{"name":"Lin","description":"A student","source_ids":["master","invented"],"evidence":"Lin is a student.","stars":9},
                {"name":"Bell","description":"Silent bell","source_ids":["master"],"evidence":"The bell is red."}]}
                """, "stop");
        var result = controller.extract("p", "character", request);
        var cards = (List<Map<String, Object>>) result.get("cards");
        assertEquals(List.of("master"), cards.getFirst().get("source_ids"));
        assertEquals(3, cards.getFirst().get("stars"));
        assertEquals(true, cards.getFirst().get("evidence_verified"));
        assertEquals(false, cards.get(1).get("evidence_verified"));
        assertEquals("", cards.get(1).get("evidence"));
        verify(gateway).chat(argThat(messages -> messages.get(1).get("content").toString().contains("Slow growth")), eq("profile"), isNull(), eq(6000));
    }

    @Test
    void malformedAndTruncatedResultsAreErrorsInsteadOfEmptySuccess() {
        response("not json", "stop");
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> controller.extract("p", "world", request)).getStatusCode().value());
        response("{\"cards\":[]}", "length");
        assertThrows(ResponseStatusException.class, () -> controller.extract("p", "world", request));
        response("{\"cards\":[]}", "stop");
        assertEquals(List.of(), controller.extract("p", "world", request).get("cards"));
    }

    @Test
    void exposesActionableRateLimitWithoutLeakingProviderResponse() throws Exception {
        when(gateway.getProviderForAgent("extractor")).thenReturn("profile");
        when(gateway.chat(anyList(), eq("profile"), isNull(), eq(6000))).thenThrow(
                new RuntimeException("LLM chat failed after 3 retries", new RuntimeException(
                        "LLM API error 429: {\"error\":{\"code\":\"gateway_concurrency_limit\"},\"private\":\"do-not-expose\"}")));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String body = mvc.perform(post("/projects/demo/cards/extract-candidates/character")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\":[{\"id\":\"a\",\"label\":\"Notes\",\"content\":\"Lin is a student.\"}]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("模型服务的并发额度已满，请等待其他生成结束后重试此模块。材料与其他候选已保留。"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("do-not-expose"));
    }

    @Test
    void explainsConnectionLossAndTimeoutWithoutReturningPartialCards() {
        when(gateway.getProviderForAgent("extractor")).thenReturn("profile");
        when(gateway.chat(anyList(), eq("profile"), isNull(), eq(6000))).thenThrow(
                new RuntimeException("LLM chat failed after 3 retries", new java.io.EOFException("unexpected end of stream on https://private.invalid")),
                new RuntimeException("LLM chat failed", new java.net.SocketTimeoutException()));
        var disconnected = assertThrows(ResponseStatusException.class, () -> controller.extract("p", "world", request));
        assertEquals(502, disconnected.getStatusCode().value());
        assertTrue(disconnected.getReason().contains("断开了连接"));
        assertFalse(disconnected.getReason().contains("private.invalid"));
        var timeout = assertThrows(ResponseStatusException.class, () -> controller.extract("p", "world", request));
        assertEquals(504, timeout.getStatusCode().value());
    }

    @Test
    void rejectsUnsupportedOrOversizedSourcesWithoutModelCalls() {
        assertThrows(ResponseStatusException.class, () -> controller.extract("p", "unknown", request));
        var tooLong = new CardExtractionController.Request(List.of(new CardExtractionController.Source("s", "s", "a".repeat(120001))), "en");
        assertEquals(413, assertThrows(ResponseStatusException.class, () -> controller.extract("p", "style", tooLong)).getStatusCode().value());
        verifyNoInteractions(gateway);
    }

    @Test
    void allThreeModulesCanRunConcurrently() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        when(gateway.getProviderForAgent("extractor")).thenReturn("profile");
        when(gateway.chat(anyList(), eq("profile"), isNull(), eq(6000))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return LlmResponse.builder().content("{\"cards\":[]}").finishReason("stop").build();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.of("character", "world", "style").stream()
                    .map(module -> executor.submit(() -> controller.extract("p", module, request))).toList();
            try { assertTrue(entered.await(3, TimeUnit.SECONDS), "All modules should reach the model before any finishes"); }
            finally { release.countDown(); }
            for (var task : tasks) assertNotNull(task.get(3, TimeUnit.SECONDS));
        }
    }
}
