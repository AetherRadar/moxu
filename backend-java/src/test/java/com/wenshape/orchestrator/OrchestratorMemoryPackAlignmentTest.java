package com.wenshape.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.agent.ArchivistAgent;
import com.wenshape.agent.EditorAgent;
import com.wenshape.agent.WriterAgent;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.CharacterCard;
import com.wenshape.model.entity.Fact;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.model.entity.WorldCard;
import com.wenshape.nlp.AdaptiveHintProfile;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorMemoryPackAlignmentTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAnalysis_realignsMemoryPackAgainstCurrentChapterInsteadOfOldArcResidue() throws Exception {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        TestCanonStorage canonStorage = new TestCanonStorage(tempDir);
        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        CapturingMemoryPackStorage memoryPackStorage = new CapturingMemoryPackStorage(tempDir);
        StaticResearchGateway gateway = new StaticResearchGateway();

        cardStorage.saveCharacterCard("p", CharacterCard.builder().name("王方").description("外门弟子").build());
        cardStorage.saveCharacterCard("p", CharacterCard.builder().name("管事").description("旧线角色").build());
        cardStorage.saveWorldCard("p", WorldCard.builder().name("杂务院").description("旧线设定").build());

        draftStorage.saveSceneBrief("p", "V1C5", SceneBrief.builder()
                .chapter("V1C5")
                .title("云崖之下")
                .goal("开场写坠崖后的昏迷与苏醒，突出身体剧痛、法器遗失与谷底求生压力。")
                .characters(List.of(
                        new LinkedHashMap<>(Map.of("name", "王方")),
                        new LinkedHashMap<>(Map.of("name", "管事"))
                ))
                .facts(List.of(
                        "王方的同屋修为炼气三层",
                        "外门杂务分配倾向修为高一层，活就轻一层",
                        "管事按旧例在杂务院前点名分派木牌"
                ))
                .timelineContext(new LinkedHashMap<>(Map.of(
                        "before", "V1C1：天未亮，王方就在杂务院前等候分派差事，管事按旧例与修为高低发放木牌。",
                        "current", "旧版场景简报残留",
                        "after", ""
                )))
                .build());

        draftStorage.saveChapterSummary("p", summary(
                "V1C3",
                "夜乱将起",
                "王方在外门杂务与夜间巡查中感到气氛越发紧绷。",
                List.of("外门查验频繁", "王方只能继续忍耐"),
                List.of(),
                List.of(),
                List.of()
        ));
        draftStorage.saveChapterSummary("p", summary(
                "V1C4",
                "山门崩裂",
                "宗门大战骤起，王方在混乱中沿边缘山道逃窜，最终失足坠崖。",
                List.of("宗门大战爆发", "王方坠入崖下"),
                List.of(),
                List.of("王方：被战局逼离熟悉区域"),
                List.of("崖下是否还有生路？")
        ));

        writeFinalDraft("p", "V1C4", """
                王方在山门崩裂后的混乱中逃窜，被斗法余波逼到边缘小道，最终连同崩落的山道一起坠了下去。
                """);
        writeFinalDraft("p", "V1C5", """
                王方坠崖后在谷底苏醒，呼喊求援始终没有回应，只能看见被雾封住的崖顶。
                他一尝试运气，胸腹便被猛地扯紧，确认强行运气只会牵动内伤。
                更糟的是，随身法器已经遗失，水囊也几乎见底，身上只剩少量碎干粮和一块磕裂的木牌。
                沿崖壁摸索时，他发现几块半埋岩面边缘异常笔直，像被人削过，雾里还有拖擦声一寸寸逼近。
                """);

        writeBindings("p", "V1C3", """
                chapter: "V1C3"
                characters:
                - "王方"
                - "管事"
                world_entities:
                - "杂务院"
                world: ["杂务院"]
                world_rules: []
                sources: []
                """);
        writeBindings("p", "V1C4", """
                chapter: "V1C4"
                characters:
                - "王方"
                world_entities: []
                world: []
                world_rules: []
                sources: []
                """);
        writeBindings("p", "V1C5", """
                chapter: "V1C5"
                characters:
                - "王方"
                world_entities: []
                world: []
                world_rules: []
                sources: []
                """);

        Orchestrator orchestrator = new Orchestrator(
                gateway,
                cardStorage,
                draftStorage,
                canonStorage,
                memoryPackStorage,
                new WriterAgent(gateway, cardStorage, draftStorage, null),
                new ArchivistAgent(gateway, cardStorage, draftStorage, null, canonStorage),
                new EditorAgent(gateway, cardStorage, draftStorage, memoryPackStorage, new DeAiRewriteConfigStorage(), null),
                new ContextSelectEngine(cardStorage, canonStorage, draftStorage, null),
                new SessionWebSocketHandler(new ObjectMapper()),
                new TraceWebSocketHandler(new ObjectMapper()),
                null
        );

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", new LinkedHashMap<>(Map.of(
                "title", "云崖之下",
                "brief_summary", "王方坠崖后苏醒，身受多处外伤并疑有内伤；随身法器遗失，仅余少量干粮与几乎见底的水囊。",
                "volume_id", "V1",
                "word_count", 1200,
                "key_events", List.of(
                        "王方在谷底苏醒并确认自己短期无法返回上方环境。",
                        "他检查伤势和物资，发现法器遗失且补给极少。",
                        "沿崖壁摸索时，他发现疑似人工修整的石面并再次听到拖擦异响。"
                ),
                "new_facts", List.of(),
                "character_state_changes", List.of(
                        "王方：情绪：惊惧中强迫自己保持冷静与警惕；目标：确认谷底异常石面与微光的来源；伤势：右腿受伤、后背重伤；持有：随身法器遗失、磕裂木牌"
                ),
                "open_loops", List.of(
                        "逼近的拖擦异响究竟来自什么东西？",
                        "发黑石面和狭缝中的幽冷青光究竟通向哪里？"
                )
        )));
        analysis.put("facts", List.of(
                Map.of("statement", "王方随身法器遗失，谷底求生只能依赖残余物资", "confidence", 0.92d),
                Map.of("statement", "王方强行运气会牵动内伤，当前无法依靠运功疗伤", "confidence", 0.94d),
                Map.of("statement", "谷底崖壁附近留有人工修整痕迹，附近存在旧时人工结构", "confidence", 0.88d)
        ));
        analysis.put("timeline_events", List.of());
        analysis.put("character_states", List.of(
                Map.of(
                        "character", "王方",
                        "state", "情绪：惊惧中强迫自己保持冷静与警惕；目标：确认谷底异常石面与微光的来源；伤势：右腿受伤、后背重伤；持有：随身法器遗失、磕裂木牌",
                        "location", "浓雾笼罩的谷底",
                        "goals", List.of("确认谷底异常石面与微光的来源"),
                        "injuries", List.of("右腿受伤", "后背重伤", "强行运气会牵动内伤"),
                        "inventory", List.of("随身法器遗失", "磕裂木牌", "少量碎干粮", "几乎见底的水囊"),
                        "last_seen", "V1C5"
                )
        ));
        analysis.put("proposals", List.of());

        orchestrator.saveAnalysis("p", "V1C5", analysis, true, false);

        Map<String, Object> pack = memoryPackStorage.lastPack;
        assertNotNull(pack);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pack.get("payload");
        assertNotNull(payload);

        List<String> seedEntities = stringList(payload.get("seed_entities"));
        assertTrue(seedEntities.contains("王方"));
        assertFalse(seedEntities.contains("管事"));
        assertFalse(seedEntities.contains("杂务院"));

        String planned = String.join("\n", stringList(payload.get("planned_queries")));
        assertFalse(planned.contains("管事"));
        assertFalse(planned.contains("杂务院"));
        assertFalse(planned.contains("世界观"));
        assertFalse(planned.contains("类型"));
        assertTrue(planned.isBlank() || planned.contains("拖擦") || planned.contains("石面") || planned.contains("微光"));

        String unresolved = String.valueOf(payload.get("unresolved_gaps"));
        assertFalse(unresolved.contains("管事"));

        String workingMemory = String.valueOf(payload.getOrDefault("working_memory", ""));
        assertTrue(workingMemory.contains("法器遗失"));
        assertTrue(workingMemory.contains("谷底"));
        assertFalse(workingMemory.contains("管事"));
        assertFalse(workingMemory.contains("杂务院"));
        assertFalse(workingMemory.contains("院口挤了一堆人"));
        assertFalse(workingMemory.contains("壮实弟子"));

        @SuppressWarnings("unchecked")
        Map<String, Object> digest = (Map<String, Object>) pack.get("chapter_digest");
        List<String> topWorld = stringList(digest.get("top_world"));
        assertFalse(topWorld.contains("杂务院"));
    }

    @Test
    void saveAnalysis_prefersCurrentDiscoveryFactsAndReadableQueriesForAncientCaveFollowup() throws Exception {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        TestCanonStorage canonStorage = new TestCanonStorage(tempDir);
        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        CapturingMemoryPackStorage memoryPackStorage = new CapturingMemoryPackStorage(tempDir);
        StaticResearchGateway gateway = new StaticResearchGateway();

        cardStorage.saveCharacterCard("p", CharacterCard.builder().name("王方").description("外门弟子").build());

        draftStorage.saveSceneBrief("p", "V1C6", SceneBrief.builder()
                .chapter("V1C6")
                .title("古洞与太阴灵机")
                .goal("王方在拖擦声逼近时进入石缝后的古洞，确认古洞遗留与太阴壁刻的真实价值。")
                .characters(List.of(new LinkedHashMap<>(Map.of("name", "王方"))))
                .facts(List.of("拖擦声持续逼近", "发黑石面后方另有空间"))
                .timelineContext(new LinkedHashMap<>(Map.of(
                        "before", "王方在谷底被拖擦异响逼迫，只得绕开发黑石面。",
                        "current", "进入古洞探索。",
                        "after", "确认太阴壁刻与古洞禁制的后续作用。"
                )))
                .build());

        draftStorage.saveChapterSummary("p", summary(
                "V1C5",
                "云崖之下",
                "王方坠崖后在谷底苏醒，发现拖擦异响与发黑石面背后另有隐秘。",
                List.of("王方在谷底苏醒", "拖擦异响持续逼近", "发黑石面后似乎另有空间"),
                List.of("王方坠崖后受困谷底"),
                List.of("王方：情绪：惊惧中强迫自己保持冷静与警惕；目标：确认谷底异常石面与微光的来源"),
                List.of("拖擦声背后的未知之物究竟是什么？", "发黑石面后的隐秘空间通向何处？")
        ));

        writeFinalDraft("p", "V1C5", """
                王方在谷底听见拖擦异响不断逼近，只得绕开发黑石面继续摸索。
                """);
        writeFinalDraft("p", "V1C6", """
                王方顺着漏出白光的石缝钻入废弃古洞，洞中残留石台、断裂石片与受损阵纹。
                他在洞室深处找到一汪积水，谨慎试过后给水囊补了一点水。
                壁刻上可辨“太阴”“引气”“封脉”“寒入骨”，旁边还留着人形修炼图。
                尖石触动残破阵纹后，灰白雾气沿断裂痕迹爬向壁刻，王方怀里的木牌也跟着轻响。
                """);

        writeBindings("p", "V1C5", """
                chapter: "V1C5"
                characters:
                - "王方"
                world_entities: []
                world: []
                world_rules: []
                sources: []
                """);
        writeBindings("p", "V1C6", """
                chapter: "V1C6"
                characters:
                - "王方"
                world_entities: []
                world: []
                world_rules: []
                sources: []
                """);

        Orchestrator orchestrator = new Orchestrator(
                gateway,
                cardStorage,
                draftStorage,
                canonStorage,
                memoryPackStorage,
                new WriterAgent(gateway, cardStorage, draftStorage, null),
                new ArchivistAgent(gateway, cardStorage, draftStorage, null, canonStorage),
                new EditorAgent(gateway, cardStorage, draftStorage, memoryPackStorage, new DeAiRewriteConfigStorage(), null),
                new ContextSelectEngine(cardStorage, canonStorage, draftStorage, null),
                new SessionWebSocketHandler(new ObjectMapper()),
                new TraceWebSocketHandler(new ObjectMapper()),
                null
        );

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("summary", new LinkedHashMap<>(Map.of(
                "title", "古洞与太阴灵机",
                "brief_summary", "王方在拖擦声逼近时进入石缝后的古洞，发现太阴壁刻、受损阵纹与可少量饮用的积水。",
                "volume_id", "V1",
                "word_count", 1800,
                "key_events", List.of(
                        "王方在拖擦声逼近时进入石缝后的古洞。",
                        "古洞中残留石台、断裂石片和受损阵纹。",
                        "王方发现“太阴”壁刻与人形修炼图，并触动残阵引出灰白雾气。"
                ),
                "new_facts", List.of(
                        "王方随身法器遗失，谷底求生只能依赖残余物资",
                        "王方随身水囊几乎见底且补给有限，当前求生资源极度匮乏"
                ),
                "character_state_changes", List.of(
                        "王方：情绪：受伤且紧张，但仍强迫自己冷静；目标：确认谷底异常石面与微光的来源；伤势：后背重伤、肋下内伤；持有：随身法器遗失、磕裂木牌"
                ),
                "open_loops", List.of(
                        "残破阵纹被触动后为何会再次嗡鸣并释放灰白雾气？",
                        "太阴壁刻与人形修炼图究竟对应什么传承？",
                        "阴寒气机为何能压住王方体内闷堵，木牌异动又意味着什么？"
                )
        )));
        analysis.put("facts", List.of(
                Map.of("statement", "发黑石面后的石缝连接一处废弃古洞，洞中残留石台与断裂石片", "confidence", 0.93d),
                Map.of("statement", "古洞地面残留受损阵纹，疑似旧日禁制或阵法残迹", "confidence", 0.92d),
                Map.of("statement", "古洞深处的积水可少量饮用，王方得以补充少量饮水", "confidence", 0.9d),
                Map.of("statement", "古洞石壁残留与“太阴”相关的壁刻和人形修炼图，疑似古修传承线索", "confidence", 0.95d),
                Map.of("statement", "残破阵纹被触动后仍能引发嗡鸣与灰白雾气，古洞禁制尚未彻底失效", "confidence", 0.94d),
                Map.of("statement", "古洞中的阴寒气机可压制并部分冲开王方体内滞涩伤势", "confidence", 0.93d),
                Map.of("statement", "太阴字迹亮起时，王方怀中的木牌同步发生异动", "confidence", 0.89d)
        ));
        analysis.put("timeline_events", List.of());
        analysis.put("character_states", List.of(
                Map.of(
                        "character", "王方",
                        "location", "谷底石缝后的废弃古洞",
                        "emotional_state", "负伤中谨慎试探古洞异象，同时保持高度戒备",
                        "goals", List.of("弄清太阴壁刻与人形修炼图的来历", "查明古洞阵纹与灰白雾气是否仍可触发"),
                        "injuries", List.of("后背重伤", "肋下内伤", "内伤闷堵开始松动"),
                        "inventory", List.of("补入少量清水的水囊", "刻有“阴”字的残片", "发生异动的木牌"),
                        "last_seen", "V1C6"
                )
        ));
        analysis.put("proposals", List.of());

        orchestrator.saveAnalysis("p", "V1C6", analysis, true, false);

        Map<String, Object> pack = memoryPackStorage.lastPack;
        assertNotNull(pack);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pack.get("payload");
        assertNotNull(payload);

        List<String> plannedQueries = stringList(payload.get("planned_queries"));
        String planned = String.join("\n", plannedQueries);
        assertTrue(planned.contains("残破阵纹") || planned.contains("古洞禁制"));
        assertTrue(planned.contains("太阴壁刻") || planned.contains("人形修炼图"));
        assertTrue(planned.contains("阴寒气机") || planned.contains("木牌异动"));
        assertFalse(planned.contains("王方用"));
        assertFalse(planned.contains("仍未"));
        assertFalse(planned.contains("为何"));

        String workingMemory = String.valueOf(payload.getOrDefault("working_memory", ""));
        assertTrue(workingMemory.contains("古洞"));
        assertTrue(workingMemory.contains("太阴"));
        assertFalse(workingMemory.contains("资源极度匮乏\n- 事实: 王方随身法器遗失"));

        ChapterSummary savedSummary = draftStorage.getChapterSummary("p", "V1C6").orElseThrow();
        String newFactsCombined = String.join("\n", savedSummary.getNewFacts());
        assertTrue(newFactsCombined.contains("古洞") || newFactsCombined.contains("阵纹") || newFactsCombined.contains("太阴"));
        assertFalse(newFactsCombined.contains("资源极度匮乏") && newFactsCombined.contains("法器遗失"));

        String stateCombined = String.join("\n", savedSummary.getCharacterStateChanges());
        assertTrue(stateCombined.contains("古洞") || stateCombined.contains("太阴") || stateCombined.contains("补入少量清水"));
    }

    @Test
    void saveAnalysis_keepsPlanningQueriesReadableInsteadOfCompressingThemIntoFragments() throws Exception {
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        TestCanonStorage canonStorage = new TestCanonStorage(tempDir);
        TestCardStorage cardStorage = new TestCardStorage(tempDir);
        CapturingMemoryPackStorage memoryPackStorage = new CapturingMemoryPackStorage(tempDir);
        StaticResearchGateway gateway = new StaticResearchGateway();

        Orchestrator orchestrator = new Orchestrator(
                gateway,
                cardStorage,
                draftStorage,
                canonStorage,
                memoryPackStorage,
                new WriterAgent(gateway, cardStorage, draftStorage, null),
                new ArchivistAgent(gateway, cardStorage, draftStorage, null, canonStorage),
                new EditorAgent(gateway, cardStorage, draftStorage, memoryPackStorage, new DeAiRewriteConfigStorage(), null),
                new ContextSelectEngine(cardStorage, canonStorage, draftStorage, null),
                new SessionWebSocketHandler(new ObjectMapper()),
                new TraceWebSocketHandler(new ObjectMapper()),
                null
        );

        var compactMethod = Orchestrator.class.getDeclaredMethod(
                "compactLocalQuery",
                String.class,
                List.class,
                AdaptiveHintProfile.class
        );
        compactMethod.setAccessible(true);

        String rawPlanningQuery = "本章需明确并固定哪些世界规则、禁忌与代价，才能支撑“黑石面—白光狭缝—废弃古洞—太阴二字发亮—机关触发”这条链条：例如阴属遗迹会被体温/血气/火光/声响中的哪一种惊动，修士能否以神识直接探查，是否存在‘先试探、后接触、不可贸然触字’的禁忌？";
        String planned = String.valueOf(compactMethod.invoke(
                orchestrator,
                rawPlanningQuery,
                List.of("黑石面", "古洞", "太阴", "机关"),
                AdaptiveHintProfile.empty()
        ));
        assertFalse(planned.contains("展开：王方"));
        assertFalse(planned.contains("王方在这"));
        assertFalse(planned.contains("例如黑石"));
        assertTrue(planned.contains("黑石面") || planned.contains("古洞") || planned.contains("太阴"));
        assertTrue(planned.contains("规则") || planned.contains("机关") || planned.contains("壁刻") || planned.contains("禁制"));
    }

    @Test
    void saveAnalysis_openLoopGoalHintStaysGrammatical() throws Exception {
        Orchestrator orchestrator = new Orchestrator(
                new StaticResearchGateway(),
                new TestCardStorage(tempDir),
                new TestDraftStorage(tempDir),
                new TestCanonStorage(tempDir),
                new CapturingMemoryPackStorage(tempDir),
                new WriterAgent(new StaticResearchGateway(), new TestCardStorage(tempDir), new TestDraftStorage(tempDir), null),
                new ArchivistAgent(new StaticResearchGateway(), new TestCardStorage(tempDir), new TestDraftStorage(tempDir), null, new TestCanonStorage(tempDir)),
                new EditorAgent(new StaticResearchGateway(), new TestCardStorage(tempDir), new TestDraftStorage(tempDir), new CapturingMemoryPackStorage(tempDir), new DeAiRewriteConfigStorage(), null),
                new ContextSelectEngine(new TestCardStorage(tempDir), new TestCanonStorage(tempDir), new TestDraftStorage(tempDir), null),
                new SessionWebSocketHandler(new ObjectMapper()),
                new TraceWebSocketHandler(new ObjectMapper()),
                null
        );

        var method = Orchestrator.class.getDeclaredMethod("convertOpenLoopToGoalHint", String.class);
        method.setAccessible(true);
        String converted = String.valueOf(method.invoke(
                orchestrator,
                "谷底深处那截发黑石面与幽冷微光的来源究竟是什么，它是否通向隐藏空间或遗迹？"
        ));

        assertTrue(converted.contains("查明"));
        assertTrue(converted.contains("确认它是否通向隐藏空间或遗迹"));
        assertFalse(converted.contains("来源的真实来历"));
        assertFalse(converted.contains("它确认是否"));
    }

    private ChapterSummary summary(String chapter,
                                   String title,
                                   String brief,
                                   List<String> keyEvents,
                                   List<String> newFacts,
                                   List<String> stateChanges,
                                   List<String> openLoops) {
        ChapterSummary summary = ChapterSummary.builder()
                .chapter(chapter)
                .title(title)
                .briefSummary(brief)
                .volumeId("V1")
                .wordCount(brief.length())
                .build();
        summary.setKeyEvents(keyEvents);
        summary.setNewFacts(newFacts);
        summary.setCharacterStateChanges(stateChanges);
        summary.setOpenLoops(openLoops);
        return summary;
    }

    private void writeFinalDraft(String projectId, String chapter, String content) throws IOException {
        Path path = tempDir.resolve(projectId).resolve("drafts").resolve(chapter).resolve("final.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void writeBindings(String projectId, String chapter, String yaml) throws IOException {
        Path path = tempDir.resolve(projectId).resolve("index").resolve("chapters").resolve(chapter).resolve("bindings.yaml");
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> String.valueOf(item == null ? "" : item).trim()).filter(s -> !s.isBlank()).toList();
    }

    private static final class StaticResearchGateway extends LlmGateway {
        private final Deque<String> responses = new ArrayDeque<>();

        private StaticResearchGateway() {
            super(null);
            responses.add("{\"queries\":[\"谷底 求生\",\"法器 遗失\",\"拖擦声 石面\"],\"note\":\"focus current chapter\"}");
        }

        @Override
        public LlmResponse chat(List<Map<String, String>> messages, String providerId, Double temperature, Integer maxTokens) {
            String content = responses.peekFirst();
            return LlmResponse.builder()
                    .content(content)
                    .provider("mock")
                    .model("mock")
                    .elapsedMs(1L)
                    .totalTokens(1)
                    .build();
        }
    }

    private static class TestDraftStorage extends DraftStorage {
        private final Path root;

        private TestDraftStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
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
    }

    private static class TestCardStorage extends CardStorage {
        private final Path root;

        private TestCardStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }
    }

    private static class CapturingMemoryPackStorage extends MemoryPackStorage {
        private final Path root;
        private Map<String, Object> lastPack;

        private CapturingMemoryPackStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public void writePack(String projectId, String chapter, Map<String, Object> pack) throws IOException {
            this.lastPack = pack;
            super.writePack(projectId, chapter, pack);
        }
    }
}
