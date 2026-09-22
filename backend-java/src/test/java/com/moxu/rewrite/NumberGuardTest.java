package com.moxu.rewrite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class NumberGuardTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "价格为300元。|价格为500元。|价格为300元。",
            "预算为3千元。|预算为3万元。|预算为3千元。",
            "他说不是三万，是三千。|他说不是3万，是302。夜色渐深。|他说不是三万，是三千。夜色渐深。",
            "金额为壹仟贰佰元整。|金额为一千三百元。|金额为壹仟贰佰元整。",
            "余额为－１，２００．５０元。|余额为+1200.50元。|余额为－１，２００．５０元。",
            "温度为−3.5℃。|温度为+3.5℉。|温度为−3.5℃。",
            "折扣为百分之三点五。|折扣为35%。|折扣为百分之三点五。",
            "占比为三分之一。|占比为1/2。|占比为三分之一。",
            "误差为±0.5%。|误差为0.8‰。|误差为±0.5%。",
            "重量为≤3kg。|重量为≥3mg。|重量为≤3kg。",
            "会议定在2026年9月19日。|会议定在2026-09-20。|会议定在2026年9月19日。",
            "会议定在二〇二六年九月十九日。|会议定在2027/09/20。|会议定在二〇二六年九月十九日。",
            "到场时间为下午3点半。|到场时间为上午4点半。|到场时间为下午3点半。",
            "到场时间为14:30:05。|到场时间为15:40:06。|到场时间为14:30:05。",
            "时间为2026-09-19 14:30。|时间为2027-10-20 15:40。|时间为2026-09-19 14:30。",
            "他住在302号房。|他住在303号房。夜风吹过。|他住在302号房。夜风吹过。",
            "房号是三〇二。|房号是三〇三。|房号是三〇二。",
            "他记下了三。|他记下了四。|他记下了三。",
            "他记下了廿。|他记下了卅。|他记下了廿。",
            "他记下了卅。|他记下了卌。|他记下了卅。",
            "他记下了卌。|他记下了廿。|他记下了卌。",
            "纸上写下七。|纸上写下八。|纸上写下七。",
            "请阅读第三章。|请阅读第4章。|请阅读第三章。",
            "他带着三把刀。|他带着四把刀。|他带着三把刀。",
            "订单编号为A-302。|订单编号为B-303。|订单编号为A-302。",
            "范围为3-5万元。|范围为6-8千元。|范围为3-5万元。",
            "价钱为三点五元。|价钱为四点五角。|价钱为三点五元。",
            "张三给李四300元。|张三给李四500元。|张三给李四300元。",
            "应付金额为￥300。|应付金额为$500。|应付金额为￥300。"
    })
    void repairsOnlyAlignedExpressions(String original, String generated, String expected) {
        var result = NumberGuard.protect(original, generated);
        assertFalse(result.preservedOriginal(), () -> result.warnings().toString());
        assertEquals(expected, result.text());
        assertFalse(result.repairs().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertEquals(java.util.Set.of("original", "generated", "context"), result.repairs().getFirst().keySet());
    }

    @Test
    void alignsReorderedClausesByActorAndActionRatherThanPosition() {
        var result = NumberGuard.protect("甲给乙300元，乙还甲50元。", "乙还甲60元，甲给乙500元。夜深了。");
        assertFalse(result.preservedOriginal());
        assertEquals("乙还甲50元，甲给乙300元。夜深了。", result.text());
        assertEquals("50元", result.repairs().getFirst().get("original"));
    }

    @Test
    void allowsSmallProseInsertionsInLongOrderedAnchors() {
        var result = NumberGuard.protect("小张递给老板300元。", "小张悄悄递给老板500元。夜深了。");
        assertEquals("小张悄悄递给老板300元。夜深了。", result.text());
        assertFalse(result.preservedOriginal());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "甲给乙300元，乙还甲50元。|乙给甲500元，甲还乙60元。|无法定位",
            "小张给小李300元。|小王给小李500元。|无法定位",
            "张三给李四300元。|张三不给李四500元。|无法定位",
            "张三给李四300元。|张三给王李四500元。|无法定位",
            "张三给李四300元。|张三给李五500元。|无法定位",
            "他付300元。|他沉默了。|遗漏",
            "他沉默了。|他付300元。|新增",
            "他付300元。|他付300元，又付300元。|重复",
            "他付300元，另付50元。|他付300元，又付300元。|无法定位",
            "给他3元，给他5元。|给他4元，给他6元。|多解",
            "给他3元，给他3元。|给他4元，给他4元。|多解",
            "金额为3元。|楼高4米。|无法定位",
            "300|500|无法定位"
    })
    void conservativelyFallsBackWithoutPartialRepairs(String original, String generated, String reason) {
        var result = NumberGuard.protect(original, generated);
        assertTrue(result.preservedOriginal());
        assertEquals(original, result.text());
        assertTrue(result.repairs().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains(reason)), result.warnings().toString());
    }

    @Test
    void repeatedValuesWithDistinctContextsRemainRepairable() {
        var result = NumberGuard.protect("甲交3元，乙交3元。", "乙交5元，甲交4元。");
        assertFalse(result.preservedOriginal());
        assertEquals("乙交3元，甲交3元。", result.text());
    }

    @Test
    void ignoresIdiomsRatherThanTreatingThemAsCriticalAmounts() {
        var result = NumberGuard.protect("一抹月光。万一失败，他会一无所获。", "月光洒落。倘若失败，他将空手而归。");
        assertFalse(result.preservedOriginal());
        assertEquals("月光洒落。倘若失败，他将空手而归。", result.text());
        assertTrue(result.repairs().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unchangedTextIsANoopEvenWithRepeatedAmbiguousNumbers() {
        String text = "给他3元，给他3元。";
        var result = NumberGuard.protect(text, text);
        assertEquals(text, result.text());
        assertFalse(result.preservedOriginal());
        assertTrue(result.repairs().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unchangedNumbersDoNotClaimSemanticValidation() {
        var result = NumberGuard.protect("他付300元。晴空万里。", "他付300元。天色阴沉。");
        assertFalse(result.preservedOriginal());
        assertEquals("他付300元。天色阴沉。", result.text());
        assertTrue(result.repairs().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void unchangedNumericSequenceAllowsRephrasedContextWithoutWarnings() {
        String original = "他付了120元，买了三本书，然后沿着河岸慢慢走回家。";
        String generated = "他花了120元，买了三本书，随后顺着河岸缓缓走回家。";
        var result = NumberGuard.protect(original, generated);
        assertEquals(generated, result.text());
        assertFalse(result.preservedOriginal());
        assertTrue(result.repairs().isEmpty());
        assertTrue(result.warnings().isEmpty());
        assertTrue(NumberGuard.protect(original, generated.replace("120", "150")).preservedOriginal());
        var repaired = NumberGuard.protect(original, generated.replace("花了120", "付了150"));
        assertEquals(generated.replace("花了", "付了"), repaired.text());
        assertFalse(repaired.preservedOriginal());
        assertEquals(1, repaired.repairs().size());
        assertTrue(repaired.warnings().isEmpty());
    }

    @Test
    void missingGenerationKeepsOriginalAndResultsAreImmutable() {
        var result = NumberGuard.protect("余额3元", null);
        assertTrue(result.preservedOriginal());
        assertEquals("余额3元", result.text());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().clear());
    }
}
