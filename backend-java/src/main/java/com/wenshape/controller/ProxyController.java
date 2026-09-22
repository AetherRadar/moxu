package com.wenshape.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 代理控制器 - 拉取 LLM 提供商的模型列表
 */
@Slf4j
@RestController
@RequestMapping("/proxy")
@RequiredArgsConstructor
public class ProxyController {
    
    private static final List<String> ANTHROPIC_FALLBACK_MODELS = List.of(
            "claude-opus-4-6",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229",
            "claude-3-sonnet-20240229",
            "claude-3-haiku-20240307"
    );
    
    private static final Map<String, String> PROVIDER_BASE_URLS = Map.of(
            "deepseek", "https://api.deepseek.com/v1",
            "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "kimi", "https://api.moonshot.cn/v1",
            "glm", "https://open.bigmodel.cn/api/paas/v4",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai/",
            "grok", "https://api.x.ai/v1"
    );
    
    /**
     * 拉取模型列表
     */
    @PostMapping("/fetch-models")
    public Mono<Map<String, Object>> fetchModels(@RequestBody FetchModelsRequest request) {
        String provider = request.provider != null ? request.provider.trim().toLowerCase() : "";
        String apiKey = request.apiKey;
        String baseUrl = request.baseUrl != null ? request.baseUrl.trim() : null;
        
        log.debug("Fetch Models: provider={}, baseUrl={}", provider, baseUrl);
        
        // Anthropic 特殊处理
        if ("anthropic".equals(provider)) {
            return fetchAnthropicModels(apiKey, baseUrl);
        }
        
        // 确定 base URL
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = PROVIDER_BASE_URLS.getOrDefault(provider, "https://api.openai.com/v1");
        }
        
        // 使用 OpenAI 兼容的 /models 接口
        return fetchOpenAICompatibleModels(apiKey, baseUrl);
    }
    
    // ========== 私有方法 ==========
    
    private Mono<Map<String, Object>> fetchAnthropicModels(String apiKey, String baseUrl) {
        String url = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "https://api.anthropic.com";
        if (!url.endsWith("/")) url += "/";
        url += "v1/models";
        
        WebClient client = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        
        return client.get()
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                    if (data == null || data.isEmpty()) {
                        return Map.of(
                                "models", ANTHROPIC_FALLBACK_MODELS,
                                "warning", "Anthropic 模型列表为空，已返回内置候选列表。"
                        );
                    }
                    
                    List<String> modelIds = data.stream()
                            .map(m -> (String) m.get("id"))
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();
                    
                    log.info("Fetch Models Success: Found {} models (anthropic)", modelIds.size());
                    return Map.<String, Object>of("models", modelIds);
                })
                .onErrorResume(e -> {
                    log.warn("Fetch Models Error (anthropic): {}", e.getMessage());
                    return Mono.just(Map.of(
                            "models", ANTHROPIC_FALLBACK_MODELS,
                            "warning", "Anthropic 模型列表拉取失败，已返回内置候选列表。原因：" + e.getMessage()
                    ));
                });
    }
    
    private Mono<Map<String, Object>> fetchOpenAICompatibleModels(String apiKey, String baseUrl) {
        String normalized = baseUrl.replaceAll("/+$", "");

        // 部分中转站把 /models 挂在根路径、聊天接口挂在 /v1 下（或相反）。
        // 依次尝试：{base}/models、去掉版本段后的 {root}/models、{base}/v1/models
        List<String> candidates = new ArrayList<>();
        candidates.add(normalized + "/models");
        String stripped = normalized.replaceAll("/v\\d+[a-zA-Z]*$", "");
        if (!stripped.equals(normalized)) {
            candidates.add(stripped + "/models");
        } else {
            candidates.add(normalized + "/v1/models");
        }

        Mono<Map<String, Object>> result = null;
        for (String url : candidates) {
            Mono<Map<String, Object>> attempt = fetchModelsAt(apiKey, url);
            if (result == null) {
                result = attempt;
            } else {
                result = result.onErrorResume(e -> {
                    log.debug("Fetch Models candidate failed: {}", e.getMessage());
                    return attempt;
                });
            }
        }

        return result.onErrorResume(e -> {
            log.warn("Fetch Models Error: {}", e.getMessage());
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()));
        });
    }

    private Mono<Map<String, Object>> fetchModelsAt(String apiKey, String url) {
        WebClient client = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        return client.get()
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                    if (data == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No models found");
                    }

                    List<String> modelIds = data.stream()
                            .map(m -> (String) m.get("id"))
                            .filter(Objects::nonNull)
                            .sorted()
                            .toList();

                    log.info("Fetch Models Success: Found {} models at {}", modelIds.size(), url);
                    return Map.<String, Object>of("models", modelIds);
                });
    }
    
    // ========== 请求类 ==========
    
    public static class FetchModelsRequest {
        public String provider;
        @com.fasterxml.jackson.annotation.JsonProperty("api_key")
        public String apiKey;
        @com.fasterxml.jackson.annotation.JsonProperty("base_url")
        public String baseUrl;
    }
}
