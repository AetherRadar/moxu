package com.moxu.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.moxu.agent.EditorAgent;
import com.moxu.config.GlobalExceptionHandler;
import com.moxu.controller.DraftController;
import com.moxu.model.entity.DeAiRewriteConfig;
import com.moxu.rewrite.PolishService;
import com.moxu.storage.DeAiRewriteConfigStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.ProjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 离线 HTTP → 真实 provider → gateway → PolishService → MVC，覆盖单次调用协议。 */
class ProviderSingleCallTest {
    private static final String ORIGINAL = "他付了120元，拿起三本书，慢慢走回家。";
    private static final String CANDIDATE = "他花了120元，拿起三本书，缓缓走回家。";
    private static final List<Map<String, String>> MESSAGES = List.of(Map.of("role", "user", "content", ORIGINAL));
    private static final String REWRITE_URL = "/projects/project/drafts/rewrite";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<JsonNode> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final DraftStorage drafts = mock(DraftStorage.class);
    private volatile IntFunction<Reply> responder;
    private HttpServer server;
    private String baseUrl;
    @TempDir Path dataPath;

    enum Backend { OPENAI, RESPONSES, ANTHROPIC, DEEPSEEK, QWEN }

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            requestBodies.add(mapper.readTree(exchange.getRequestBody()));
            Reply reply = responder.apply(requests.incrementAndGet());
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            if (reply.status() == 503) {
                exchange.getResponseHeaders().set("Retry-After", "0");
            }
            if (reply.status() == 307) {
                exchange.getResponseHeaders().set("Location", "/redirect-target");
            }
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void emptyBodyReachesPolishAs422WithExactlyOneRequest(Backend backend) throws Exception {
        respond(payload(backend, "", false));
        mvc(gateway(provider(backend))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("未返回正文")))
                .andExpect(jsonPath("$.detail").value(containsString("原文未修改")));
        assertEquals(1, requests.get());
        verifyNoInteractions(drafts);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void reportedTruncationIs422EvenWhenOutputLooksComplete(Backend backend) throws Exception {
        respond(payload(backend, CANDIDATE, true));
        mvc(gateway(provider(backend))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("截断")))
                .andExpect(jsonPath("$.rewritten").doesNotExist());
        assertEquals(1, requests.get());
        verifyNoInteractions(drafts);
    }

    @ParameterizedTest
    @EnumSource(value = Backend.class, names = {"OPENAI", "DEEPSEEK", "QWEN"})
    void reasoningOnlyExhaustionCannotTriggerProviderSupplementOrGatewayRetry(Backend backend) throws Exception {
        respond(openAiReasoningOnly());
        mvc(gateway(provider(backend))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("截断")));
        assertEquals(1, requests.get());
        assertEquals(512, requestBodies.getFirst().path("max_tokens").asInt());
        // The synchronous API buffers one streaming request to keep long proxy calls alive.
        assertTrue(requestBodies.getFirst().path("stream").asBoolean(false));
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void successfulCandidateAndUsageSurviveSingleCallPath(Backend backend) throws Exception {
        respond(payload(backend, CANDIDATE, false));
        LlmGateway gateway = gateway(provider(backend));
        mvc(gateway).perform(post(REWRITE_URL).contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewritten").value(CANDIDATE))
                .andExpect(jsonPath("$.debug.status").value("candidate"));
        assertEquals(1, requests.get());
        assertEquals(1, gateway.getStats().get("totalRequests"));
        assertEquals(24L, gateway.getStats().get("totalTokens"));
        String expectedPath = switch (backend) {
            case RESPONSES -> "/responses";
            case ANTHROPIC -> "/v1/messages";
            default -> "/chat/completions";
        };
        assertEquals(List.of(expectedPath), requestPaths);
        verifyNoInteractions(drafts);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void httpFailureIs503WithoutRetryOrSensitiveBodyLeak(Backend backend) throws Exception {
        responder = count -> new Reply(500, "application/json", "{\"error\":\"sk-sensitive private prompt\"}");
        mvc(gateway(provider(backend))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("sk-sensitive"))))
                .andExpect(jsonPath("$.detail").value(containsString("原文未修改")));
        assertEquals(1, requests.get());
        verifyNoInteractions(drafts);
    }

    @Test
    void openAiNormalChatStillSupplementsReasoningOnlyOutput() throws Exception {
        String initial = openAiReasoningOnly();
        String success = payload(Backend.OPENAI, CANDIDATE, false);
        responder = count -> json(count == 1 ? initial : success);
        LlmResponse response = provider(Backend.OPENAI).chat(MESSAGES, 0.2, 512);
        assertEquals(CANDIDATE, response.getContent());
        assertEquals(2, requests.get());
        assertEquals(512, requestBodies.getFirst().path("max_tokens").asInt());
        assertTrue(requestBodies.get(1).path("max_tokens").asInt() > 512);
    }

    @ParameterizedTest
    @EnumSource(Backend.class)
    void singleCallCannotFollowRetryHintsOrRedirects(Backend backend) throws Exception {
        MockMvc mvc = mvc(gateway(provider(backend)));
        for (int httpStatus : List.of(503, 307)) {
            requests.set(0);
            responder = count -> new Reply(httpStatus, "application/json", "{}");
            mvc.perform(post(REWRITE_URL).contentType(MediaType.APPLICATION_JSON).content(request()))
                    .andExpect(status().isServiceUnavailable());
            assertEquals(1, requests.get(), "Only one request permitted for HTTP " + httpStatus);
        }
    }

    @Test
    void gatewayNormalChatStillRetriesHttpFailure() throws Exception {
        String success = payload(Backend.OPENAI, CANDIDATE, false);
        responder = count -> count == 1 ? new Reply(500, "application/json", "{}") : json(success);
        LlmGateway gateway = gateway(provider(Backend.OPENAI));
        assertEquals(CANDIDATE, gateway.chat(MESSAGES, "fixture", 0.2, 512).getContent());
        assertEquals(2, requests.get());
        assertEquals(1, gateway.getStats().get("totalRequests"));
    }

    @Test
    void responsesNormalChatStillRejectsEmptyContentButSingleCallPreservesIt() throws Exception {
        respond(payload(Backend.RESPONSES, "", false));
        LlmProvider provider = provider(Backend.RESPONSES);
        assertThrows(RuntimeException.class, () -> provider.chat(MESSAGES, 0.2, 512));
        assertEquals(1, requests.get());
        LlmResponse response = provider.chatOnce(MESSAGES, 0.2, 512);
        assertEquals("", response.getContent());
        assertEquals("stop", response.getFinishReason());
        assertEquals(2, requests.get());
    }

    @Test
    void responsesSingleCallPreservesEmptyBodyAndTruncationTogether() throws Exception {
        respond(payload(Backend.RESPONSES, "", true));
        LlmResponse response = gateway(provider(Backend.RESPONSES)).chatOnce(MESSAGES, "fixture", 0.2, 512);
        assertEquals("", response.getContent());
        assertEquals("incomplete", response.getFinishReason());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "end_turn", "stop_sequence"})
    void anthropicDefaultSingleCallPreservesStopReasonWithoutChangingText(String stopReason) throws Exception {
        var root = mapper.readTree(payload(Backend.ANTHROPIC, CANDIDATE, false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("stop_reason", stopReason);
        respond(root.toString());
        LlmResponse response = gateway(provider(Backend.ANTHROPIC)).chatOnce(MESSAGES, "fixture", 0.2, 512);
        assertEquals(CANDIDATE, response.getContent());
        assertEquals(stopReason, response.getFinishReason());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responsesSseIncompleteAndMissingTerminalEventAre422(boolean includeTerminal) throws Exception {
        String delta = "event: response.output_text.delta\ndata: " + mapper.writeValueAsString(
                Map.of("type", "response.output_text.delta", "delta", CANDIDATE)) + "\n\n";
        String terminal = "event: response.incomplete\ndata: " + mapper.writeValueAsString(Map.of(
                "type", "response.incomplete", "response", mapper.readTree(payload(Backend.RESPONSES, CANDIDATE, true)))) + "\n\n";
        responder = count -> new Reply(200, "text/event-stream", delta + (includeTerminal ? terminal : ""));
        mvc(gateway(provider(Backend.RESPONSES))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("截断")));
        assertEquals(1, requests.get());
    }

    @Test
    void responsesCompletedSseStillReturnsCandidate() throws Exception {
        String terminal = "event: response.completed\ndata: " + mapper.writeValueAsString(Map.of(
                "type", "response.completed", "response", mapper.readTree(payload(Backend.RESPONSES, CANDIDATE, false)))) + "\n\n";
        responder = count -> new Reply(200, "text/event-stream", terminal);
        mvc(gateway(provider(Backend.RESPONSES))).perform(post(REWRITE_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewritten").value(CANDIDATE));
        assertEquals(1, requests.get());
    }

    private String payload(Backend backend, String text, boolean truncated) throws Exception {
        Object value = switch (backend) {
            case OPENAI, DEEPSEEK, QWEN -> Map.of("model", "fixture-model", "choices", List.of(Map.of(
                    "message", Map.of("role", "assistant", "content", text), "finish_reason", truncated ? "length" : "stop")),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 14));
            case RESPONSES -> Map.of("model", "fixture-model", "status", truncated ? "incomplete" : "completed",
                    "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))),
                    "usage", Map.of("input_tokens", 10, "output_tokens", 14));
            case ANTHROPIC -> Map.of("model", "fixture-model", "stop_reason", truncated ? "max_tokens" : "end_turn",
                    "content", List.of(Map.of("type", "text", "text", text)), "usage", Map.of("input_tokens", 10, "output_tokens", 14));
        };
        return mapper.writeValueAsString(value);
    }

    private String openAiReasoningOnly() throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of(
                "role", "assistant", "content", "", "reasoning_content", "still reasoning"), "finish_reason", "length")),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 512)));
    }

    private LlmProvider provider(Backend backend) {
        return switch (backend) {
            case OPENAI -> new OpenAiProvider("offline-test", baseUrl, "fixture-model", 512, 0.2);
            case RESPONSES -> new ResponsesProvider("offline-test", baseUrl, "fixture-model", 512, 0.2);
            case ANTHROPIC -> new AnthropicProvider("offline-test", baseUrl, "fixture-model", 512, 0.2);
            case DEEPSEEK -> new OpenAiProvider("offline-test", baseUrl, "fixture-model", 512, 0.2, "deepseek");
            case QWEN -> new OpenAiProvider("offline-test", baseUrl, "fixture-model", 512, 0.2, "qwen");
        };
    }

    @SuppressWarnings("unchecked")
    private LlmGateway gateway(LlmProvider provider) {
        ProjectStorage projects = mock(ProjectStorage.class);
        when(projects.getDataPath()).thenReturn(dataPath);
        LlmGateway gateway = new LlmGateway(projects);
        ((Map<String, LlmProvider>) ReflectionTestUtils.getField(gateway, "providers")).put("fixture", provider);
        return gateway;
    }

    private MockMvc mvc(LlmGateway gateway) {
        DeAiRewriteConfigStorage configs = mock(DeAiRewriteConfigStorage.class);
        // 本套验证通用 Provider；不依赖产品默认引擎，以兼容默认 local 的实验设置。
        when(configs.getConfig("project")).thenReturn(DeAiRewriteConfig.builder().engine("llm").build());
        PolishService polish = new PolishService(gateway, baseUrl, "unused-local-model",
                "unused-local-model", false, 5);
        EditorAgent editor = new EditorAgent(gateway, null, drafts, null, configs, polish);
        return MockMvcBuilders.standaloneSetup(new DraftController(drafts, editor, null, null, null, null, configs, null, gateway))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String request() throws Exception {
        return mapper.writeValueAsString(Map.of("text", ORIGINAL));
    }

    private void respond(String body) {
        responder = count -> json(body);
    }

    private Reply json(String body) {
        return new Reply(200, "application/json", body);
    }

    private record Reply(int status, String contentType, String body) {}
}
