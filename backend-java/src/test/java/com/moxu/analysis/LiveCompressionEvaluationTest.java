package com.moxu.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.agent.ArchivistAgent;
import com.moxu.agent.WriterAgent;
import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.llm.LlmHttpException;
import com.moxu.model.entity.SceneBrief;
import com.moxu.orchestrator.Orchestrator;
import com.moxu.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only: real configured provider, isolated manuscript, no archive writes. */
@EnabledIfEnvironmentVariable(named = "MOXU_LIVE_EVAL", matches = "true")
class LiveCompressionEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @TempDir Path scratch;
    private ch.qos.logback.classic.Level previousLogLevel;

    @BeforeEach void suppressProviderDiagnostics() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.moxu.llm");
        previousLogLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.OFF);
    }

    @AfterEach void restoreProviderDiagnostics() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.moxu.llm"))
                .setLevel(previousLogLevel);
    }

    private <T extends BaseStorage> T storage(T instance, Path directory) {
        ReflectionTestUtils.setField(instance, "dataDir", directory.toString());
        return instance;
    }

    private static void json(Path path, Object value) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static Map<String, String> hashes(Path data, Path source) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path path : List.of(source, data.resolve("llm_profiles.json"), data.resolve("agent_assignments.json"))) {
            result.put(path.getFileName().toString(), AnalysisStorage.hash(Files.readString(path)));
        }
        return result;
    }

    private static Map<String, Object> evidence(String type, String text) {
        return Map.of("type", type, "text", text, "source", Map.of("chapter", "V1C1"), "score", 1.0);
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, v) -> copy.put(String.valueOf(key), v));
                result.add(copy);
            }
        }
        return result;
    }

    private static final class RecordingGateway extends LlmGateway {
        private final Path output;
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private String stage = "extraction";

        RecordingGateway(ProjectStorage storage, Path output) {
            super(storage);
            this.output = output;
        }

        @Override public LlmResponse chatOnce(List<Map<String, String>> messages, String provider,
                                              Double temperature, Integer maxTokens) {
            if (calls.size() >= 5 || provider == null || provider.equals("mock")) {
                throw new IllegalStateException("Live call guard");
            }
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("stage", stage);
            metric.put("prompt_characters", messages.stream().mapToInt(m -> m.get("content").length()).sum());
            metric.put("prompt_utf8_bytes", messages.stream()
                    .mapToInt(m -> m.get("content").getBytes(StandardCharsets.UTF_8).length).sum());
            metric.put("max_output_tokens", maxTokens);
            metric.put("status", "started");
            calls.add(metric);
            json(output.resolve(stage + "-messages.json"), messages);
            json(output.resolve("calls.json"), calls);
            long start = System.nanoTime();
            try {
                LlmResponse response = super.chatOnce(messages, provider, temperature, maxTokens);
                metric.put("status", "complete");
                metric.put("prompt_tokens", response.getPromptTokens());
                metric.put("completion_tokens", response.getCompletionTokens());
                metric.put("total_tokens", response.getTotalTokens());
                metric.put("usage_reported", response.getTotalTokens() > 0 || response.getPromptTokens() > 0);
                metric.put("finish_reason", Objects.toString(response.getFinishReason(), ""));
                metric.put("response_model", Objects.toString(response.getModel(), ""));
                Files.writeString(output.resolve(stage + "-raw.txt"), response.getContent());
                return response;
            } catch (Exception e) {
                metric.put("status", "failed");
                metric.put("error_type", e.getClass().getSimpleName());
                if (e instanceof LlmHttpException http) {
                    metric.put("http_status", http.getStatusCode());
                    metric.put("retry_after_ms", http.getRetryAfterMillis());
                }
                // Transport exceptions may contain provider URLs; persist only the type.
                throw new IllegalStateException("Live evaluation stopped: " + e.getClass().getSimpleName());
            } finally {
                metric.put("wall_ms", (System.nanoTime() - start) / 1_000_000);
                json(output.resolve("calls.json"), calls);
            }
        }
    }

    @Test void compareFullSourceWithProductionWorkingMemory() throws Exception {
        Path data = Path.of(required("MOXU_LIVE_DATA")).toRealPath();
        String project = required("MOXU_LIVE_PROJECT");
        Path sourcePath = data.resolve(project).resolve("drafts/V1C1/final.md").normalize();
        assertTrue(sourcePath.startsWith(data));
        Path outputRoot = Path.of("target/live-evaluation").toAbsolutePath();
        Path output = Files.createDirectories(outputRoot.resolve(
                Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8)));
        System.out.println("Live evaluation artifacts: " + output);
        Map<String, String> before = hashes(data, sourcePath);
        json(output.resolve("original-hashes.json"), before);
        String source = Files.readString(sourcePath);
        Files.writeString(output.resolve("source.md"), source);
        Files.writeString(output.resolve("digest-diagnostic.txt"), ChapterDigest.extract(source, 1200));

        var gateway = new RecordingGateway(storage(new ProjectStorage(), data), output);
        ReflectionTestUtils.setField(gateway, "openaiMaxTokens", 3000);
        ReflectionTestUtils.setField(gateway, "openaiTemperature", 0.0);
        gateway.init();
        String provider = gateway.getProviderForAgent("writer");
        assertNotEquals("mock", provider);
        assertEquals(provider, gateway.getProviderForAgent("archivist"), "Use the same model for this comparison");
        var cards = storage(new CardStorage(), scratch);
        var drafts = storage(new DraftStorage(), scratch);
        var canon = storage(new CanonStorage(), scratch);
        Path copiedSource = scratch.resolve(project).resolve("drafts/V1C1/final.md");
        Files.createDirectories(copiedSource.getParent());
        Files.writeString(copiedSource, source);
        var writer = new WriterAgent(gateway, cards, drafts, null);
        var archivist = new ArchivistAgent(gateway, cards, drafts, null, canon);
        var orchestrator = new Orchestrator(gateway, cards, drafts, canon,
                storage(new MemoryPackStorage(), scratch), writer, archivist, null, null, null, null, null);
        List<Map<String, Object>> ledger = new ArrayList<>();
        int priorCalls = Integer.parseInt(System.getenv().getOrDefault("MOXU_LIVE_PRIOR_CALLS", "0"));
        long priorTokens = Long.parseLong(System.getenv().getOrDefault("MOXU_LIVE_PRIOR_TOKENS", "0"));
        int callLimit = Integer.parseInt(System.getenv().getOrDefault("MOXU_LIVE_CALL_LIMIT", "5"));
        assertTrue(callLimit >= 1 && callLimit <= 5 && priorCalls >= 0 && priorCalls < callLimit
                && priorTokens >= 0 && priorTokens < 100000);
        var account = new AnalysisContext.Account(UUID.randomUUID().toString(), 100000, 20, callLimit - priorCalls, priorTokens,
                event -> { ledger.add(event); json(output.resolve("ledger.json"), ledger); });
        try (var scope = new AnalysisContext(account, "live", "comparison", new AnalysisProperties())) {
            var extracted = AnalysisContext.stage("extraction",
                    () -> archivist.analyzeCombined(project, "V1C1", "", source, false, 3000));
            json(output.resolve("extracted.json"), extracted);
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (var fact : maps(extracted.canon().get("facts"))) {
                evidence.add(evidence("fact", String.valueOf(fact.getOrDefault("statement", ""))));
            }
            evidence.add(evidence("chapter_summary", extracted.summary().getBriefSummary()));
            String goal = "\u7d27\u63a5\u4e0a\u4e00\u7ae0\u7ed3\u5c3e\uff0c\u5199\u9648\u6714\u7684\u8c28\u614e\u89c2\u5bdf\u4e0e\u8131\u8eab\u5c1d\u8bd5\uff0c\u4fdd\u6301\u6821\u56ed\u60ac\u7591\u98ce\u683c\uff0c\u4e0d\u63d0\u524d\u89e3\u91ca\u672a\u77e5\u73b0\u8c61\u7684\u771f\u76f8\u3002";
            var chunks = drafts.searchTextChunks(project, goal, 6, List.of("V1C1"), List.of(), false);
            for (var chunk : chunks) evidence.add(evidence("text_chunk", String.valueOf(chunk.get("text"))));
            var brief = SceneBrief.builder().chapter("V1C2").title("\u7eed\u7ae0\u5b9e\u6d4b").goal(goal)
                    .characters(List.of(Map.of("name", "\u9648\u6714"))).build();
            String memory = ReflectionTestUtils.invokeMethod(orchestrator, "buildWorkingMemory",
                    goal, brief, evidence, List.of(), Set.of(), Set.of());
            assertNotNull(memory);
            Files.writeString(output.resolve("working-memory.txt"), memory);
            json(output.resolve("evidence.json"), evidence);
            Map<String, Object> shared = Map.of("chapter_goal", goal,
                    "previous_summaries", List.of(extracted.summary().getBriefSummary()));
            List<Map<String, String>> reference = ReflectionTestUtils.invokeMethod(
                    writer, "buildDraftMessages", brief, 800, shared);
            assertNotNull(reference);
            reference = new ArrayList<>(reference);
            Map<String, String> context = reference.get(1);
            reference.set(1, Map.of("role", context.get("role"), "content",
                    context.get("content").replace("<<<CONTEXT_END>>>",
                            "\nFull previous chapter (reference arm):\n" + source + "\n<<<CONTEXT_END>>>")));
            Map<String, Object> compressed = new HashMap<>(shared);
            compressed.put("working_memory", memory);
            compressed.put("project_id", project);
            List<Map<String, String>> candidate = ReflectionTestUtils.invokeMethod(
                    writer, "buildDraftMessages", brief, 800, compressed);
            String ending = DraftStorage.endingExcerpt(source, 2000);
            Files.writeString(output.resolve("protected-ending.txt"), ending);
            Files.writeString(output.resolve("protected-anchors.txt"),
                    com.moxu.quality.ContinuityProtection.anchors(source, ending, 600));
            generate(gateway, writer, provider, "full-source", reference, output, source);
            generate(gateway, writer, provider, "compressed", candidate, output, source);
        } finally {
            json(output.resolve("budget.json"), account.metrics());
            Map<String, String> after = hashes(data, sourcePath);
            json(output.resolve("original-hashes-after.json"), after);
            assertEquals(before, after, "Original manuscript and provider configuration must be unchanged");
        }
    }

    private static void generate(RecordingGateway gateway, WriterAgent writer, String provider, String stage,
                                 List<Map<String, String>> messages, Path output, String source) throws IOException {
        gateway.stage = stage;
        LlmResponse result = AnalysisContext.stage(stage, () -> gateway.chatOnce(messages, provider, 0.0, 3000));
        assertFalse(result.getContent().isBlank(), "Provider returned no final content");
        assertNotEquals("length", result.getFinishReason(), "Output truncated; do not claim a quality pass");
        String content = ReflectionTestUtils.invokeMethod(writer, "extractDraftContent", result.getContent());
        content = ReflectionTestUtils.invokeMethod(writer, "postProcessDraftContent", content);
        assertNotNull(content);
        Files.writeString(output.resolve(stage + "-draft.md"), content);
        json(output.resolve(stage + "-continuity-review.json"),
                com.moxu.quality.ContinuityProtection.review("live", "V1C2", "V1C1", source, content));
    }
}
