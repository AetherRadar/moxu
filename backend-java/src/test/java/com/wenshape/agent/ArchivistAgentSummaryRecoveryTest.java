package com.wenshape.agent;

import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.CharacterCard;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivistAgentSummaryRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void generateChapterSummary_recoversOpenLoopsWhenInitialSummaryOmitsThem() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "title": "云崖之下",
                  "brief_summary": "王方坠入谷底后艰难苏醒，确认自己伤重且物资稀缺，只能沿崖壁继续摸索求生。",
                  "key_events": [
                    "王方在谷底苏醒并确认自己与上方战场隔绝。",
                    "他检查伤势和物资，发现法器遗失且补给极少。"
                  ],
                  "new_facts": [
                    "王方随身法器遗失，眼下只能依靠残余物资求生。"
                  ],
                  "character_state_changes": [],
                  "open_loops": []
                }
                """,
                """
                {
                  "open_loops": [
                    "谷底深处那截发黑石面与幽冷微光的来源究竟是什么，它是否通向隐藏空间或遗迹？",
                    "身后越来越近的拖擦异响来自什么东西，它是否已经盯上了王方？"
                  ]
                }
                """
        ));

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                new TestCardStorage(tempDir),
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方从谷底苏醒，确认自己伤重且法器遗失，只能沿崖壁摸索。
                雾里隐隐有一点微青光，像埋在深井底的一粒火星。
                他刚要再往前一步，身后忽然又响起了那阵拖擦声，比先前近得多。
                """;

        ChapterSummary summary = agent.generateChapterSummary("p", "V1C5", "云崖之下", draft);

        assertEquals(2, summary.getOpenLoops().size());
        assertTrue(summary.getOpenLoops().get(0).contains("微光"));
        assertTrue(summary.getOpenLoops().get(1).contains("拖擦"));
    }

    @Test
    void extractCanonUpdates_recoversCharacterStatesWhenInitialExtractionIsEmpty() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "facts": [],
                  "timeline_events": [],
                  "character_states": []
                }
                """,
                """
                {
                  "character_states": [
                    {
                      "character": "王方",
                      "goals": ["沿崖壁继续寻找出路", "确认谷底异常石面与微光的来源"],
                      "injuries": ["右腿受伤", "肋下内伤"],
                      "inventory": ["少量碎干粮", "几乎见底的水囊"],
                      "location": "浓雾笼罩的谷底",
                      "emotional_state": "受伤且紧张，但仍强迫自己冷静"
                    }
                  ]
                }
                """
        ));

        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        cardStorage.saveCharacter("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                cardStorage,
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方从坠崖后的剧痛中苏醒，发现自己身处浓雾笼罩的谷底。
                他确认右腿、后背、肋下都有伤，一尝试运气便牵动内伤。
                身上只剩少量碎干粮和几乎见底的水囊，只能继续沿崖壁寻找出路。
                """;

        Map<String, Object> updates = agent.extractCanonUpdates("p", "V1C5", draft);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) updates.get("character_states");

        assertEquals(1, states.size());
        assertEquals("王方", states.get(0).get("character"));
        assertTrue(String.valueOf(states.get(0).get("location")).contains("谷底"));
        assertFalse(((List<?>) states.get(0).getOrDefault("injuries", List.of())).isEmpty());
    }

    @Test
    void extractCanonUpdates_fallsBackToHeuristicStateWhenRecoveryAlsoReturnsEmpty() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "facts": [],
                  "timeline_events": [],
                  "character_states": []
                }
                """,
                """
                {
                  "character_states": []
                }
                """
        ));

        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        cardStorage.saveCharacter("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                cardStorage,
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方从坠崖后的剧痛中苏醒，发现自己身处被浓雾和高耸崖壁夹住的谷底。
                他检查伤势，确认右腿、后背、肋下等处皆有重伤，一尝试运气便牵动内伤。
                更糟的是，随身法器已经遗失，身上只剩一块磕裂的木牌、少量碎干粮和几乎见底的水囊。
                拖擦声出现后，他判断继续原地停留不安全，决定重新沿崖壁探索。
                """;

        Map<String, Object> updates = agent.extractCanonUpdates("p", "V1C5", draft);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) updates.get("character_states");

        assertEquals(1, states.size());
        Map<String, Object> state = states.get(0);
        assertEquals("王方", state.get("character"));
        assertTrue(String.valueOf(state.get("state")).contains("伤势"));
        assertTrue(String.valueOf(state.get("location")).contains("谷底"));
        assertTrue(((List<?>) state.getOrDefault("inventory", List.of())).size() >= 2);
    }

    @Test
    void extractCanonUpdates_supplementsSurvivalFactsWhenInitialExtractionMissesThem() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "facts": [],
                  "timeline_events": [],
                  "character_states": []
                }
                """,
                """
                {
                  "character_states": []
                }
                """
        ));

        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        cardStorage.saveCharacter("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                cardStorage,
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方坠崖后在谷底苏醒，呼喊求援始终没有回应，抬头只能看见被雾封住的崖顶。
                他一尝试运气，胸腹便被猛地扯紧，确认强行运气只会牵动内伤。
                更糟的是，随身法器已经遗失，水囊也几乎见底，身上只剩少量碎干粮和一块磕裂的木牌。
                沿崖壁摸索时，他发现几块半埋岩面边缘异常笔直，像被人削过，雾里还有拖擦声一寸寸逼近。
                """;

        Map<String, Object> updates = agent.extractCanonUpdates("p", "V1C5", draft);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) updates.get("facts");

        assertFalse(facts.isEmpty());
        String combined = facts.stream()
                .map(item -> String.valueOf(item.getOrDefault("statement", "")))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(combined.contains("法器遗失") || combined.contains("运气会牵动内伤"));
        assertTrue(combined.contains("谷底") || combined.contains("人工修整痕迹") || combined.contains("拖擦异响"));
    }

    @Test
    void extractCanonUpdates_prioritizesNewDiscoveryFactsInAncientCaveChapter() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "facts": [],
                  "timeline_events": [],
                  "character_states": []
                }
                """,
                """
                {
                  "character_states": []
                }
                """
        ));

        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        cardStorage.saveCharacter("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                cardStorage,
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方绕开发黑石面后，从漏出白光的石缝钻入一处废弃古洞。洞中残留石台、断裂石片与受损阵纹。
                他在洞室深处找到一汪积水，谨慎试验后少量饮用，又给水囊补了一点水。
                石壁上刻着“太阴”“引气”“封脉”“寒入骨”等残字，还有一道人形修炼图。
                他用尖石触到残破阵纹后，洞中传来轻微嗡鸣，灰白雾气沿断裂痕迹爬向壁刻。
                那股阴寒气机随后压住了他体内原本火烧火燎的闷堵，怀里的木牌也跟着轻响。
                """;

        Map<String, Object> updates = agent.extractCanonUpdates("p", "V1C6", draft);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) updates.get("facts");

        String combined = facts.stream()
                .map(item -> String.valueOf(item.getOrDefault("statement", "")))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(combined.contains("古洞") || combined.contains("洞室"));
        assertTrue(combined.contains("阵纹") || combined.contains("禁制"));
        assertTrue(combined.contains("太阴") || combined.contains("壁刻"));
        assertTrue(combined.contains("阴寒气机") || combined.contains("木牌"));
    }

    @Test
    void extractCanonUpdates_mergesHeuristicChapterEndStateWhenLlmStateIsGeneric() {
        QueueLlmGateway gateway = new QueueLlmGateway(List.of(
                """
                {
                  "facts": [],
                  "timeline_events": [],
                  "character_states": [
                    {
                      "character": "王方",
                      "state": "情绪：受伤且紧张，但仍强迫自己冷静；目标：确认谷底异常石面与微光的来源；伤势：后背重伤、肋下内伤；持有：随身法器遗失、磕裂木牌",
                      "location": "浓雾笼罩的谷底",
                      "goals": ["确认谷底异常石面与微光的来源"],
                      "injuries": ["后背重伤", "肋下内伤"],
                      "inventory": ["随身法器遗失", "磕裂木牌"],
                      "emotional_state": "受伤且紧张，但仍强迫自己冷静"
                    }
                  ]
                }
                """
        ));

        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        cardStorage.saveCharacter("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        ArchivistAgent agent = new ArchivistAgent(
                gateway,
                cardStorage,
                new TestDraftStorage(tempDir),
                null,
                new TestCanonStorage(tempDir)
        );

        String draft = """
                王方从石缝挤入一处废弃古洞，洞中有石台、残片和受损阵纹。
                他试过积水后给水囊补了一点水，又将刻有“阴”字的残片塞进怀里。
                壁刻上可辨“太阴”“引气”“封脉”“寒入骨”，旁边还有一道人形修炼图。
                阴寒气机顺着壁刻钻入体内，压住了原本火烧火燎的闷堵，怀里的木牌也在字迹亮起时轻响。
                """;

        Map<String, Object> updates = agent.extractCanonUpdates("p", "V1C6", draft);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) updates.get("character_states");

        assertEquals(1, states.size());
        Map<String, Object> state = states.get(0);
        assertTrue(String.valueOf(state.getOrDefault("location", "")).contains("古洞"));
        assertTrue(String.valueOf(state.getOrDefault("state", "")).contains("太阴") || String.valueOf(state.getOrDefault("state", "")).contains("古洞"));
        assertTrue(String.valueOf(state.getOrDefault("inventory", List.of())).contains("补入少量清水"));
        assertTrue(String.valueOf(state.getOrDefault("injuries", List.of())).contains("内伤闷堵"));
    }

    private static final class QueueLlmGateway extends LlmGateway {
        private final Deque<String> responses = new ArrayDeque<>();

        private QueueLlmGateway(List<String> responses) {
            super(null);
            this.responses.addAll(responses);
        }

        @Override
        public String getProviderForAgent(String agentName) {
            return "mock";
        }

        @Override
        public LlmResponse chat(List<Map<String, String>> messages, String providerId, Double temperature, Integer maxTokens) {
            String content = responses.isEmpty() ? "{\"facts\":[],\"timeline_events\":[],\"character_states\":[]}" : responses.removeFirst();
            return LlmResponse.builder()
                    .content(content)
                    .provider("mock")
                    .model("mock")
                    .elapsedMs(1L)
                    .totalTokens(1)
                    .build();
        }
    }

    private static final class TestCardStorage extends CardStorage {
        private final Path root;
        private final Map<String, Map<String, CharacterCard>> charactersByProject = new LinkedHashMap<>();

        private TestCardStorage(Path root) {
            this.root = root;
        }

        void saveCharacter(String projectId, CharacterCard card) {
            charactersByProject.computeIfAbsent(projectId, ignored -> new LinkedHashMap<>())
                    .put(card.getName(), card);
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public Optional<CharacterCard> getCharacterCard(String projectId, String characterName) {
            return Optional.ofNullable(charactersByProject
                    .getOrDefault(projectId, Map.of())
                    .get(characterName));
        }

        @Override
        public List<String> listCharacterCards(String projectId) {
            return new ArrayList<>(charactersByProject.getOrDefault(projectId, Map.of()).keySet());
        }
    }

    private static final class TestDraftStorage extends DraftStorage {
        private final Path root;

        private TestDraftStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }
    }

    private static final class TestCanonStorage extends CanonStorage {
        private final Path root;

        private TestCanonStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }
    }
}
