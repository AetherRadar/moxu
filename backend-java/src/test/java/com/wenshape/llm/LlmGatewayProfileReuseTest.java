package com.wenshape.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.storage.ProjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LlmGatewayProfileReuseTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private LlmGateway gateway;

    @BeforeEach
    void setup() throws Exception {
        gateway = new LlmGateway(new ProjectStorage() {
            @Override public Path getDataPath() { return root; }
        });
        profiles("fixture-key", "fixture-model");
        mapper.writeValue(root.resolve("agent_assignments.json").toFile(), Map.of("writer", "fixture"));
        gateway.init();
    }

    @Test
    void reusesClientsAcrossConcurrentAgentLookups() throws Exception {
        Object original = provider();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 20).mapToObj(i -> callers.submit(() -> {
                assertEquals("fixture", gateway.getProviderForAgent("writer"));
                return provider();
            })).toList();
            for (var future : futures) assertSame(original, future.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void profileEditsReplaceClientAndRemovedCredentialsCannotReuseOldClient() throws Exception {
        Object original = provider();
        profiles("rotated-fixture-key", "new-model");
        gateway.getProviderForAgent("writer");
        Object updated = provider();
        assertNotSame(original, updated);
        assertEquals("new-model", ReflectionTestUtils.getField(updated, "model"));
        assertEquals("rotated-fixture-key", ReflectionTestUtils.getField(updated, "apiKey"));
        gateway.getProviderForAgent("writer");
        assertSame(updated, provider());
        profiles("", "new-model");
        assertEquals("mock", gateway.getProviderForAgent("writer"));
        assertThrows(IllegalArgumentException.class, this::provider);
    }

    @Test
    void removedProfilesAreEvictedAndStatsAreSnapshots() throws Exception {
        Map<String, Object> stats = gateway.getStats();
        mapper.writeValue(root.resolve("llm_profiles.json").toFile(), List.of());
        assertEquals("mock", gateway.getProviderForAgent("writer"));
        assertThrows(IllegalArgumentException.class, this::provider);
        assertEquals(List.of("fixture"), stats.get("providers"));
        assertEquals(List.of("mock"), gateway.getStats().get("providers"));
    }

    private Object provider() {
        return ReflectionTestUtils.invokeMethod(gateway, "getProvider", "fixture");
    }

    private void profiles(String key, String model) throws Exception {
        mapper.writeValue(root.resolve("llm_profiles.json").toFile(), List.of(Map.of(
                "id", "fixture", "provider", "openai", "api_key", key,
                "base_url", "http://127.0.0.1:1/v1", "model", model,
                "temperature", 0.7, "max_tokens", 4096)));
    }
}
