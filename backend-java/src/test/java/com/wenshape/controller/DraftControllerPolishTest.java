package com.wenshape.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wenshape.agent.EditorAgent;
import com.wenshape.config.GlobalExceptionHandler;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.DeAiRewriteConfig;
import com.wenshape.rewrite.PolishService;
import com.wenshape.storage.DeAiRewriteConfigStorage;
import com.wenshape.storage.DraftStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MVC 分派和全局 advice，不能只调用 controller 方法断言异常。 */
class DraftControllerPolishTest {
    private static final String URL = "/projects/project/drafts/rewrite";
    private static final String ORIGINAL = "他付了120元，拿起三本书，慢慢走回家。";
    private static final String CANDIDATE = "他花了120元，拿起三本书，缓缓走回家。";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final DraftStorage drafts = mock(DraftStorage.class);
    private final DeAiRewriteConfigStorage configs = mock(DeAiRewriteConfigStorage.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(configs.getConfig("project")).thenReturn(DeAiRewriteConfig.builder().engine("llm").build());
        when(gateway.getProviderForAgent("writer")).thenReturn("writer-profile");
        when(gateway.chatOnce(anyList(), anyString(), anyDouble(), anyInt()))
                .thenReturn(LlmResponse.builder().content(CANDIDATE).model("writer-model").finishReason("stop").build());
        mvc = mvc(new PolishService(gateway, "http://127.0.0.1:11434", "moxu-polish:qwen3-1.7b",
                "moxu-polish:qwen3-1.7b", false, 120));
    }

    @Test
    void successPreservesFrontendResponseAndNeverWritesDraft() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rewritten").value(CANDIDATE))
                .andExpect(jsonPath("$.engine").value("llm"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.debug.status").value("candidate"))
                .andExpect(jsonPath("$.debug.model").value("writer-model"));
        verifyNoInteractions(drafts);
        verify(configs, never()).saveConfig(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":\"\"}", "{\"text\":\"　\"}", "null", "{"})
    void invalidInputIs400ThroughGlobalAdvice(String json) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isString());
        verifyNoInteractions(gateway, drafts);
    }

    @Test
    void tooLongIs422WithoutModelCall() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("雨".repeat(3001))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("3000")));
        verifyNoInteractions(gateway, drafts);
    }

    @Test
    void changedNumbersAreRestoredInPreviewAndOriginalIsNotPersisted() throws Exception {
        when(gateway.chatOnce(anyList(), anyString(), anyDouble(), anyInt()))
                .thenReturn(LlmResponse.builder().content(CANDIDATE.replace("花了120", "付了150")).finishReason("stop").build());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewritten").value(CANDIDATE.replace("花了", "付了")))
                .andExpect(jsonPath("$.debug.numberRepairs[0].original").value("120元"))
                .andExpect(jsonPath("$.debug.numberRepairs[0].generated").value("150元"));
        verifyNoInteractions(drafts);
    }

    @Test
    void genericBackendErrorIs503AndNeverExposesSecrets() throws Exception {
        doThrow(new RuntimeException("https://secret.example sk-sensitive private text"))
                .when(gateway).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("原文未修改")))
                .andExpect(content().string(not(containsString("sk-sensitive"))))
                .andExpect(content().string(not(containsString("secret.example"))));
        verifyNoInteractions(drafts);
    }

    @Test
    void genericBackendTimeoutIs504ThroughGlobalAdvice() throws Exception {
        doThrow(new RuntimeException("sensitive", new SocketTimeoutException("sensitive")))
                .when(gateway).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value(containsString("超时")))
                .andExpect(content().string(not(containsString("sensitive"))));
        verifyNoInteractions(drafts);
    }

    @Test
    void fakeLocalHttpFailureIs503ThroughGlobalAdvice() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] error = "sk-sensitive private backend text".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.start();
        try {
            local(server, 2);
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").isString())
                    .andExpect(content().string(not(containsString("sk-sensitive"))));
            assertEquals(1, calls.get());
            verifyNoInteractions(gateway, drafts);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localBusyIs429AndPendingHttpCallTimesOutAs504ThroughGlobalAdvice() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            local(server, 2);
            var first = workers.submit(() -> mvc.perform(post(URL)
                    .contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL))));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ORIGINAL)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.detail").value(containsString("忙")));
            first.get(4, TimeUnit.SECONDS)
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.detail").value(containsString("超时")));
            assertEquals(1, calls.get());
            verifyNoInteractions(gateway, drafts);
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private void local(HttpServer server, int timeout) {
        when(configs.getConfig("project")).thenReturn(DeAiRewriteConfig.builder().engine("local").build());
        mvc = mvc(new PolishService(gateway, "http://127.0.0.1:" + server.getAddress().getPort(),
                "moxu-polish:qwen3-1.7b", "moxu-polish:qwen3-1.7b", false, timeout));
    }

    private MockMvc mvc(PolishService polish) {
        EditorAgent editor = new EditorAgent(gateway, null, drafts, null, configs, polish);
        DraftController controller = new DraftController(drafts, editor, null, null, null, null, configs, null, gateway);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String body(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("text", text));
    }
}
