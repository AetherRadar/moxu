package com.wenshape.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * 润色配置。保留旧算法字段以兼容已保存的项目；单次润色仅使用 engine。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeAiRewriteConfig {

    private String engine;
    private Integer intensity;
    private Integer beamWidth;
    private Integer maxPasses;
    private Integer colloquiality;
    private Integer sentenceVariety;
    private Integer connectiveCleanup;
    private Integer paragraphReshape;
    private Boolean useProjectCorpus;
    private Boolean preserveParagraphs;
    private Boolean hotspotLocalEnabled;
    private Boolean extraModelPassEnabled;
    private String extraModelPassPrompt;
    private Boolean returnDebug;

    public static DeAiRewriteConfig defaults() {
        return DeAiRewriteConfig.builder()
                .engine("local")
                .intensity(58)
                .beamWidth(4)
                .maxPasses(2)
                .colloquiality(52)
                .sentenceVariety(68)
                .connectiveCleanup(78)
                .paragraphReshape(35)
                .useProjectCorpus(false)
                .preserveParagraphs(true)
                .hotspotLocalEnabled(false)
                .extraModelPassEnabled(false)
                .extraModelPassPrompt("")
                .returnDebug(true)
                .build();
    }

    public DeAiRewriteConfig normalized() {
        DeAiRewriteConfig base = DeAiRewriteConfig.defaults();
        return DeAiRewriteConfig.builder()
                .engine(normalizeEngine(engine != null ? engine : base.engine))
                .intensity(clamp(intensity, base.intensity, 0, 100))
                .beamWidth(clamp(beamWidth, base.beamWidth, 1, 8))
                .maxPasses(clamp(maxPasses, base.maxPasses, 1, 4))
                .colloquiality(clamp(colloquiality, base.colloquiality, 0, 100))
                .sentenceVariety(clamp(sentenceVariety, base.sentenceVariety, 0, 100))
                .connectiveCleanup(clamp(connectiveCleanup, base.connectiveCleanup, 0, 100))
                .paragraphReshape(clamp(paragraphReshape, base.paragraphReshape, 0, 100))
                .useProjectCorpus(useProjectCorpus != null ? useProjectCorpus : base.useProjectCorpus)
                .preserveParagraphs(preserveParagraphs != null ? preserveParagraphs : base.preserveParagraphs)
                .hotspotLocalEnabled(hotspotLocalEnabled != null ? hotspotLocalEnabled : base.hotspotLocalEnabled)
                .extraModelPassEnabled(extraModelPassEnabled != null ? extraModelPassEnabled : base.extraModelPassEnabled)
                .extraModelPassPrompt(normalizePrompt(extraModelPassPrompt != null ? extraModelPassPrompt : base.extraModelPassPrompt))
                .returnDebug(returnDebug != null ? returnDebug : base.returnDebug)
                .build();
    }

    private static String normalizePrompt(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > 12000) {
            return normalized.substring(0, 12000).trim();
        }
        return normalized;
    }

    private static int clamp(Integer value, Integer fallback, int min, int max) {
        int actual = value != null ? value : fallback;
        if (actual < min) return min;
        return Math.min(actual, max);
    }

    private static String normalizeEngine(String value) {
        if (value == null || value.isBlank()) {
            return "local";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        // 默认使用本地微调模型；保留用户显式选择的通用模型。
        return "llm".equals(normalized) ? "llm" : "local";
    }
}
