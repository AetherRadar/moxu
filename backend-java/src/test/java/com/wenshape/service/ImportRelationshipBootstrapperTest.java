package com.wenshape.service;

import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.CharacterCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportRelationshipBootstrapperTest {

    private final ImportRelationshipBootstrapper bootstrapper = new ImportRelationshipBootstrapper();

    @Test
    void shouldBuildAliasesMentionsAndRelationCandidatesFromImportedChapters() {
        List<ImportRelationshipBootstrapper.ChapterSource> chapters = List.of(
                new ImportRelationshipBootstrapper.ChapterSource(
                        "V1C1",
                        "父女谈话",
                        "刘局看着女儿刘雨菲，语气很冷。\"雨菲，你先坐。\"刘局说道。刘雨菲没有动，只是盯着父亲。"
                ),
                new ImportRelationshipBootstrapper.ChapterSource(
                        "V1C2",
                        "回家",
                        "阿强回到家，妻子柳月给他递了杯水。柳月抱住阿强，低声说你别总逞强。阿强点了点头。"
                ),
                new ImportRelationshipBootstrapper.ChapterSource(
                        "V1C3",
                        "对峙",
                        "阿强在办公室门口拦住刘雨菲。\"你还要继续查？\"阿强问。刘雨菲冷笑，推开阿强，两人当场吵了起来。"
                )
        );

        List<ChapterSummary> summaries = List.of(
                summary("V1C1", "父女谈话", "刘局与女儿刘雨菲谈话，父女气氛僵硬。",
                        List.of("刘局与女儿刘雨菲谈话，关系紧张。")),
                summary("V1C2", "回家", "阿强回家后与妻子柳月互相安慰。",
                        List.of("阿强与妻子柳月抱在一起，相互照应。")),
                summary("V1C3", "对峙", "阿强与刘雨菲正面对峙，冲突升级。",
                        List.of("阿强与刘雨菲爆发争吵，对立升级。"))
        );

        List<CharacterCard> cards = List.of(
                character("刘局"),
                character("刘雨菲"),
                character("阿强"),
                character("柳月")
        );

        ImportRelationshipBootstrapper.BootstrapResult result = bootstrapper.build(chapters, summaries, cards);

        @SuppressWarnings("unchecked")
        Map<String, Object> aliasCharacters = (Map<String, Object>) result.aliasIndex().get("characters");
        @SuppressWarnings("unchecked")
        Map<String, Object> liuYuFei = (Map<String, Object>) aliasCharacters.get("刘雨菲");
        @SuppressWarnings("unchecked")
        List<String> liuYuFeiAliases = (List<String>) liuYuFei.get("aliases");
        assertTrue(liuYuFeiAliases.contains("雨菲"), "应从唯一给名推导出“雨菲”别名");

        @SuppressWarnings("unchecked")
        Map<String, Object> chapterMap = (Map<String, Object>) result.chapterMentionIndex().get("chapters");
        @SuppressWarnings("unchecked")
        Map<String, Object> chapter3 = (Map<String, Object>) chapterMap.get("V1C3");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chapter3Characters = (List<Map<String, Object>>) chapter3.get("characters");
        assertFalse(chapter3Characters.isEmpty(), "V1C3 应有章节人物索引");
        assertTrue(chapter3Characters.stream().anyMatch(item -> "阿强".equals(item.get("name"))));
        assertTrue(chapter3Characters.stream().anyMatch(item -> "刘雨菲".equals(item.get("name"))));

        Map<String, Object> fatherDaughter = findPair(result, "刘局", "刘雨菲");
        assertNotNull(fatherDaughter, "应识别刘局与刘雨菲的关系候选");
        assertEquals("亲属/家庭", fatherDaughter.get("dominant_label"));

        Map<String, Object> couple = findPair(result, "阿强", "柳月");
        assertNotNull(couple, "应识别阿强与柳月的关系候选");
        assertEquals("亲密倾向", couple.get("dominant_label"));

        Map<String, Object> conflict = findPair(result, "阿强", "刘雨菲");
        assertNotNull(conflict, "应识别阿强与刘雨菲的关系候选");
        assertEquals("对立/冲突", conflict.get("dominant_label"));

        assertEquals(4, result.stats().get("characters_indexed"));
        assertEquals(3, result.stats().get("chapters_indexed"));
    }

    private ChapterSummary summary(String chapter, String title, String briefSummary, List<String> keyEvents) {
        return ChapterSummary.builder()
                .chapter(chapter)
                .title(title)
                .briefSummary(briefSummary)
                .keyEvents(new ArrayList<>(keyEvents))
                .newFacts(new ArrayList<>())
                .characterStateChanges(new ArrayList<>())
                .openLoops(new ArrayList<>())
                .build();
    }

    private CharacterCard character(String name) {
        return CharacterCard.builder()
                .name(name)
                .aliases(new ArrayList<>())
                .description(name + " 的角色卡")
                .stars(2)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPair(ImportRelationshipBootstrapper.BootstrapResult result, String left, String right) {
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) result.relationCandidateIndex().get("pairs");
        return pairs.stream()
                .filter(item -> (left.equals(item.get("left")) && right.equals(item.get("right")))
                        || (left.equals(item.get("right")) && right.equals(item.get("left"))))
                .findFirst()
                .orElse(null);
    }
}
