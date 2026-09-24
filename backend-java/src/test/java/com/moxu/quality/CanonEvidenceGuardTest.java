package com.moxu.quality;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonEvidenceGuardTest {
    @Test void locatesParagraphJoinedEvidenceAndRestoresExactSourceQuote() {
        String source = "他将卡片放进内袋。\r\n\r\n警告写着：别回应点名。\n他躲在讲台下。";
        String quote = "他将卡片放进内袋。警告写着：别回应点名。";
        var fact = Map.of("statement", "卡片已放进内袋", "certainty", "explicit", "evidence", quote);
        var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(fact)), source);
        var verified = CanonEvidenceGuard.maps(result.get("facts"));
        assertEquals(1, verified.size());
        assertEquals("他将卡片放进内袋。\r\n\r\n警告写着：别回应点名。", verified.getFirst().get("evidence"));
        assertTrue(source.contains((String) verified.getFirst().get("evidence")));
        assertEquals(1, CanonEvidenceGuard.maps(CanonEvidenceGuard.apply(result, source).get("facts")).size());
        assertEquals(quote, fact.get("evidence"), "Do not mutate the caller's candidate");
    }

    @Test void whitespaceMatchingDoesNotAcceptRemovedNegationReorderedOrSplicedQuotes() {
        String source = "卡片没有放进内袋。\n他没有应声。\n教室里仍有脚步声。\n掌心已经变冷。";
        for (String quote : List.of("卡片放进内袋。", "掌心已经变冷。卡片没有放进内袋。",
                "他没有应声。掌心已经变冷。", "他已经应声。", "卡片没有放进内袋！")) {
            var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(Map.of(
                    "statement", quote, "certainty", "explicit", "evidence", quote))), source);
            assertTrue(CanonEvidenceGuard.maps(result.get("facts")).isEmpty(), quote);
            assertEquals(1, CanonEvidenceGuard.maps(result.get("pending_facts")).size());
        }
    }

    @Test void wrappedQualifiersCannotBeHiddenByQuotingTheFollowingLine() {
        String source = "像是\n有人已经把卡片偷偷放进内袋。\n其他人还在等待。";
        var fact = Map.of("statement", "有人把卡片放进内袋", "certainty", "explicit",
                "evidence", "有人已经把卡片偷偷放进内袋。");
        var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(fact)), source);
        assertTrue(CanonEvidenceGuard.maps(result.get("facts")).isEmpty());
        assertEquals(1, CanonEvidenceGuard.maps(result.get("pending_facts")).size());
    }

    @Test void keepsLatinWordAndNumericBoundariesAndRejectsWhitespaceOnlyEvidence() {
        String source = "It was not able to move.\n编号是1 4号。";
        for (String quote : List.of("It was notable to move.", "编号是14号。", "  \n \t   ")) {
            var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(Map.of(
                    "statement", quote, "certainty", "explicit", "evidence", quote))), source);
            assertTrue(CanonEvidenceGuard.maps(result.get("facts")).isEmpty());
        }
        var wrapped = Map.of("statement", "It was not able to move.", "certainty", "explicit",
                "evidence", "It was not\nable to move.");
        assertEquals(1, CanonEvidenceGuard.maps(
                CanonEvidenceGuard.apply(Map.of("facts", List.of(wrapped)), source).get("facts")).size());
    }

    @Test void keepsInferredMotiveOutOfCanonEvenWhenQualifierWasOmittedFromQuote() {
        String source = "像是有人要把投保人有意骗保的动机硬塞进档案里。";
        var fact = Map.of("statement", "有人改写日期以制造投保人骗保的假象", "certainty", "explicit",
                "evidence", "投保人有意骗保的动机硬塞进档案里");
        var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(fact)), source);
        assertTrue(((List<?>)result.get("facts")).isEmpty());
        assertEquals(1, ((List<?>)result.get("pending_facts")).size());
    }
    @Test void acceptsLocatedExplicitEvidenceButQuarantinesMissingOrInventedQuotes() {
        String source = "保险单原始生效日期比沉船时间早三天。";
        var explicit = Map.of("statement", source, "certainty", "explicit", "evidence", source);
        var invented = Map.of("statement", "保险单已被改写", "certainty", "explicit", "evidence", "有人改写了保险单日期");
        var result = CanonEvidenceGuard.apply(Map.of("facts", List.of(explicit, invented, Map.of("statement", "没有证据的事实"))), source);
        assertEquals(1, ((List<?>)result.get("facts")).size());
        assertEquals(2, ((List<?>)result.get("pending_facts")).size());
    }
    @Test void detectsSpecificPageArithmeticWithoutMatchingDatesOrInclusiveRanges() {
        assertEquals(1, ProseChecks.check("编号114到117之间缺了两页，编号143到147之间缺了一页。").size());
        assertTrue(ProseChecks.check("页码143至147之间缺了三页。编号1到12之间缺了十页。").isEmpty());
        assertTrue(ProseChecks.check("143到147号包含五页。2026到2028年，缺了一页。编号147到143之间缺了一页。").isEmpty());
    }
}
