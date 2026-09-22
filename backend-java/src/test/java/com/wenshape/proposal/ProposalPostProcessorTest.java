package com.wenshape.proposal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProposalPostProcessorTest {

    @Test
    void finalizeProposals_shouldSortDeduplicateAndLimit() {
        List<Map<String, Object>> raw = new ArrayList<>();
        raw.add(proposal(" 王方 ", "character", 0.61, "", "片段 A"));
        raw.add(proposal("王方", "Character", 0.92, "主角", ""));
        raw.add(proposal("青岚宗", "world", 0.74, "宗门", "来源 1"));
        raw.get(0).put("source_kind", "inline_name");
        raw.get(0).put("evidence_kinds", List.of("inline_name"));
        raw.get(0).put("confidence_explanation", "来自正文边界命中");
        raw.get(1).put("source_kind", "leading_name");
        raw.get(1).put("evidence_kinds", List.of("leading_name", "inline_name"));
        raw.get(1).put("confidence_explanation", "综合得分更高");

        for (int i = 0; i < 10; i++) {
            raw.add(proposal("候选" + i, "World", 0.30 + i * 0.01, "world-" + i, "src-" + i));
        }

        List<Map<String, Object>> result = ProposalPostProcessor.finalizeProposals(raw, 8);

        assertEquals(8, result.size());
        assertEquals("王方", result.get(0).get("name"));
        assertEquals("Character", result.get(0).get("type"));
        assertEquals("主角", result.get(0).get("description"));
        assertEquals("片段 A", result.get(0).get("source_text"));
        assertEquals("leading_name", result.get(0).get("source_kind"));
        assertEquals(List.of("leading_name", "inline_name"), result.get(0).get("evidence_kinds"));
        assertEquals("综合得分更高", result.get(0).get("confidence_explanation"));

        long wangFangCount = result.stream()
                .filter(item -> "Character".equals(item.get("type")) && "王方".equals(item.get("name")))
                .count();
        assertEquals(1, wangFangCount);
    }

    @Test
    void finalizeProposals_shouldDropBlankItems() {
        List<Map<String, Object>> raw = List.of(
                proposal("   ", "Character", 0.8, "x", "x"),
                proposal("王方", " ", 0.9, "x", "x"),
                proposal("王方", "Character", 0.9, "角色", "来源")
        );

        List<Map<String, Object>> result = ProposalPostProcessor.finalizeProposals(raw, 8);

        assertEquals(1, result.size());
        assertEquals("王方", result.get(0).get("name"));
    }

    private Map<String, Object> proposal(String name,
                                         String type,
                                         double confidence,
                                         String description,
                                         String sourceText) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("type", type);
        item.put("confidence", confidence);
        item.put("description", description);
        item.put("source_text", sourceText);
        return item;
    }
}
