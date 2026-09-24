package com.moxu.context;

import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.Fact;
import com.moxu.model.entity.WorldCard;
import com.moxu.search.ProjectLexiconResolver;
import com.moxu.storage.CardStorage;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.DraftStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSelectEngineIdfTest {

    @TempDir
    Path tempDir;

    @Test
    void factRetrievalPrefersRareEntityTermOverCommonBackgroundTerm() {
        TestCardStorage cardStorage = new TestCardStorage();
        TestCanonStorage canonStorage = new TestCanonStorage();
        canonStorage.facts = List.of(
                Fact.builder().id("F1").statement("合欢宗传承已有千年。").build(),
                Fact.builder().id("F2").statement("合欢宗弟子修习双修秘法。").build(),
                Fact.builder().id("F3").statement("奥莉诺来自外海魔域。").build()
        );
        ContextSelectEngine engine = new ContextSelectEngine(cardStorage, canonStorage, new DraftStorage(), resolver(cardStorage));

        List<ContextItem> items = engine.retrievalSelect("p", "奥莉诺 合欢宗", List.of("fact"), 3);

        assertFalse(items.isEmpty());
        assertEquals("F3", items.getFirst().getId());
    }

    @Test
    void characterRetrievalPrefersRareCharacterNameOverCommonOrganizationTerm() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.characterNames = List.of("奥莉诺", "长老甲", "长老乙");
        cardStorage.characterCards = Map.of(
                "奥莉诺", CharacterCard.builder().name("奥莉诺").description("外海来客，擅长幻术。").build(),
                "长老甲", CharacterCard.builder().name("长老甲").description("合欢宗长老，负责刑罚。").build(),
                "长老乙", CharacterCard.builder().name("长老乙").description("合欢宗执事，负责外务。").build()
        );

        ContextSelectEngine engine = new ContextSelectEngine(cardStorage, new TestCanonStorage(), new DraftStorage(), resolver(cardStorage));

        List<ContextItem> items = engine.retrievalSelect("p", "奥莉诺 合欢宗", List.of("character"), 3);

        assertFalse(items.isEmpty());
        assertEquals("character:奥莉诺", items.getFirst().getId());
    }

    @Test
    void worldRetrievalUsesProjectLexiconToPreferExactLongEntityOverPartialOverlap() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.worldNames = List.of("白银审判骑士团", "白银骑士团");
        cardStorage.worldCards = Map.of(
                "白银审判骑士团", WorldCard.builder()
                        .name("白银审判骑士团")
                        .description("白银审判骑士团统帅边境军。")
                        .build(),
                "白银骑士团", WorldCard.builder()
                        .name("白银骑士团")
                        .description("白银骑士团负责审判叛徒。")
                        .build()
        );

        ContextSelectEngine engine = new ContextSelectEngine(cardStorage, new TestCanonStorage(), new DraftStorage(), resolver(cardStorage));

        List<ContextItem> items = engine.retrievalSelect("p", "白银审判骑士团", List.of("world_entity"), 2);

        assertFalse(items.isEmpty());
        assertEquals("world_entity:白银审判骑士团", items.getFirst().getId());
    }

    @Test
    void factRetrievalFiltersLowSignalUnnamedFactsAndDeduplicatesNearDuplicates() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.characterNames = List.of("王方");
        cardStorage.characterCards = Map.of(
                "王方", CharacterCard.builder().name("王方").description("外门弟子。").build()
        );

        TestCanonStorage canonStorage = new TestCanonStorage();
        canonStorage.facts = List.of(
                Fact.builder().id("F1").statement("一名身形壮实的弟子修为炼气三层").build(),
                Fact.builder().id("F2").statement("壮实弟子修为确认已到炼气三层，昨日刚稳住").build(),
                Fact.builder().id("F3").statement("王方完成任务下午过半已清通南段和两处堵口，并巡查确认没有回堵").build(),
                Fact.builder().id("F4").statement("王方下午完成额外检查把南段和两处堵口都清过，并来回确认没有回堵").build(),
                Fact.builder().id("F5").statement("王方失去法器并与宗门外援失联").build()
        );

        ContextSelectEngine engine = new ContextSelectEngine(cardStorage, canonStorage, new DraftStorage(), resolver(cardStorage));

        List<ContextItem> items = engine.retrievalSelect("p", "王方 法器 失联", List.of("fact"), 5);

        assertFalse(items.isEmpty());
        assertEquals("F5", items.getFirst().getId());
        assertTrue(items.stream().noneMatch(item -> "F1".equals(item.getId())), "匿名临时角色事实不应进入上下文");
        long ditchFacts = items.stream()
                .filter(item -> item.getId().equals("F3") || item.getId().equals("F4"))
                .count();
        assertTrue(ditchFacts <= 1, "近重复的流水账事实应在检索时折叠");
    }

    @Test
    void factsAfterTheFirstFiftyRemainSearchable() {
        TestCanonStorage canon = new TestCanonStorage();
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 200; i++) facts.add(Fact.builder().id("background-" + i)
                .statement("Background ordinary landscape number " + i).build());
        facts.add(Fact.builder().id("important").statement("Alice must keep the obsidian key.").build());
        canon.facts = facts;
        var engine = new ContextSelectEngine(new TestCardStorage(), canon, new DraftStorage(), null);
        var selected = engine.retrievalSelect("p", "obsidian key", List.of("fact"), 1);
        assertEquals(List.of("important"), selected.stream().map(ContextItem::getId).toList());
    }

    @Test
    void characterAndWorldCardsAreLimitedAfterRankingNotByStorageOrder() {
        TestCardStorage cards = new TestCardStorage();
        Map<String, CharacterCard> characters = new LinkedHashMap<>();
        Map<String, WorldCard> worlds = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) {
            characters.put("person" + i, CharacterCard.builder().name("person" + i).description("background").build());
            worlds.put("place" + i, WorldCard.builder().name("place" + i).description("background").build());
        }
        characters.put("Alice", CharacterCard.builder().name("Alice").description("obsidian keeper").build());
        worlds.put("Citadel", WorldCard.builder().name("Citadel").description("obsidian vault").build());
        cards.characterNames = new ArrayList<>(characters.keySet()); cards.characterCards = characters;
        cards.worldNames = new ArrayList<>(worlds.keySet()); cards.worldCards = worlds;
        var engine = new ContextSelectEngine(cards, new TestCanonStorage(), new DraftStorage(), null);
        assertEquals("character:Alice", engine.retrievalSelect("p", "obsidian", List.of("character"), 1).getFirst().getId());
        assertEquals("world_entity:Citadel", engine.retrievalSelect("p", "obsidian", List.of("world"), 1).getFirst().getId());
    }

    @Test
    void factDeduplicationPreservesNegationNumbersAndAdditionalClauses() {
        TestCanonStorage canon = new TestCanonStorage();
        canon.facts = List.of(
                Fact.builder().id("positive").statement("Alice owns the key").build(),
                Fact.builder().id("negative").statement("Alice does not own the key").build(),
                Fact.builder().id("qualified").statement("Alice owns the key but cannot use it").build(),
                Fact.builder().id("one").statement("Alice owns 1 key").build(),
                Fact.builder().id("two").statement("Alice owns 2 keys").build(),
                Fact.builder().id("duplicate").statement("  Alice owns the key  ").build());
        var engine = new ContextSelectEngine(new TestCardStorage(), canon, new DraftStorage(), null);
        var ids = engine.retrievalSelect("p", "Alice key", List.of("fact"), 10).stream().map(ContextItem::getId).toList();
        assertEquals(5, ids.size());
        assertTrue(ids.containsAll(List.of("positive", "negative", "qualified", "one", "two")));
        assertFalse(ids.contains("duplicate"));
    }

    @Test
    void noPreviousWrittenChapterCannotRetrieveFutureProseOrSummaries() throws Exception {
        DraftStorage drafts = new DraftStorage();
        ReflectionTestUtils.setField(drafts, "dataDir", tempDir.toString());
        drafts.saveFinalDraft("p", "V1C2", "Alice holds the obsidian key.");
        drafts.saveChapterSummary("p", com.moxu.model.entity.ChapterSummary.builder()
                .chapter("V1C2").briefSummary("Alice holds the obsidian key.").build());
        var engine = new ContextSelectEngine(new TestCardStorage(), new TestCanonStorage(), drafts, null);
        assertTrue(engine.retrievalSelectForChapter("p", "V1C1", "obsidian", List.of("summary", "text_chunk"), 5).isEmpty());
        assertFalse(engine.retrievalSelectForChapter("p", "V1C3", "obsidian", List.of("summary", "text_chunk"), 5).isEmpty());
    }

    private ProjectLexiconResolver resolver(CardStorage cardStorage) {
        ProjectLexiconResolver resolver = new ProjectLexiconResolver(cardStorage);
        ReflectionTestUtils.setField(resolver, "dataDir", tempDir.toString());
        return resolver;
    }

    private static class TestCardStorage extends CardStorage {
        List<String> characterNames = List.of();
        Map<String, CharacterCard> characterCards = Map.of();
        List<String> worldNames = List.of();
        Map<String, WorldCard> worldCards = Map.of();

        @Override
        public List<String> listCharacterCards(String projectId) {
            return characterNames;
        }

        @Override
        public Optional<CharacterCard> getCharacterCard(String projectId, String characterName) {
            return Optional.ofNullable(characterCards.get(characterName));
        }

        @Override
        public List<String> listWorldCards(String projectId) {
            return worldNames;
        }

        @Override
        public Optional<WorldCard> getWorldCard(String projectId, String cardName) {
            return Optional.ofNullable(worldCards.get(cardName));
        }
    }

    private static class TestCanonStorage extends CanonStorage {
        List<Fact> facts = List.of();

        @Override
        public List<Fact> listFacts(String projectId) {
            return facts;
        }
    }
}
