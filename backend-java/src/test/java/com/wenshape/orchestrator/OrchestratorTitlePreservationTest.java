package com.wenshape.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.agent.ArchivistAgent;
import com.wenshape.agent.EditorAgent;
import com.wenshape.agent.WriterAgent;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DeAiRewriteConfigStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import com.wenshape.websocket.SessionWebSocketHandler;
import com.wenshape.websocket.TraceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorTitlePreservationTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeChapterKeepsExistingTitleWhenGeneratedSummaryFallsBackToChapterId() {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        draftStorage.existingSummaries.put(
                "V1C1",
                ChapterSummary.builder().chapter("V1C1").title("山雨欲来").build()
        );
        TestArchivistAgent archivistAgent = new TestArchivistAgent(draftStorage, new TestCanonStorage(tempDir));
        archivistAgent.generatedSummary = ChapterSummary.builder()
                .chapter("V1C1")
                .title("V1C1")
                .briefSummary("这是摘要")
                .build();
        Orchestrator orchestrator = orchestrator(draftStorage, archivistAgent);

        Map<String, Object> result = orchestrator.analyzeChapter("p", "V1C1", "正文内容", null);

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) result.get("analysis");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) analysis.get("summary");
        assertEquals("山雨欲来", summary.get("title"));
        assertEquals("山雨欲来", archivistAgent.lastChapterTitle);
    }

    @Test
    void saveAnalysisKeepsExistingTitleWhenIncomingSummaryTitleIsSynthetic() {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        draftStorage.existingSummaries.put(
                "V1C1",
                ChapterSummary.builder().chapter("V1C1").title("山雨欲来").build()
        );
        TestCanonStorage canonStorage = new TestCanonStorage(tempDir);
        Orchestrator orchestrator = orchestrator(draftStorage, new TestArchivistAgent(draftStorage, canonStorage), canonStorage);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", "V1C1");
        summary.put("brief_summary", "这是摘要");
        summary.put("volume_id", "V1");
        summary.put("word_count", 12);
        summary.put("key_events", List.of("事件一"));
        summary.put("new_facts", List.of());
        summary.put("character_state_changes", List.of());
        summary.put("open_loops", List.of());

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", summary);
        analysis.put("facts", List.of());
        analysis.put("timeline_events", List.of());
        analysis.put("character_states", List.of());
        analysis.put("proposals", List.of());

        Map<String, Object> result = orchestrator.saveAnalysis("p", "V1C1", analysis, false, false);

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        assertEquals("山雨欲来", draftStorage.savedSummary.getTitle());
    }

    private Orchestrator orchestrator(TestDraftStorage draftStorage, TestArchivistAgent archivistAgent) {
        return orchestrator(draftStorage, archivistAgent, archivistAgent.testCanonStorage);
    }

    private Orchestrator orchestrator(TestDraftStorage draftStorage,
                                      TestArchivistAgent archivistAgent,
                                      TestCanonStorage canonStorage) {
        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        MemoryPackStorage memoryPackStorage = new TestMemoryPackStorage(tempDir);
        WriterAgent writerAgent = new WriterAgent(new LlmGateway(null), cardStorage, draftStorage, null);
        EditorAgent editorAgent = new EditorAgent(
                new LlmGateway(null),
                cardStorage,
                draftStorage,
                memoryPackStorage,
                new DeAiRewriteConfigStorage(),
                null
        );
        return new Orchestrator(
                new LlmGateway(null),
                cardStorage,
                draftStorage,
                canonStorage,
                memoryPackStorage,
                writerAgent,
                archivistAgent,
                editorAgent,
                new ContextSelectEngine(cardStorage, canonStorage, draftStorage, null),
                new SessionWebSocketHandler(new ObjectMapper()),
                new TraceWebSocketHandler(new ObjectMapper()),
                null
        );
    }

    private static class TestArchivistAgent extends ArchivistAgent {
        private ChapterSummary generatedSummary = ChapterSummary.builder().chapter("V1C1").title("V1C1").build();
        private Map<String, Object> canonUpdates = Map.of(
                "facts", List.of(),
                "timeline_events", List.of(),
                "character_states", List.of()
        );
        private String lastChapterTitle = null;
        private final TestCanonStorage testCanonStorage;

        private TestArchivistAgent(DraftStorage draftStorage, TestCanonStorage canonStorage) {
            super(new LlmGateway(null), new TestCardStorage(null), draftStorage, null, canonStorage);
            this.testCanonStorage = canonStorage;
        }

        @Override
        public ChapterSummary generateChapterSummary(String projectId, String chapter, String chapterTitle, String finalDraft) {
            this.lastChapterTitle = chapterTitle;
            ChapterSummary copy = ChapterSummary.builder()
                    .chapter(generatedSummary.getChapter())
                    .volumeId(generatedSummary.getVolumeId())
                    .title(generatedSummary.getTitle())
                    .briefSummary(generatedSummary.getBriefSummary())
                    .wordCount(generatedSummary.getWordCount())
                    .keyEvents(generatedSummary.getKeyEvents())
                    .newFacts(generatedSummary.getNewFacts())
                    .characterStateChanges(generatedSummary.getCharacterStateChanges())
                    .openLoops(generatedSummary.getOpenLoops())
                    .build();
            return copy;
        }

        @Override
        public Map<String, Object> extractCanonUpdates(String projectId, String chapter, String finalDraft) {
            return canonUpdates;
        }
    }

    private static class TestDraftStorage extends DraftStorage {
        private final Path root;
        private final Map<String, ChapterSummary> existingSummaries = new LinkedHashMap<>();
        private final Map<String, SceneBrief> sceneBriefs = new LinkedHashMap<>();
        private ChapterSummary savedSummary;

        private TestDraftStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public Optional<ChapterSummary> getChapterSummary(String projectId, String chapter) {
            ChapterSummary summary = existingSummaries.get(chapter);
            return Optional.ofNullable(summary);
        }

        @Override
        public Optional<SceneBrief> getSceneBrief(String projectId, String chapter) {
            return Optional.ofNullable(sceneBriefs.get(chapter));
        }

        @Override
        public void saveChapterSummary(String projectId, ChapterSummary summary) throws IOException {
            String chapter = summary.getChapter();
            existingSummaries.put(chapter, summary);
            savedSummary = summary;
        }

        @Override
        public Optional<String> getFinalDraft(String projectId, String chapter) {
            return Optional.empty();
        }

        @Override
        public Optional<Draft> getLatestDraft(String projectId, String chapter) {
            return Optional.empty();
        }
    }

    private static class TestCardStorage extends CardStorage {
        private final Path root;

        private TestCardStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root == null ? Path.of(".") : root.resolve(projectId);
        }

        @Override
        public List<String> listCharacterCards(String projectId) {
            return List.of();
        }

        @Override
        public List<String> listWorldCards(String projectId) {
            return List.of();
        }
    }

    private static class TestCanonStorage extends CanonStorage {
        private final Path root;

        private TestCanonStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public List<com.wenshape.model.entity.Fact> listFacts(String projectId) {
            return List.of();
        }

        @Override
        public int deleteFactsByChapter(String projectId, String chapter) {
            return 0;
        }

        @Override
        public int deleteTimelineEventsByChapter(String projectId, String chapter) {
            return 0;
        }

        @Override
        public int deleteCharacterStatesByChapter(String projectId, String chapter) {
            return 0;
        }

        @Override
        public void saveTimelineEvent(String projectId, Map<String, Object> event) {
        }

        @Override
        public void saveCharacterState(String projectId, Map<String, Object> state) {
        }
    }

    private static class TestMemoryPackStorage extends MemoryPackStorage {
        private final Path root;

        private TestMemoryPackStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public void writePack(String projectId, String chapter, Map<String, Object> pack) {
        }
    }
}
