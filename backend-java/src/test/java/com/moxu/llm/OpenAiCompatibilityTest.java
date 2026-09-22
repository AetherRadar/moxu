package com.moxu.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.moxu.storage.ProjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiCompatibilityTest {
    @TempDir Path root;

    @Test
    void configuredCompatibilityUsesOneNonStreamingRequestThroughGateway() throws Exception {
        var mapper = new ObjectMapper();
        var calls = new AtomicInteger();
        var streaming = new java.util.concurrent.atomic.AtomicBoolean(true);
        var temperatureSent = new java.util.concurrent.atomic.AtomicBoolean(true);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            var request = mapper.readTree(exchange.getRequestBody());
            streaming.set(request.path("stream").asBoolean());
            temperatureSent.set(request.has("temperature"));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"完整结果\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            mapper.writeValue(root.resolve("llm_profiles.json").toFile(), List.of(Map.of(
                "id", "compat", "provider", "custom", "api_key", "fixture-key", "model", "fixture",
                "base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "compatibility_mode", true)));
            mapper.writeValue(root.resolve("agent_assignments.json").toFile(), Map.of("writer", "compat"));
            var gateway = new LlmGateway(new ProjectStorage() { @Override public Path getDataPath() { return root; } });
            gateway.init();
            var response = gateway.chatOnce(List.of(Map.of("role", "user", "content", "测试")), gateway.getProviderForAgent("writer"), 0.2, 128);
            assertEquals("完整结果", response.getContent());
            assertFalse(streaming.get());
            assertFalse(temperatureSent.get());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
