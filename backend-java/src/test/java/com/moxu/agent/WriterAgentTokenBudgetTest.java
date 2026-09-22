package com.moxu.agent;

import com.moxu.llm.LlmGateway;
import com.moxu.llm.LlmResponse;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.Draft;
import com.moxu.model.entity.SceneBrief;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DraftStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriterAgentTokenBudgetTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_usesScaledStreamBudgetForDraft() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.streamResponse = Flux.just("<draft>正文</draft>");
        NoopDraftStorage draftStorage = new NoopDraftStorage(tempDir);
        WriterAgent agent = new WriterAgent(gateway, new CardStorage(), draftStorage, null);

        SceneBrief brief = SceneBrief.builder()
                .chapter("V1C1")
                .title("开场")
                .goal("写一个开场")
                .build();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sceneBrief", brief);
        context.put("targetWordCount", 600);

        Map<String, Object> result = agent.execute("p", "V1C1", context);

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(2048, gateway.lastStreamMaxTokens);

        context.put("targetWordCount", 3000);
        gateway.lastStreamMaxTokens = -1;
        result = agent.execute("p", "V1C1", context);

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals(6000, gateway.lastStreamMaxTokens);
    }

    @Test
    void generateQuestionsFromGaps_usesSingleShotBudgetAndTrimsLongContext() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.chatOnceResponses.add("[{\"gap_key\":\"V1C2:plot_point:缺口\",\"type\":\"plot_point\",\"text\":\"确认推进点\"}]");
        WriterAgent agent = new WriterAgent(gateway, new CardStorage(), new NoopDraftStorage(tempDir), null);

        String longSummary = "S".repeat(1200);
        String longGap = "G".repeat(1000);
        SceneBrief brief = SceneBrief.builder()
                .chapter("V1C2")
                .title("标题")
                .goal("目标")
                .build();
        List<ChapterSummary> summaries = List.of(summary("V1C1", longSummary));
        List<Map<String, ?>> gaps = List.of(Map.of("kind", "plot_point", "text", longGap, "key", "V1C2:plot_point:缺口"));

        List<Map<String, String>> questions = agent.generateQuestionsFromGaps(
                brief,
                "章节目标" + "C".repeat(1000),
                summaries,
                gaps,
                List.of()
        );

        assertEquals(1, questions.size());
        assertEquals(512, gateway.lastChatOnceMaxTokens);
        assertNotNull(gateway.lastChatOncePrompt);
        assertFalse(gateway.lastChatOncePrompt.contains(longSummary));
        assertFalse(gateway.lastChatOncePrompt.contains(longGap));
        assertTrue(gateway.lastChatOncePrompt.contains("S".repeat(120)));
        assertTrue(gateway.lastChatOncePrompt.contains("G".repeat(120)));
    }

    @Test
    void generateResearchPlan_usesSingleShotBudgetAndTrimsLargeStats() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.chatOnceResponses.add("{\"queries\":[\"关键线索\"],\"note\":\"ok\"}");
        WriterAgent agent = new WriterAgent(gateway, new CardStorage(), new NoopDraftStorage(tempDir), null);

        String longStats = "X".repeat(2000);
        Map<String, Object> evidenceStats = new LinkedHashMap<>();
        evidenceStats.put("detail", longStats);

        Map<String, Object> plan = agent.generateResearchPlan(
                "章节目标" + "Y".repeat(800),
                List.of(Map.of("text", "缺口" + "G".repeat(900))),
                evidenceStats,
                2
        );

        assertEquals(List.of("关键线索"), plan.get("queries"));
        assertEquals(256, gateway.lastChatOnceMaxTokens);
        assertNotNull(gateway.lastChatOncePrompt);
        assertFalse(gateway.lastChatOncePrompt.contains(longStats));
        assertTrue(gateway.lastChatOncePrompt.contains("X".repeat(120)));
    }

    private ChapterSummary summary(String chapter, String briefSummary) {
        ChapterSummary summary = ChapterSummary.builder()
                .chapter(chapter)
                .title("标题")
                .briefSummary(briefSummary)
                .volumeId("V1")
                .wordCount(briefSummary.length())
                .build();
        summary.setKeyEvents(List.of());
        summary.setNewFacts(List.of());
        summary.setCharacterStateChanges(List.of());
        summary.setOpenLoops(List.of());
        return summary;
    }

    private static final class RecordingGateway extends LlmGateway {
        private final Deque<String> chatOnceResponses = new ArrayDeque<>();
        private Flux<String> streamResponse = Flux.just("<draft>正文</draft>");
        private List<Map<String, String>> lastChatOnceMessages = List.of();
        private String lastChatOncePrompt = "";
        private Integer lastChatOnceMaxTokens = null;
        private List<Map<String, String>> lastStreamMessages = List.of();
        private Integer lastStreamMaxTokens = null;

        private RecordingGateway() {
            super(null);
        }

        @Override
        public String getProviderForAgent(String agentName) {
            return "mock";
        }

        @Override
        public LlmResponse chatOnce(List<Map<String, String>> messages, String providerId,
                                    Double temperature, Integer maxTokens) {
            this.lastChatOnceMessages = copyMessages(messages);
            this.lastChatOncePrompt = joinPrompt(messages);
            this.lastChatOnceMaxTokens = maxTokens;
            String content = chatOnceResponses.isEmpty() ? "{\"queries\":[],\"note\":\"empty\"}" : chatOnceResponses.removeFirst();
            return LlmResponse.builder()
                    .content(content)
                    .provider("mock")
                    .model("mock")
                    .elapsedMs(1L)
                    .totalTokens(1)
                    .build();
        }

        @Override
        public LlmResponse chat(List<Map<String, String>> messages, String providerId,
                                Double temperature, Integer maxTokens) {
            return chatOnce(messages, providerId, temperature, maxTokens);
        }

        @Override
        public Flux<String> streamChat(List<Map<String, String>> messages, String providerId,
                                       Double temperature, Integer maxTokens) {
            this.lastStreamMessages = copyMessages(messages);
            this.lastStreamMaxTokens = maxTokens;
            return streamResponse;
        }

        private List<Map<String, String>> copyMessages(List<Map<String, String>> messages) {
            if (messages == null || messages.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(messages);
        }

        private String joinPrompt(List<Map<String, String>> messages) {
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map<String, String> message : messages) {
                if (message == null) {
                    continue;
                }
                String content = String.valueOf(message.getOrDefault("content", ""));
                if (!content.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(content);
                }
            }
            return sb.toString();
        }
    }

    private static final class NoopDraftStorage extends DraftStorage {
        private final Path root;

        private NoopDraftStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public Optional<SceneBrief> getSceneBrief(String projectId, String chapter) {
            return Optional.empty();
        }

        @Override
        public Draft saveCurrentDraft(String projectId, String chapter, String content, Integer wordCount,
                                      List<String> pendingConfirmations, boolean createPrevBackup) {
            return Draft.builder()
                    .chapter(chapter)
                    .version("current")
                    .content(content)
                    .wordCount(wordCount != null ? wordCount : content.length())
                    .build();
        }
    }
}
