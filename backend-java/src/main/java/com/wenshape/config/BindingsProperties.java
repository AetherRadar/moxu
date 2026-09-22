package com.wenshape.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 章节绑定（bindings）相关的可调参数。
 *
 * <p>目标不是把所有启发式都暴露出去，而是把最常需要微调的
 * BM25 / alias / rule 阈值抽成配置，方便后续按项目语料调参。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wenshape.bindings")
public class BindingsProperties {

    private Thresholds thresholds = new Thresholds();
    private Async async = new Async();

    @Data
    public static class Thresholds {
        /**
         * 具体实体名（专名）的基础 BM25 门槛。
         */
        private double bm25 = 0.95;

        /**
         * 泛称 alias 的基础 BM25 门槛。
         */
        private double bm25Generic = 1.35;

        /**
         * 世界规则的基础 BM25 门槛。
         */
        private double rule = 1.0;

        /**
         * 短具体 alias 的额外门槛。
         */
        private double aliasShortSpecificPenalty = 0.18;

        /**
         * 短泛称 alias 的额外门槛。
         */
        private double aliasShortGenericPenalty = 0.25;

        /**
         * 中等长度具体 alias 的额外门槛。
         */
        private double aliasMediumSpecificPenalty = 0.08;

        /**
         * 中等长度泛称 alias 的额外门槛。
         */
        private double aliasMediumGenericPenalty = 0.15;

        /**
         * 长 alias 的门槛减免。
         */
        private double aliasLongRelief = 0.08;

        /**
         * 泛称且词项很少时的额外惩罚。
         */
        private double aliasGenericSingleTermPenalty = 0.12;

        /**
         * alias 词项较丰富时的门槛减免。
         */
        private double aliasRichTermRelief = 0.05;

        /**
         * 很短规则文本的额外门槛。
         */
        private double ruleShortPenalty = 0.30;

        /**
         * 中短规则文本的额外门槛。
         */
        private double ruleMediumPenalty = 0.15;

        /**
         * 很长规则文本的门槛减免。
         */
        private double ruleLongRelief = 0.08;

        /**
         * 规则 token 很少时的额外惩罚。
         */
        private double ruleFewTermsPenalty = 0.12;

        /**
         * 明显规则提示词带来的门槛减免。
         */
        private double ruleHintRelief = 0.08;
    }

    @Data
    public static class Async {
        /**
         * bindings 后台重建的最大并发数。
         *
         * <p>这里控制的是“同时处理多少个章节的 bindings 重建”，
         * 不是线程池大小上限。默认值偏保守，优先保证大项目下的稳定性。</p>
         */
        private int maxConcurrentRebuilds = 4;
    }
}
