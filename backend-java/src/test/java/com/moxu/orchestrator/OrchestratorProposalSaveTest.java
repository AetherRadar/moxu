package com.moxu.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.agent.ArchivistAgent;
import com.moxu.agent.EditorAgent;
import com.moxu.agent.WriterAgent;
import com.moxu.context.ContextSelectEngine;
import com.moxu.llm.LlmGateway;
import com.moxu.model.entity.ChapterSummary;
import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.Draft;
import com.moxu.model.entity.SceneBrief;
import com.moxu.model.entity.WorldCard;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DeAiRewriteConfigStorage;
import com.moxu.storage.DraftStorage;
import com.moxu.storage.MemoryPackStorage;
import com.moxu.websocket.SessionWebSocketHandler;
import com.moxu.websocket.TraceWebSocketHandler;
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

class OrchestratorProposalSaveTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAnalysisCreatesCharacterAndWorldCardsFromProposals() {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        TestCanonStorage canonStorage = new TestCanonStorage(tempDir);
        RecordingCardStorage cardStorage = new RecordingCardStorage(tempDir);
        Orchestrator orchestrator = orchestrator(draftStorage, canonStorage, cardStorage);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", "勾引");
        summary.put("brief_summary", "这是摘要");
        summary.put("volume_id", "V1");
        summary.put("word_count", 12);
        summary.put("key_events", List.of());
        summary.put("new_facts", List.of());
        summary.put("character_state_changes", List.of());
        summary.put("open_loops", List.of());

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", summary);
        analysis.put("facts", List.of());
        analysis.put("timeline_events", List.of());
        analysis.put("character_states", List.of());
        analysis.put("proposals", List.of(
                Map.of(
                        "name", "刘雨菲",
                        "type", "Character",
                        "description", "本章反复出场的重要角色",
                        "stars", 2
                ),
                Map.of(
                        "name", "专案组",
                        "type", "World",
                        "description", "案件调查组织"
                )
        ));

        Map<String, Object> result = orchestrator.saveAnalysis("p", "V1C13", analysis, true, false);

        assertTrue(Boolean.TRUE.equals(result.get("success")));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(2, stats.get("cards_created"));
        assertTrue(cardStorage.getCharacterCard("p", "刘雨菲").isPresent());
        assertTrue(cardStorage.getWorldCard("p", "专案组").isPresent());
    }

    private Orchestrator orchestrator(TestDraftStorage draftStorage,
                                      TestCanonStorage canonStorage,
                                      RecordingCardStorage cardStorage) {
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
        ArchivistAgent archivistAgent = new ArchivistAgent(new LlmGateway(null), cardStorage, draftStorage, null, canonStorage);
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

    private static class RecordingCardStorage extends CardStorage {
        private final Path root;
        private final Map<String, CharacterCard> characters = new LinkedHashMap<>();
        private final Map<String, WorldCard> worlds = new LinkedHashMap<>();

        private RecordingCardStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public Optional<CharacterCard> getCharacterCard(String projectId, String characterName) {
            return Optional.ofNullable(characters.get(characterName));
        }

        @Override
        public void saveCharacterCard(String projectId, CharacterCard card) {
            characters.put(card.getName(), card);
        }

        @Override
        public List<String> listCharacterCards(String projectId) {
            return List.copyOf(characters.keySet());
        }

        @Override
        public Optional<WorldCard> getWorldCard(String projectId, String cardName) {
            return Optional.ofNullable(worlds.get(cardName));
        }

        @Override
        public void saveWorldCard(String projectId, WorldCard card) {
            worlds.put(card.getName(), card);
        }

        @Override
        public List<String> listWorldCards(String projectId) {
            return List.copyOf(worlds.keySet());
        }
    }

    private static class TestDraftStorage extends DraftStorage {
        private final Path root;
        private ChapterSummary savedSummary;

        private TestDraftStorage(Path root) {
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
        public Optional<ChapterSummary> getChapterSummary(String projectId, String chapter) {
            return Optional.ofNullable(savedSummary);
        }

        @Override
        public void saveChapterSummary(String projectId, ChapterSummary summary) throws IOException {
            this.savedSummary = summary;
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
        public List<com.moxu.model.entity.Fact> listFacts(String projectId) {
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
