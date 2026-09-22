package com.moxu.rewrite;

import java.util.Map;

/** 仅供差异预览的候选；通过启发式检查不代表事实或文风质量得到保证。 */
public record PolishResult(String rewritten, String engine, boolean changed, Map<String, Object> debug) {
    public PolishResult {
        debug = Map.copyOf(debug);
    }
}
