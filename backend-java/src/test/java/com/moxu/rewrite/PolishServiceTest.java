package com.moxu.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.DeAiRewriteConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolishServiceTest {
    private static final String ORIGINAL = "他付了120元，买了三本书，然后沿着河岸慢慢走回家。";
    private static final String CANDIDATE = "他花了120元，买了三本书，随后顺着河岸缓缓走回家。";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hold;
    private HttpServer server;
    private ExecutorService workers;
    private PolishService service;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/api/chat", exchange -> {
            requests.incrementAndGet();
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            entered.countDown();
            try {
                if (hold) {
                    release.await(5, TimeUnit.SECONDS);
                }
                byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        reply.set(localReply(CANDIDATE, "stop"));
        service = createService(2);
        when(gateway.getProviderForAgent("writer")).thenReturn("writer-profile");
        answer(CANDIDATE, "stop");
    }

    @AfterEach
    void cleanup() {
        release.countDown();
        server.stop(0);
        workers.shutdownNow();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \n\t", "　", "\u00a0"})
    void rejectsBlankBeforeCallingAnyBackend(String text) {
        assertStatus(400, () -> service.polish(text, config("local")));
        assertEquals(0, requests.get());
        verifyNoInteractions(gateway);
    }

    @Test
    void inputLimitCountsEntireInputAndAcceptsExactly3000Characters() throws Exception {
        String exact = "雨".repeat(3000);
        reply.set(localReply(exact, "stop"));
        assertEquals(exact, service.polish(exact, config("local")).rewritten());
        assertEquals(4096, requestBody.get().path("options").path("num_predict").asInt());
        assertStatus(422, () -> service.polish(exact + " ", config("local")));
        assertStatus(422, () -> service.polish(exact + "雨", config("llm")));
        assertEquals(1, requests.get());
        verifyNoInteractions(gateway);
    }

    @Test
    void localSendsOneBoundedNonThinkingRequestAndReturnsOnlyCandidateMetadata() {
        PolishResult result = service.polish(ORIGINAL, config("local"));
        assertEquals(CANDIDATE, result.rewritten());
        assertEquals("local", result.engine());
        assertTrue(result.changed());
        assertEquals("candidate", result.debug().get("status"));
        assertEquals("moxu-polish:qwen3-1.7b", result.debug().get("model"));
        assertEquals(ORIGINAL.length(), result.debug().get("originalChars"));
        assertEquals(CANDIDATE.length(), result.debug().get("outputChars"));
        assertTrue(((Long) result.debug().get("elapsedMs")) >= 0);
        JsonNode body = requestBody.get();
        assertEquals("moxu-polish:qwen3-1.7b", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean(true));
        assertFalse(body.path("think").asBoolean(true));
        assertEquals("把这段去AI味，只改这一段，不许往后续写：\n" + ORIGINAL,
                body.path("messages").get(1).path("content").asText());
        JsonNode options = body.path("options");
        assertEquals(0.2, options.path("temperature").asDouble());
        assertEquals(0.9, options.path("top_p").asDouble());
        assertEquals(1.08, options.path("repeat_penalty").asDouble());
        assertEquals(8192, options.path("num_ctx").asInt());
        assertTrue(options.path("num_predict").asInt() > 0 && options.path("num_predict").asInt() <= 4096);
        assertEquals(1, requests.get());
        verifyNoInteractions(gateway);
    }

    @Test
    void defaultsAndLegacyFlagsUseOneLocalCallAndNeverUseGenericModel() {
        DeAiRewriteConfig legacy = config("hybrid");
        legacy.setMaxPasses(4);
        legacy.setBeamWidth(8);
        legacy.setHotspotLocalEnabled(true);
        legacy.setExtraModelPassEnabled(true);
        legacy.setUseProjectCorpus(true);
        PolishResult result = service.polish(ORIGINAL, legacy);
        assertEquals("local", result.engine());
        assertEquals(CANDIDATE, result.rewritten());
        assertEquals("moxu-polish:qwen3-1.7b", result.debug().get("model"));
        verifyNoInteractions(gateway);
        assertEquals(1, requests.get());
    }

    @Test
    void unchangedOutputIsStillCandidateAndClosedThinkingIsExcludedFromGuards() {
        answer("<think>考虑999种写法</think>" + ORIGINAL, "stop");
        PolishResult result = service.polish(ORIGINAL, config("llm"));
        assertEquals(ORIGINAL, result.rewritten());
        assertFalse(result.changed());
        assertEquals("candidate", result.debug().get("status"));
        assertTrue((Integer) result.debug().get("rawOutputChars") > (Integer) result.debug().get("outputChars"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"　 ", "<think>还在思考", "<THINK>正文", "<think>思考</think>",
            "正文</think>", "<think><think>嵌套</think>", "<think", "<think>思考</think", ""})
    void rejectsEmptyOrIncompleteThinkingForBothEngines(String output) throws Exception {
        answer(output, "stop");
        assertStatus(422, () -> service.polish(ORIGINAL, config("llm")));
        reply.set(localReply(output, "stop"));
        assertStatus(422, () -> service.polish(ORIGINAL, config("local")));
        assertEquals(1, requests.get());
        verify(gateway, times(1)).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "max_tokens", "max_output_tokens", "incomplete", "LENGTH"})
    void rejectsReportedTruncationEvenWhenTextLooksComplete(String reason) throws Exception {
        answer(ORIGINAL, reason);
        assertStatus(422, () -> service.polish(ORIGINAL, config("llm")));
        reply.set(localReply(ORIGINAL, reason));
        assertStatus(422, () -> service.polish(ORIGINAL, config("local")));
    }

    @Test
    void rejectsIncompleteLocalEnvelope() {
        reply.set("{\"done\":false,\"message\":{\"content\":\"正文\"}}");
        assertStatus(422, () -> service.polish(ORIGINAL, config("local")));
    }

    @ParameterizedTest
    @CsvSource({"74,false", "75,true", "135,true", "136,false"})
    void lengthGuardEnforcesInclusiveBoundariesForBothEngines(int length, boolean accepted) throws Exception {
        String original = "雨".repeat(100);
        String output = "风".repeat(length);
        answer(output, "stop");
        reply.set(localReply(output, "stop"));
        for (String engine : List.of("llm", "local")) {
            if (accepted) {
                assertEquals(output, service.polish(original, config(engine)).rewritten());
            } else {
                PolishResult result = service.polish(original, config(engine));
                assertEquals(original, result.rewritten());
                assertEquals(true, result.debug().get("preservedOriginal"));
            }
        }
    }

    @Test
    void whitespaceCannotPadAnOtherwiseTooShortResult() {
        answer("雨".repeat(10) + "　 ".repeat(50), "stop");
        PolishResult result = service.polish("雨".repeat(100), config("llm"));
        assertEquals("雨".repeat(100), result.rewritten());
        assertEquals(true, result.debug().get("preservedOriginal"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "120|121", "120|一百二十", "三|四", "三|3", "三|", "|三", "三|三三",
            "壹佰贰拾|壹佰叁拾", "兩|两", "廿|卅", "1.25|125", "1,200|1200",
            "-12|12", "负三|三", "１２|12", "25%|25", "三分之一|三分之二", "二〇二六|二〇二五"
    })
    void changedAddedMissingOrReformattedNumbersNeverEscapeAsWrongFacts(String before, String after) throws Exception {
        String original = "他记下了" + (before == null ? "" : before) + "，然后合上手中的册子安静地走回家。";
        String output = "他记下了" + (after == null ? "" : after) + "，然后合上手中的册子安静地走回家。";
        answer(output, "stop");
        reply.set(localReply(output, "stop"));
        assertEquals(original, service.polish(original, config("llm")).rewritten());
        assertEquals(original, service.polish(original, config("local")).rewritten());
    }

    @Test
    void numberOrderAndMultiplicityArePreserved() {
        String original = "甲有12元，乙有34元，他们各自收好钱走出了房门。";
        answer(original.replace("12元", "34元").replace("乙有34", "乙有12"), "stop");
        assertEquals(original, service.polish(original, config("llm")).rewritten());
        answer("甲有12元，乙有34元，他们收好12元走出了房门。", "stop");
        assertEquals(original, service.polish(original, config("llm")).rewritten());
    }

    @Test
    void preservesMixedLiteralNumbersInAcceptedOutput() {
        String original = "二〇二六年，他记下12.5%、-3、壹佰贰拾、三分之一和1,200，然后离开。";
        answer(original.replace("记下", "写下"), "stop");
        assertEquals(original.replace("记下", "写下"), service.polish(original, config("llm")).rewritten());
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 429, 500, 503, 504})
    void localHttpFailureIs503WithoutBodyOrFallback(int backendStatus) {
        status.set(backendStatus);
        reply.set("secret-token /private/path prompt contents");
        ResponseStatusException error = assertStatus(503, () -> service.polish(ORIGINAL, config("local")));
        assertFalse(error.getReason().contains("secret-token"));
        assertNull(error.getCause());
        assertEquals(1, requests.get());
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json secret-token", "null", "[]", "{\"error\":\"secret-token\"}"})
    void localMalformedResponseIs503AndDoesNotLeakDetails(String body) {
        reply.set(body);
        assertFalse(assertStatus(503, () -> service.polish(ORIGINAL, config("local")))
                .getReason().contains("secret-token"));
    }

    @Test
    void localTimeoutIs504WithoutRetryAndReleasesSlot() throws Exception {
        service = createService(1);
        hold = true;
        assertStatus(504, () -> service.polish(ORIGINAL, config("local")));
        assertEquals(1, requests.get());
        hold = false;
        release.countDown();
        assertEquals(CANDIDATE, service.polish(ORIGINAL, config("local")).rewritten());
        assertEquals(2, requests.get());
        verifyNoInteractions(gateway);
    }

    @Test
    void concurrentLocalRequestIsRejectedImmediatelyAcrossServiceInstances() throws Exception {
        hold = true;
        Future<PolishResult> first = workers.submit(() -> service.polish(ORIGINAL, config("local")));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        PolishService otherInstance = createService(2);
        assertStatus(429, () -> otherInstance.polish(ORIGINAL, config("local")));
        assertEquals(1, requests.get());
        release.countDown();
        assertEquals(CANDIDATE, first.get(2, TimeUnit.SECONDS).rewritten());
        assertEquals(CANDIDATE, otherInstance.polish(ORIGINAL, config("local")).rewritten());
    }

    @Test
    void validationAndBackendFailuresReleaseLocalSlot() throws Exception {
        reply.set(localReply("", "stop"));
        assertStatus(422, () -> service.polish(ORIGINAL, config("local")));
        status.set(503);
        assertStatus(503, () -> service.polish(ORIGINAL, config("local")));
        status.set(200);
        reply.set(localReply(CANDIDATE, "stop"));
        assertEquals(CANDIDATE, service.polish(ORIGINAL, config("local")).rewritten());
    }

    @Test
    void genericTimeoutHasDeadlineAndNoRetry() {
        service = createService(1);
        when(gateway.chatOnce(anyList(), anyString(), anyDouble(), anyInt())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return LlmResponse.builder().content(CANDIDATE).build();
        });
        assertStatus(504, () -> service.polish(ORIGINAL, config("llm")));
        verify(gateway, times(1)).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
        assertEquals(0, requests.get());
    }

    @Test
    void nestedGenericTimeoutAndBackendFailuresAreSanitized() {
        when(gateway.chatOnce(anyList(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("secret-token", new SocketTimeoutException("secret-token")));
        assertFalse(assertStatus(504, () -> service.polish(ORIGINAL, config("llm"))).getReason().contains("secret-token"));
        doThrow(new IllegalStateException("secret-token"))
                .when(gateway).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
        assertFalse(assertStatus(503, () -> service.polish(ORIGINAL, config("llm"))).getReason().contains("secret-token"));
        assertEquals(0, requests.get());
    }

    @Test
    void refusesGatewayMockFallback() {
        when(gateway.getProviderForAgent("writer")).thenReturn("mock");
        assertStatus(503, () -> service.polish(ORIGINAL, config("llm")));
        verify(gateway, never()).chatOnce(anyList(), anyString(), anyDouble(), anyInt());
    }

    private void answer(String content, String reason) {
        when(gateway.chatOnce(anyList(), anyString(), anyDouble(), anyInt()))
                .thenReturn(LlmResponse.builder().content(content).model("writer-model").finishReason(reason).build());
    }

    private String localReply(String text, String reason) throws Exception {
        return mapper.writeValueAsString(Map.of("done", true, "done_reason", reason,
                "message", Map.of("role", "assistant", "content", text == null ? "" : text)));
    }

    private PolishService createService(long timeoutSeconds) {
        return new PolishService(gateway, "http://127.0.0.1:" + server.getAddress().getPort(),
                "moxu-polish:qwen3-1.7b", "moxu-polish:qwen3-1.7b", false, timeoutSeconds);
    }

    private DeAiRewriteConfig config(String engine) {
        return DeAiRewriteConfig.builder().engine(engine).build();
    }

    private ResponseStatusException assertStatus(int status, Runnable call) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(status, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("原文未修改"));
        return exception;
    }
}
