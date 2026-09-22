package com.moxu.nlp;

import java.util.List;

/**
 * 分析期动态 hint 档案。
 *
 * <p>目标不是把“当前小说热词”写死进 Agent 常量，而是把 hint 拆成三层：
 * <ul>
 *     <li>generic：全局通用叙事语义词</li>
 *     <li>genre：题材层常用词</li>
 *     <li>project：项目自适应高频术语</li>
 * </ul>
 *
 * <p>各 Agent 使用 discovery / mechanism / durable / state / query 五类组合后的 hint，
 * 但保留原始三层，方便后续调试与扩展。</p>
 */
public record AdaptiveHintProfile(
        String genre,
        List<String> genericTerms,
        List<String> genreTerms,
        List<String> projectTerms,
        List<String> discoveryHints,
        List<String> mechanismHints,
        List<String> durableHints,
        List<String> stateHints,
        List<String> queryHints,
        List<String> carryoverHints
) {

    private static final AdaptiveHintProfile EMPTY = new AdaptiveHintProfile(
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
    );

    public static AdaptiveHintProfile empty() {
        return EMPTY;
    }
}
