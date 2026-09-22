package com.moxu.quality;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CanonEvidenceGuardTest {
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
