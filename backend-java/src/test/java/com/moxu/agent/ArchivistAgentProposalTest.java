package com.moxu.agent;

import com.moxu.llm.LlmGateway;
import com.moxu.storage.CanonStorage;
import com.moxu.storage.CardStorage;
import com.moxu.storage.DraftStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivistAgentProposalTest {

    private final ArchivistAgent agent = new ArchivistAgent(
            new LlmGateway(null),
            new CardStorage(),
            new DraftStorage(),
            null,
            new CanonStorage()
    );

    @Test
    void detectSettingChanges_addsHighConfidenceCharacterProposals() {
        String draft = """
                刘局站在门口看着刘雨菲，脸色很沉。
                刘雨菲抿着嘴，抬头看向刘局：“你早就知道？”
                刘局沉声道：“先回车上。”
                刘雨菲没有再顶嘴，只是跟着刘局往外走。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        List<String> characterNames = proposals.stream()
                .filter(item -> "Character".equals(item.get("type")))
                .map(item -> String.valueOf(item.get("name")))
                .collect(Collectors.toList());

        assertTrue(characterNames.contains("刘局"));
        assertTrue(characterNames.contains("刘雨菲"));
        assertFalse(characterNames.contains("小心"));
        assertFalse(characterNames.contains("两人"));
        assertFalse(characterNames.contains("刘局/刘雨菲"));
    }

    @Test
    void detectSettingChanges_doesNotBlockCharacterProposalWhenSameNameExistsInWorldCards() {
        String draft = """
                刘局看了她一眼，没有立刻说话。
                刘局把车钥匙放在桌上，示意她坐下。
                她抬头时，刘局已经把档案推了过去。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of("刘局"));

        boolean hasCharacterLiuJu = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "刘局".equals(item.get("name")));

        assertTrue(hasCharacterLiuJu);
    }

    @Test
    void detectSettingChanges_recallsStrongTitleCharactersInsideClauses() {
        String draft = """
                她刚坐下，王总就把文件推了过来。
                对面的李警官没有插话，只是盯着王总。
                王总沉默了几秒，才让李警官先出去。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        boolean hasWangZong = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王总".equals(item.get("name")));
        boolean hasLiJingGuan = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "李警官".equals(item.get("name")));
        String wangZongSourceKind = proposals.stream()
                .filter(item -> "Character".equals(item.get("type")) && "王总".equals(item.get("name")))
                .map(item -> String.valueOf(item.get("source_kind")))
                .findFirst()
                .orElse("");
        String wangZongConfidenceExplanation = proposals.stream()
                .filter(item -> "Character".equals(item.get("type")) && "王总".equals(item.get("name")))
                .map(item -> String.valueOf(item.get("confidence_explanation")))
                .findFirst()
                .orElse("");

        assertTrue(hasWangZong);
        assertTrue(hasLiJingGuan);
        assertEquals("titled_name", wangZongSourceKind);
        assertTrue(wangZongConfidenceExplanation.contains("综合得分"));
    }

    @Test
    void detectSettingChanges_doesNotTreatAbstractConceptAsCharacter() {
        String draft = """
                她这次施展易形之后，连声音都变了。
                易形本身并不稳定，稍有分神就会露出破绽。
                他担心易形在关键时刻失效，只能继续拖延时间。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasYiXing = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "易形".equals(item.get("name")));

        assertFalse(hasYiXing);
    }

    @Test
    void detectSettingChanges_extractsSpecificWorldNameInsteadOfSwallowedPhrase() {
        String draft = """
                他被一路押到青岚宗，才终于看清那座山门的模样。
                青岚宗的弟子守在门外，没有任何人敢擅闯。
                她抬头望向青岚宗主殿，心里第一次生出退意。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasQingLanZong = proposals.stream()
                .anyMatch(item -> "World".equals(item.get("type")) && "青岚宗".equals(item.get("name")));
        boolean hasSwallowedPhrase = proposals.stream()
                .anyMatch(item -> "World".equals(item.get("type")) && String.valueOf(item.get("name")).contains("押到青岚宗"));
        boolean misclassifiedAsCharacter = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "青岚宗".equals(item.get("name")));
        @SuppressWarnings("unchecked")
        List<String> qingLanZongEvidenceKinds = proposals.stream()
                .filter(item -> "World".equals(item.get("type")) && "青岚宗".equals(item.get("name")))
                .map(item -> (List<String>) item.getOrDefault("evidence_kinds", List.of()))
                .findFirst()
                .orElse(List.of());
        String qingLanZongConfidenceExplanation = proposals.stream()
                .filter(item -> "World".equals(item.get("type")) && "青岚宗".equals(item.get("name")))
                .map(item -> String.valueOf(item.get("confidence_explanation")))
                .findFirst()
                .orElse("");

        assertTrue(hasQingLanZong);
        assertFalse(hasSwallowedPhrase);
        assertFalse(misclassifiedAsCharacter);
        assertTrue(qingLanZongEvidenceKinds.contains("world_suffix_rule"));
        assertTrue(qingLanZongConfidenceExplanation.contains("按重复次数折算置信度"));
    }

    @Test
    void detectSettingChanges_doesNotPromoteGenericTechniqueNameToWorldCard() {
        String draft = """
                她一连用了两次邪术，脸色一下白了下去。
                这种邪术代价极大，稍有失手就会反噬自身。
                他盯着她的手腕，知道那根本不是什么能轻易承受的邪术。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasXieShu = proposals.stream()
                .anyMatch(item -> "World".equals(item.get("type")) && "邪术".equals(item.get("name")));

        assertFalse(hasXieShu);
    }

    @Test
    void detectSettingChanges_trimsTrailingNarrativeCharFromLeadingCharacterCandidate() {
        String draft = """
                王方没多看她，只是把门推开。
                王方低声道：“进去。”
                她抬头时，王方已经站在门边。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasWangFang = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方".equals(item.get("name")));
        boolean hasDirtyWangFangMei = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方没".equals(item.get("name")));

        assertTrue(hasWangFang);
        assertFalse(hasDirtyWangFangMei);
    }

    @Test
    void detectSettingChanges_doesNotTreatBaiTianAsCharacter() {
        String draft = """
                白天在旧渠边看见你的人很多，可到了晚上就只剩她一个。
                白天过去之后，街上的声音慢慢淡了。
                他没再提起白天的事，只是把窗帘拉上。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasBaiTian = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "白天".equals(item.get("name")));

        assertFalse(hasBaiTian);
    }

    @Test
    void detectSettingChanges_keepsRepeatedBareTwoCharNameAfterTrimmingNarrativeResidue() {
        String draft = """
                王方没多看她，只是把门推开。
                王方没接话，只站在门口等她。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasWangFang = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方".equals(item.get("name")));
        boolean hasDirty = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方没".equals(item.get("name")));

        assertTrue(hasWangFang);
        assertFalse(hasDirty);
    }

    @Test
    void detectSettingChanges_extractsWangFangFromChapterLikeNarrative() {
        String draft = """
                王方回到住处时，天色已经沉下去一截。
                同屋的人还没回来，或是回了别处蹭热水去了。王方没多看，把腰间木牌摘下，压到桌边。
                手背上今天被细砂打出的红痕还在。王方把手在衣角上擦干，翻出一小块干粮，慢慢吃了。
                王方坐在床沿，听着门外飘过去的话，手上动作没停。
                王方把门掩上，盘膝坐到床板上。
                王方的气走得很慢。
                王方没见过那种半月连升的人，倒是见过几个吃了不该吃的东西的人。
                王方没睁眼，只把气息稳住，等一圈走完，才缓缓吐出一口浊气。
                王方睁开眼，把呼吸放平：“坐一会儿，睡得踏实些。”
                王方“嗯”了一声，重新闭上眼。
                王方睫毛动了动，没出声。
                王方没接这话，只重新沉下心神。
                王方眉头微皱，指节绷了一下。
                王方都见过。见多了，盘坐时反倒不容易乱。
                王方停了片刻，没急着动。
                王方抬手揉了揉发僵的膝盖，动作很轻。
                王方已经下了床，把压在桌边的木牌拿在手里。
                王方手指一紧，木牌边缘一下硌进掌心裂口里。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasWangFang = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方".equals(item.get("name")));
        boolean hasDirty = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && String.valueOf(item.get("name")).contains("王方没"));

        assertTrue(hasWangFang);
        assertFalse(hasDirty);
    }

    @Test
    void detectSettingChanges_doesNotEmitNarrativeFragmentsAsCharacterOrWorld() {
        String draft = """
                王方原本盘腿坐在床沿，才把一口气沉到半截，脚下忽然一抖，像是整座山被什么东西猛地推了一把。
                这回他看见一道黑线贴着山体窜过，所过之处屋瓦、栏杆、树干同时断开。
                话音刚落，远处山道上突然冲下来几个人，边跑边嘶声喊：“别上去！”
                他立刻朝更偏的边缘小道去，刚迈出两步，就听见后头有人斗法的闷响。
                王方脚下一顿，王方只觉眼前整片山道都白了。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        List<String> names = proposals.stream()
                .map(item -> String.valueOf(item.get("name")))
                .toList();

        assertFalse(names.contains("边缘小"));
        assertFalse(names.contains("后头"));
        assertFalse(names.contains("别上去"));
        assertFalse(names.contains("王方脚"));
        assertFalse(names.contains("王方只"));
        assertFalse(names.contains("远处山"));
        assertFalse(names.contains("贴着山"));
        assertFalse(names.contains("像是整座山"));
        assertFalse(names.contains("是整座山"));
    }

    @Test
    void detectSettingChanges_rejectsRepeatedPseudoNamesFromDisasterScene() {
        String draft = """
                高处云层像被什么东西硬生生划开，一道惨白光痕从群山后头掠过去，照得半边山壁都泛起死冷的颜色。
                话音刚落，远处山道上突然冲下来几个人，像是从更高处逃下来的，身上全是灰，有一个人半边袖子都烧没了，边跑边嘶声喊：“别上去！”
                王方被震得往后滑了半步，他死死攥住旁边凸出的石角，掌心一下磨出血。
                另一个人同样身上全是灰，几乎是滚着扑下来，嘴里还是那句“别上去”。
                第三个人从更高处逃下，身上全是灰，扑到石角旁边时膝盖一软。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        List<String> names = proposals.stream()
                .map(item -> String.valueOf(item.get("name")))
                .toList();

        assertFalse(names.contains("全是"));
        assertFalse(names.contains("石角"));
        assertFalse(names.contains("别上去"));
        assertFalse(names.contains("远处山"));
        assertFalse(names.contains("半边山"));
    }

    @Test
    void detectSettingChanges_keepsRepeatedInlineBareNameAfterNerTightening() {
        String draft = """
                她抬头时，王方已经站在门边，没有再给她解释的机会。
                没等她开口，王方便把门重新关上，只让她先坐下。
                她还想追问，可王方只是看了她一眼，示意她安静。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasWangFang = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "王方".equals(item.get("name")));

        assertTrue(hasWangFang);
    }

    @Test
    void detectSettingChanges_doesNotTreatXiaoDaoScenePhraseAsCharacter() {
        String draft = """
                他猛地转身，朝更偏的边缘小道去。
                夜风越刮越硬，带着湿冷的腥气，像山下深谷里翻上来的。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());

        boolean hasBianYuanXiao = proposals.stream()
                .anyMatch(item -> "Character".equals(item.get("type")) && "边缘小".equals(item.get("name")));

        assertFalse(hasBianYuanXiao);
    }

    @Test
    void detectSettingChanges_doesNotTreatNarrativeMountainPhraseAsWorld() {
        String draft = """
                王方原本盘腿坐在床沿，脚下忽然一抖，像是整座山被什么东西猛地推了一把。
                紧跟着，像是整座山都在往下塌，耳边全是碎石滚落的响动。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        List<String> worldNames = proposals.stream()
                .filter(item -> "World".equals(item.get("type")))
                .map(item -> String.valueOf(item.get("name")))
                .toList();

        assertFalse(worldNames.contains("像是整座山"));
        assertFalse(worldNames.contains("是整座山"));
    }

    @Test
    void detectSettingChanges_rejectsLeadingNounPhraseAndWeakSuffixPhraseFromValleyScene() {
        String draft = """
                石面不再全是松散碎石，偶尔会露出几块较平整的岩面，边缘竟有些过于笔直。
                他沿着另一块石面慢慢挪过去，脚下的路渐渐往下斜，偶尔会反出一点湿冷的光。
                王方扶着那块石面站稳，没有再往上硬爬。
                """;

        List<Map<String, Object>> proposals = agent.detectSettingChanges(draft, List.of(), List.of());
        List<String> names = proposals.stream()
                .map(item -> String.valueOf(item.get("name")))
                .toList();

        assertFalse(names.contains("石面"));
        assertFalse(names.contains("偶尔会"));
    }
}
