package com.moxu.quality;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContinuityProtectionTest {
    private ContinuityProtection.Report review(String source, String draft) {
        return ContinuityProtection.review("book", "V1C2", "V1C1", source, draft);
    }

    @Test void flagsInventedMinutePrecisionWithoutCallingItAConfirmedContradiction() {
        var report = review("第二天一早，辅导员开始点名。", "时间停在点名开始的十三点零四分。");
        assertEquals(1, report.issues().size());
        assertEquals("unverified_clock", report.issues().getFirst().type());
        assertEquals("十三点零四分", report.issues().getFirst().evidence());
        assertEquals("local_rules_only", report.coverage());
        assertTrue(report.issues().getFirst().message().contains("新情节"));
    }

    @Test void normalizesEquivalentClockFormsAndDoesNotFlagDurationsOrChapterNumbers() {
        assertTrue(review("下午一点零四分开始。", "屏幕显示13:04。").issues().isEmpty());
        assertTrue(review("13:04开始。", "十三点零四分，钟响了。").issues().isEmpty());
        assertTrue(review("第二天一早。", "三分钟后，第十三章的纸条掉下来。等了十三小时。").issues().isEmpty());
        assertEquals(1, review("上午九点十分。", "屏幕显示13:04，又闪过十三点零四分。").issues().size());
    }

    @Test void doesNotTreatEveryNewFictionalTimeAsAReasonToRewriteTheManuscript() {
        String text = "两小时后，手机显示13:04。";
        var report = review("早晨他还在教室。", text);
        assertEquals("warning", report.issues().getFirst().severity());
        assertEquals("两小时后，手机显示13:04。", text);
    }

    @Test void warnsAboutReinventedPastCallDurationButNotNewActionsOrEquivalentUnits() {
        var report = review("通话记录里，二十分钟前那通三十秒的未接来电变成了一串零。",
                "昨天傍晚那通长达七分钟的点餐通话，只剩空白。");
        assertEquals(1, report.issues().size());
        assertEquals("unverified_past_duration", report.issues().getFirst().type());
        assertEquals("七分钟", report.issues().getFirst().evidence());
        assertTrue(report.reference_excerpts().contains("三十秒的未接来电"));
        assertTrue(review("之前等了六十秒。", "他记得上次等了一分钟。").issues().isEmpty());
        assertTrue(review("之前等了三十秒。", "他又等了七分钟。").issues().isEmpty());
    }

    @Test void preservesEarlierDurationEvidenceOutsideTheEnding() {
        String past = "通话记录里，二十分钟前那通三十秒的未接来电变成了一串零。";
        assertTrue(ContinuityProtection.anchors(past + "\n" + "x".repeat(2500) + "结尾。",
                "结尾。", 600).contains(past));
    }

    @Test void comparesIdentifierKindsWithoutTruncatingLongIdsOrErasingLeadingZeroes() {
        assertTrue(review("他的学号是十四号。", "学号14号写在证上。").issues().isEmpty());
        assertTrue(review("编号1234567890123456。", "编号1234567890123456仍在。").issues().isEmpty());
        assertEquals(1, review("编号0014。", "编号14。").issues().size());
        assertEquals(1, review("房间号14。", "学号14。").issues().size());
        assertEquals(1, review("编号123456。", "编号123457。").issues().size());
    }

    @Test void capsFindingsButMakesTruncationExplicit() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 45; i++) content.append("编号").append(i).append("。");
        var report = review("早晨。", content.toString());
        assertEquals(30, report.issues().size());
        assertTrue(report.truncated());
    }

    @Test void revisionBindsSourceDraftProjectAndChapter() {
        var a = review("早晨。", "时间13:04。");
        assertNotEquals(a.revision(), review("晚上。", "时间13:04。").revision());
        assertNotEquals(a.revision(), review("早晨。", "时间14:04。").revision());
        assertNotEquals(a.revision(), ContinuityProtection.review("other", "V1C2", "V1C1", "早晨。", "时间13:04。").revision());
        assertNotEquals(a.revision(), ContinuityProtection.review("book", "V1C3", "V1C1", "早晨。", "时间13:04。").revision());
    }

    @Test void firstChapterStillRunsLocalArithmeticWithoutPretendingToHaveAReference() {
        var report = ContinuityProtection.review("book", "V1C1", "", "", "13:04，编号143到147之间缺了一页。");
        assertEquals(1, report.issues().size());
        assertEquals("page_count_mismatch", report.issues().getFirst().type());
        assertEquals("", report.source_chapter());
    }

    @Test void anchorsAreVerbatimDiverseBoundedAndNotDuplicatedFromEnding() {
        String early = "第二天一早，辅导员开始点名。";
        String id = "他的学号是14号。";
        String ending = "他将卡片放入内袋。";
        String source = early + "\n" + "静默的房间。\n".repeat(500) + id + "\n" + ending;
        String anchors = ContinuityProtection.anchors(source, ending, 600);
        assertTrue(anchors.contains(early));
        assertTrue(anchors.contains(id));
        assertFalse(anchors.contains(ending));
        assertTrue(anchors.length() <= 600);
        for (String snippet : anchors.split("\n- ")) assertTrue(source.contains(snippet.replaceFirst("^- ", "")));
        assertEquals("", ContinuityProtection.anchors(source, source, 600));
        assertEquals("", ContinuityProtection.anchors(source, ending, 0));
    }
}
