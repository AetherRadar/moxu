package com.moxu.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.moxu.storage.ProjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LlmGatewayRetryTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong();
    private final List<Long> waits = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private LlmGateway gateway;
    private LlmRetryCoordinator coordinator;
    private int firstStatus = 200;
    private String retryAfter;
    private boolean disconnect;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        coordinator = new LlmRetryCoordinator(clock::get, millis -> { waits.add(millis); clock.addAndGet(millis); });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int count = requests.incrementAndGet();
            if (count == 1 && disconnect) { exchange.close(); return; }
            int status = count == 1 || firstStatus == 404 ? firstStatus : 200;
            String payload = status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"Complete original-quality result\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"error\":{\"code\":\"gateway_concurrency_limit\",\"message\":\"private-provider-response\"}}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        gateway = new LlmGateway(mock(ProjectStorage.class));
        ((Map<String, LlmProvider>) ReflectionTestUtils.getField(gateway, "providers")).put("fixture",
                new OpenAiProvider("fixture-key", "http://127.0.0.1:" + server.getAddress().getPort(), "original-model", 6000, 0.7));
        ReflectionTestUtils.setField(gateway, "retries", coordinator);
    }

    @AfterEach
    void stop() { server.stop(0); }

    private LlmResponse generate() {
        return gateway.chat(List.of(Map.of("role", "user", "content", "Full source material")), "fixture", 0.7, 6000);
    }

    @Test
    void respectsProviderRetryAfterAndPreservesModelInputAndBudget() throws Exception {
        firstStatus = 429; retryAfter = "12";
        assertEquals("Complete original-quality result", generate().getContent());
        assertEquals(2, requests.get());
        assertTrue(waits.getFirst() >= 12_000);
        assertEquals(bodies.getFirst(), bodies.getLast());
        var request = mapper.readTree(bodies.getLast());
        assertEquals("original-model", request.path("model").asText());
        assertEquals(6000, request.path("max_tokens").asInt());
        assertEquals(0.7, request.path("temperature").asDouble());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void permanentHttpFailuresDoNotRepeatGeneration(int status) {
        firstStatus = status;
        var failure = assertThrows(RuntimeException.class, this::generate);
        assertInstanceOf(LlmHttpException.class, failure.getCause());
        assertEquals(status == 404 ? 2 : 1, requests.get(), "404 may try the alternate API path, but never another gateway attempt");
        assertTrue(waits.isEmpty());
        assertFalse(failure.getCause().getMessage().contains("private-provider-response"));
    }

    @Test
    void connectionDropDoesNotImmediatelyDuplicateTheGeneration() {
        disconnect = true;
        assertEquals("Complete original-quality result", generate().getContent());
        assertEquals(2, requests.get());
        assertTrue(waits.getFirst() >= 15_000);
    }

    @Test
    void http2ResetAndShutdownUseTheLongCooldownWithoutExposingErrorText() {
        for (var failure : List.of(new okhttp3.internal.http2.StreamResetException(okhttp3.internal.http2.ErrorCode.CANCEL),
                new okhttp3.internal.http2.ConnectionShutdownException(), new java.io.IOException("private-proxy-details"))) {
            var first = LlmRetryCoordinator.decide(new RuntimeException("wrapped", failure), 0);
            assertTrue(first.retry());
            assertEquals(15_000, first.delayMillis());
            assertEquals(30_000, LlmRetryCoordinator.decide(failure, 1).delayMillis());
            assertFalse(first.reason().contains("private-proxy-details"));
        }
    }

    @Test
    void sharedCooldownStaggersSameProviderButDoesNotBlockOthers() throws Exception {
        coordinator.defer("fixture", 15_000);
        assertTrue(coordinator.awaitTurn("other-provider", 100));
        assertEquals(0, clock.get());
        assertTrue(coordinator.awaitTurn("fixture", 20_000));
        assertEquals(15_000, clock.get());
        assertTrue(coordinator.awaitTurn("fixture", 20_000));
        assertEquals(16_000, clock.get());
        coordinator.defer("fixture", 90_000);
        assertFalse(coordinator.awaitTurn("fixture", 30_000));
        assertEquals(16_000, clock.get(), "A retry must not ignore an upstream delay longer than its wait budget");
    }

    @Test
    void honorsHttpDateAndDoesNotRetainSensitiveUpstreamBody() {
        var now = Instant.parse("2026-09-22T00:00:00Z");
        assertEquals(20_000, LlmHttpException.parseRetryAfter("Tue, 22 Sep 2026 00:00:20 GMT", now));
        assertEquals(0, LlmHttpException.parseRetryAfter("invalid", now));
        assertEquals(0, LlmHttpException.parseRetryAfter("-1", now));
        var error = LlmHttpException.from(429, "45", "{\"error\":{\"code\":\"gateway_concurrency_limit\",\"private\":\"secret\"}}");
        assertTrue(error.getMessage().contains("gateway_concurrency_limit"));
        assertFalse(error.getMessage().contains("secret"));
        assertEquals(45_000, LlmRetryCoordinator.decide(error, 0).delayMillis());
    }

    @Test
    void interruptedCooldownDoesNotIssueAnotherModelCall() {
        firstStatus = 429;
        ReflectionTestUtils.setField(gateway, "retries", new LlmRetryCoordinator(clock::get, millis -> { throw new InterruptedException(); }));
        try {
            assertThrows(RuntimeException.class, this::generate);
            assertEquals(1, requests.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
