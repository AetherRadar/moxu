package com.wenshape.llm;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mock provider for demo / no-key scenarios.
 */
public class MockProvider implements LlmProvider {

    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        StringBuilder prompt = new StringBuilder();
        for (Map<String, String> message : messages) {
            if (message == null) {
                continue;
            }
            prompt.append(message.getOrDefault("role", "user"))
                    .append(": ")
                    .append(message.getOrDefault("content", ""))
                    .append("\n");
        }

        String content = "[MOCK] MoXu is running in demo mode.\n\n"
                + "No API key is configured, so this is a placeholder response.\n\n"
                + "---\nInput:\n"
                + prompt;

        return LlmResponse.builder()
                .content(content)
                .model("mock")
                .finishReason("stop")
                .provider(getProviderName())
                .elapsedMs(0)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .build();
    }

    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        String content = chat(messages, temperature, maxTokens).getContent();
        return Flux.fromArray(content.split(""))
                .delayElements(Duration.ofMillis(5));
    }

    @Override
    public String getProviderName() {
        return "mock";
    }
}
