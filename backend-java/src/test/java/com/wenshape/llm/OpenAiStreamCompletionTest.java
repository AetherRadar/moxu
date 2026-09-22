package com.wenshape.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiStreamCompletionTest {
    private static final String CONTENT = "data: {\"choices\":[{\"delta\":{\"content\":\"Complete text\"}}]}\n\n";

    @Test
    void doneMarkerCompletesWithoutWaitingForProxyToCloseBody() throws Exception {
        CountDownLatch closeBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String body = CONTENT + "data: {\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n";
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try { closeBody.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var result = callers.submit(() -> provider(server).chatOnce(messages(), 0.2, 512))
                        .get(2, TimeUnit.SECONDS);
                assertEquals("Complete text", result.getContent());
                assertEquals("stop", result.getFinishReason());
                assertEquals(17, result.getTotalTokens());
            } finally { closeBody.countDown(); server.stop(0); }
        }
    }

    @Test
    void prematureEofIsNotReportedAsACompleteGeneration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = CONTENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var result = provider(server).chatOnce(messages(), 0.2, 512);
            assertEquals("Complete text", result.getContent());
            assertEquals("incomplete", result.getFinishReason());
        } finally { server.stop(0); }
    }

    private OpenAiProvider provider(HttpServer server) {
        return new OpenAiProvider("fixture-key", "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", 512, 0.2);
    }

    private List<Map<String, String>> messages() {
        return List.of(Map.of("role", "user", "content", "Complete input"));
    }
}
