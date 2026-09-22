package com.moxu.llm;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * LLM 提供商接口
 */
public interface LlmProvider {
    
    /**
     * 同步聊天
     */
    LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens);

    /**
     * 单次聊天。含补请求或空正文恢复逻辑的提供商须覆写，保留原始结果供调用方校验。
     */
    default LlmResponse chatOnce(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        return chat(messages, temperature, maxTokens);
    }
    
    /**
     * 流式聊天
     */
    Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens);
    
    /**
     * 获取提供商名称
     */
    String getProviderName();
}
