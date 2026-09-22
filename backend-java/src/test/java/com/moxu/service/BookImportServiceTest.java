package com.moxu.service;

import com.moxu.model.entity.ChapterSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BookImportServiceTest {

    @Test
    void parseTextBook_detectsVolumesAndChapters() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        String text = """
                第一卷 初入江湖

                第一章 山门之前
                陆玄第一次来到山门之前，抬头望向云海。

                第二章 夜探偏殿
                夜色沉沉，他独自潜入偏殿。

                第二卷 风云再起

                第一章 新的敌人
                城中忽然传来钟鸣，众人神色大变。
                """;

        var parsed = service.parseTextBook("测试书.txt", text);

        assertEquals(2, parsed.volumes().size());
        assertTrue(parsed.explicitVolumes());
        assertEquals("第一卷 初入江湖", parsed.volumes().get(0).title());
        assertEquals(2, parsed.volumes().get(0).chapters().size());
        assertEquals("第一章 山门之前", parsed.volumes().get(0).chapters().get(0).title());
        assertEquals("第二卷 风云再起", parsed.volumes().get(1).title());
        assertEquals(1, parsed.volumes().get(1).chapters().size());
    }

    @Test
    void parseTextBook_fallsBackWhenNoChapterHeading() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            longText.append("这是一个没有显式章节标题的长段落，用来测试导入时的兜底切分逻辑。")
                    .append("主角在城里来回奔走，局势不断变化。\n\n");
        }

        var parsed = service.parseTextBook("长文本.txt", longText.toString());

        assertEquals(1, parsed.volumes().size());
        assertFalse(parsed.explicitVolumes());
        assertTrue(parsed.volumes().get(0).chapters().size() >= 2);
    }

    @Test
    void parseTextBook_usesLargeBlankGapsAsChapterBoundaries() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        String blockA = repeat("第一段剧情持续推进，陆玄在长廊中缓步前行，观察四周动静。", 28);
        String blockB = repeat("第二段剧情另起一章，月姬在偏殿内布置阵法，气氛逐渐紧绷。", 28);

        String text = blockA + "\n\n\n\n" + blockB;

        var parsed = service.parseTextBook("粘贴文本", text);

        assertEquals(1, parsed.volumes().size());
        assertEquals(2, parsed.volumes().get(0).chapters().size(), "应把明显的大空行间隔识别成章节边界");
    }

    @Test
    void computeWorldCardBudget_growsSlowly() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);

        assertEquals(3, service.computeWorldCardBudget(1));
        assertTrue(service.computeWorldCardBudget(10) >= 4);
        assertTrue(service.computeWorldCardBudget(30) <= 10);
        assertEquals(10, service.computeWorldCardBudget(200));
    }

    @Test
    void heuristicCards_prefersCrossChapterCoreEntities() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "山门惊变",
                        "陆玄在合欢宗密室醒来，合欢宗弟子守在门外。",
                        List.of("陆玄察觉身体异变", "合欢宗弟子不敢靠近"),
                        List.of("合欢宗修炼以双修秘法为核心")),
                summary("V1C2", "月姬枯骨",
                        "陆玄踏出密室，月姬枯骨暴露在众人面前，合欢宗上下震动。",
                        List.of("陆玄端坐宝座", "合欢宗众人跪伏"),
                        List.of("月姬曾是合欢宗宗主")),
                summary("V1C3", "搜罗天才",
                        "陆玄命令合欢宗搜罗天赋奇才，并开始筹划魔子布局。",
                        List.of("陆玄下达命令", "合欢宗封锁山门"),
                        List.of("魔子将被用于后续布局"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 5, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) result.get("characters");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> world = (List<Map<String, Object>>) result.get("world");

        List<String> characterNames = characters.stream().map(item -> String.valueOf(item.get("name"))).toList();
        List<String> worldNames = world.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(characterNames.contains("陆玄"), "应识别跨章节反复出现的核心角色");
        assertTrue(worldNames.contains("合欢宗"), "应识别跨章节稳定出现的组织设定");
        assertFalse(characterNames.contains("合欢"), "不应把组织前缀误判成角色");
    }

    @Test
    void heuristicCards_filtersPseudoWorldCardsFromActionPhrases() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "谷仓藏身",
                        "众人将他救出并带到谷仓，在干草堆后暂时藏身。",
                        List.of("她把伤员送到城门外，再折返接应。"),
                        List.of("一行人决定继续潜伏")),
                summary("V1C2", "夜里转移",
                        "为了避开追兵，他们又把同伴带到谷仓深处。",
                        List.of("护卫准备把物资送到城门附近。"),
                        List.of("众人不敢久留"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 5, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> world = (List<Map<String, Object>>) result.get("world");
        List<String> worldNames = world.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(worldNames.isEmpty(), "动作短语截出来的伪地点不应进入世界卡候选");
        assertTrue(worldNames.stream().noneMatch(name -> name.contains("带到") || name.contains("送到") || name.startsWith("在")),
                "不应生成“将他救出并带到谷”“送到城”这类怪卡");
    }

    @Test
    void heuristicCards_keepsStableWorldEntitiesAfterFiltering() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "初到黑石城",
                        "艾尔跟着商队进入黑石城，准备从碎石谷绕路北上。",
                        List.of("黑石城城防森严", "碎石谷夜里常有狼群出没"),
                        List.of("黑石城与碎石谷之间有旧驿道")),
                summary("V1C2", "谷口遇袭",
                        "众人从黑石城出发，在碎石谷谷口遭到伏击。",
                        List.of("黑石城援军迟迟未到", "碎石谷地势狭窄"),
                        List.of("碎石谷是黑石城外的重要通道"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 5, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> world = (List<Map<String, Object>>) result.get("world");
        List<String> worldNames = world.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(worldNames.contains("黑石城"), "正常的跨章节地点仍应保留");
        assertTrue(worldNames.contains("碎石谷"), "正常的跨章节地点仍应保留");
    }

    @Test
    void heuristicCards_filtersNarrativeOpenersAndKeepsFullThreeCharNames() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "互换开始",
                        "秦欢在电话里确认自己与慕茗雪发生了身体互换，两人都难以接受现实。",
                        List.of("秦欢赶去地铁站见慕茗雪", "一边解释情况一边安抚对方"),
                        List.of("慕茗雪的处境让秦欢第一次感到棘手")),
                summary("V1C2", "寝室争执",
                        "慕茗雪回到寝室后一边打游戏一边敷衍室友追问，颜明玉觉得意外，徐嫣则认为她在浪费江风起给予的机会。",
                        List.of("两人发生争执", "最终慕茗雪没有继续争吵"),
                        List.of("颜明玉和江风起都与慕茗雪后续处境有关")),
                summary("V1C3", "余波未平",
                        "面对江风起的邀约，慕茗雪没有立刻回复，秦欢决定先观察局势。",
                        List.of("颜明玉随后提醒她先冷静下来"),
                        List.of("江风起仍然关注慕茗雪的选择"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 8, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) result.get("characters");
        List<String> characterNames = characters.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(characterNames.contains("秦欢"), "正常的两字人名应保留");
        assertTrue(characterNames.contains("慕茗雪"), "三字人名不应被截断成前两字");
        assertTrue(characterNames.contains("颜明玉"), "三字人名不应被截断成前两字");
        assertTrue(characterNames.contains("江风起"), "高频三字人名应被识别");

        assertFalse(characterNames.contains("一边"), "叙事连接词不应变成人物卡");
        assertFalse(characterNames.contains("两人"), "群体称呼不应变成人物卡");
        assertFalse(characterNames.contains("随后"), "时间顺承词不应变成人物卡");
        assertFalse(characterNames.contains("最终"), "总结词不应变成人物卡");
        assertFalse(characterNames.contains("面对"), "动词/介词短语不应变成人物卡");
        assertFalse(characterNames.contains("认为"), "常见谓词不应变成人物卡");
        assertFalse(characterNames.contains("慕茗"), "三字姓名的前两字截断不应落卡");
        assertFalse(characterNames.contains("颜明"), "三字姓名的前两字截断不应落卡");
    }

    @Test
    void heuristicCards_filtersTemporalPhrasesAndOrdinalNarrationButKeepsRealNames() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "寝室余波",
                        "第二天醒来后，连盈先开口打趣，慕茗雪没有接话。",
                        List.of("第五层宿舍楼的走廊里，连盈又问她要不要一起出门。"),
                        List.of("连盈是寝室里最爱调侃气氛的人")),
                summary("V1C2", "中午电话",
                        "次日中午，连盈和颜明玉一起回寝室，昨天的争执仍让人尴尬。",
                        List.of("第二天的别扭还没散去，但连盈还是主动递来了奶茶。"),
                        List.of("连盈依旧把慕茗雪当室友对待"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 8, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) result.get("characters");
        List<String> characterNames = characters.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(characterNames.contains("连盈"), "真实反复出现的人名应保留");
        assertFalse(characterNames.contains("第二天"), "时间短语不应变成人物卡");
        assertFalse(characterNames.contains("次日"), "时间顺承词不应变成人物卡");
        assertFalse(characterNames.contains("昨天"), "时间指代词不应变成人物卡");
        assertFalse(characterNames.contains("第五层"), "序数叙事短语不应因“第”被误判成人名");
    }

    @Test
    void importedCards_reclassifyHumanLikeWorldCardsIntoCharacters() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C12", "赴约",
                        "主角前去见刘局，在茶庄里感受到强烈压迫感。",
                        List.of("刘局继续试探主角"),
                        List.of("刘局与主角之间旧怨未消")),
                summary("V1C13", "勾引",
                        "吴帆提到刘雨菲，称她是刘家大小姐，主角意识到她背后牵着刘家关系。",
                        List.of("刘雨菲即将登场"),
                        List.of("刘雨菲和刘局都与警方系统有关")),
                summary("V1C14", "摊牌",
                        "刘雨菲试图说服主角，双方展开危险谈判。",
                        List.of("刘雨菲继续拖延时间"),
                        List.of("刘雨菲与主角关系更加复杂"))
        );

        Map<String, Object> parsed = Map.of(
                "characters", List.of(),
                "world", List.of(Map.of(
                        "name", "刘局/刘雨菲",
                        "description", "警方势力代表。刘局是鹏城警方高层，势力庞大且手段狠辣；刘雨菲是其女儿，与主角有情感纠葛。",
                        "aliases", List.of("刘局", "刘雨菲", "刘小姐"),
                        "category", "organization",
                        "rules", List.of(),
                        "stars", 2
                ))
        );

        Map<String, Object> corrected = service.correctImportedCardsForTesting(parsed, summaries, 9, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) corrected.get("characters");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> world = (List<Map<String, Object>>) corrected.get("world");

        List<String> characterNames = characters.stream().map(item -> String.valueOf(item.get("name"))).toList();
        List<String> worldNames = world.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(characterNames.contains("刘雨菲"), "合并到 world 的人物名应被纠偏回角色卡");
        assertFalse(worldNames.contains("刘局/刘雨菲"), "明显的人名合并卡不应继续留在 world");
    }

    @Test
    void normalizeImportedChapterTitle_collapsesDuplicatedChapterMarkers() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);

        String normalized = service.normalizeImportedChapterTitleForTesting(
                "第1章 第一章 妻子出轨",
                1,
                "测试书.txt",
                "测试书"
        );

        assertEquals("第一章 妻子出轨", normalized);
    }

    @Test
    void finalizeImportedCards_filtersUnsupportedBareConceptWorldCard() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "诡异遭遇",
                        "男主深夜回家时在小区隐秘小路上遭遇诡异红衣少女。",
                        List.of("男主获得法术【易形】。"),
                        List.of("易形会让目标与其最近一次性对象逐步交换肉身。"))
        );

        Map<String, Object> parsed = Map.of(
                "characters", List.of(),
                "world", List.of(
                        Map.of(
                                "name", "易形",
                                "description", "男主获得法术【易形】，作用是让目标与其最近一次性对象逐步交换肉身。",
                                "aliases", List.of(),
                                "category", "concept",
                                "rules", List.of(),
                                "stars", 2
                        )
                )
        );

        Map<String, Object> result = service.finalizeImportedCardsForTesting(parsed, summaries, 6, 6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> world = (List<Map<String, Object>>) result.get("world");
        List<String> worldNames = world.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertFalse(worldNames.contains("易形"), "没有启发式支撑、也不具备结构后缀的裸概念词不应直接落成设定卡");
    }

    @Test
    void representativeExcerpts_prefersNarrativeParagraphsOverMetaSummaryLines() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<String> contents = List.of(
                """
                本章主要讲述主角进入宗门后的变化，这是一个过渡章节。

                陆玄抬手掀开帘幕，夜风裹着冷香扑面而来，檐角铜铃一声声敲进长廊深处，他站在月色里没有回头。

                总结来看，这一章主要服务于后续剧情铺垫。
                """,
                """
                这一章重点是解释背景设定和人物关系。

                月姬指尖轻轻拂过玉案，笑意却没有落进眼底，灯火在她侧脸跳动，将整座偏殿照得忽明忽暗。
                """
        );

        List<String> excerpts = service.representativeExcerptsForTesting(contents, 3);

        assertFalse(excerpts.isEmpty(), "应至少选出一个代表性片段");
        assertTrue(excerpts.stream().anyMatch(text -> text.contains("夜风") || text.contains("灯火")),
                "应优先保留叙事性强的描写段落");
        assertTrue(excerpts.stream().noneMatch(text -> text.startsWith("本章主要讲述") || text.startsWith("总结来看")),
                "不应优先选择元说明/摘要腔段落");
    }

    @Test
    void heuristicCards_filtersNarrativeResidueFromCharacterNames() {
        BookImportService service = new BookImportService(null, null, null, null, null, null, null, null);
        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "不肯熄灭的念头",
                        "王方结束一天在旧渠疏墙、挖淤的苦活后回到屋中。",
                        List.of("王方没多看她，只是把门推开。", "白天在旧渠那边看见你的人很多。"),
                        List.of("王方只是外门杂务弟子。")),
                summary("V1C2", "旧渠边",
                        "王方低声道要先进去，白天过去之后街上的声音慢慢淡了。",
                        List.of("她抬头时，王方已经站在门边。"),
                        List.of("王方仍住在简陋寒酸的屋中。"))
        );

        Map<String, Object> result = service.heuristicCardsForTesting(summaries, 8, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> characters = (List<Map<String, Object>>) result.get("characters");
        List<String> names = characters.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(names.contains("王方"), "应识别真实人名王方");
        assertFalse(names.contains("王方没"), "不应把叙事残字吞进人名");
        assertFalse(names.contains("白天"), "时间词不应误判成人名");
    }

    private ChapterSummary summary(String chapter,
                                   String title,
                                   String brief,
                                   List<String> events,
                                   List<String> facts) {
        return ChapterSummary.builder()
                .chapter(chapter)
                .title(title)
                .briefSummary(brief)
                .keyEvents(events)
                .newFacts(facts)
                .characterStateChanges(List.of())
                .openLoops(List.of())
                .build();
    }

    private String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
