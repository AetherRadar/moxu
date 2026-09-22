package com.moxu.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DraftControllerTitleNormalizationTest {

    private final DraftController controller = new DraftController(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    @Test
    void normalizeAcceptedTitleStripsRepeatedChapterMarkers() {
        String normalized = ReflectionTestUtils.invokeMethod(
                controller,
                "normalizeAcceptedTitle",
                "第13章 第十三章 勾引",
                "测试项目",
                "V1C13",
                ""
        );

        assertEquals("勾引", normalized);
    }

    @Test
    void normalizeAcceptedTitleStripsTitleLabelAndRepeatedChapterMarkersTogether() {
        String normalized = ReflectionTestUtils.invokeMethod(
                controller,
                "normalizeAcceptedTitle",
                "章节标题：第13章 第十三章 勾引",
                "测试项目",
                "V1C13",
                ""
        );

        assertEquals("勾引", normalized);
    }

    @Test
    void normalizeAcceptedTitleRejectsBareChapterHeading() {
        String normalized = ReflectionTestUtils.invokeMethod(
                controller,
                "normalizeAcceptedTitle",
                "第十三章",
                "测试项目",
                "V1C13",
                ""
        );

        assertEquals("", normalized);
    }
}
