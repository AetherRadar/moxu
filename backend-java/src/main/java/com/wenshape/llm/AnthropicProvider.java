package com.wenshape.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude LLM 提供商
 */
@Slf4j
public class AnthropicProvider implements LlmProvider {
    
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    private static final String API_VERSION = "2023-06-01";
    
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public AnthropicProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.model = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        
        log.info("Initialized Anthropic provider with model: {}", this.model);
    }
    
    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        long startTime = System.currentTimeMillis();
        
        try {
            HttpRequest request = buildRequest(messages, temperature, maxTokens, false);
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw LlmHttpException.from(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null), response.body());
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            
            // 提取内容
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode contentArray = root.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.get("type").asText())) {
                        contentBuilder.append(block.get("text").asText());
                    }
                }
            }
            
            // 提取 token 使用
            JsonNode usage = root.get("usage");
            int inputTokens = usage != null ? usage.get("input_tokens").asInt(0) : 0;
            int outputTokens = usage != null ? usage.get("output_tokens").asInt(0) : 0;
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            
            return LlmResponse.builder()
                    .content(contentBuilder.toString())
                    .model(model)
                    .finishReason(root.path("stop_reason").asText(null))
                    .provider("anthropic")
                    .promptTokens(inputTokens)
                    .completionTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .elapsedMs(elapsedMs)
                    .build();
            
        } catch (Exception e) {
            log.error("Anthropic API call failed", e);
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.ofVirtual().start(() -> {
            try {
                HttpRequest request = buildRequest(messages, temperature, maxTokens, true);
                HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    String body;
                    try (java.io.InputStream stream = response.body()) {
                        body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    sink.tryEmitError(new RuntimeException("Anthropic stream error: " + response.statusCode() + " - " + body));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) {
                            continue;
                        }
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            String token = extractStreamToken(node);
                            if (!token.isEmpty()) {
                                sink.tryEmitNext(token);
                            }
                            String type = node.path("type").asText("");
                            if ("message_stop".equals(type)) {
                                break;
                            }
                        } catch (Exception parseError) {
                            log.debug("Failed to parse Anthropic SSE payload: {}", data, parseError);
                        }
                    }
                }
                sink.tryEmitComplete();
            } catch (Exception e) {
                log.error("Anthropic stream chat failed", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }
    
    @Override
    public String getProviderName() {
        return "anthropic";
    }

    private HttpRequest buildRequest(List<Map<String, String>> messages,
                                     Double temperature,
                                     Integer maxTokens,
                                     boolean stream) throws Exception {
        MessagePayload payload = preparePayload(messages);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens != null ? maxTokens : this.maxTokens);
        requestBody.put("temperature", temperature != null ? temperature : this.temperature);
        requestBody.put("messages", payload.messages());
        if (!payload.systemPrompt().isBlank()) {
            requestBody.put("system", payload.systemPrompt());
        }
        if (stream) {
            requestBody.put("stream", true);
        }

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(120));
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private MessagePayload preparePayload(List<Map<String, String>> messages) {
        StringBuilder systemPrompt = new StringBuilder();
        List<Map<String, String>> anthropicMessages = new ArrayList<>();

        for (Map<String, String> msg : messages) {
            if (msg == null) {
                continue;
            }
            String role = String.valueOf(msg.getOrDefault("role", "user")).trim();
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if (content.isBlank()) {
                continue;
            }
            if ("system".equals(role)) {
                if (systemPrompt.length() > 0) {
                    systemPrompt.append("\n\n");
                }
                systemPrompt.append(content);
                continue;
            }
            String normalizedRole = "assistant".equals(role) ? "assistant" : "user";
            anthropicMessages.add(Map.of("role", normalizedRole, "content", content));
        }

        if (anthropicMessages.isEmpty()) {
            anthropicMessages.add(Map.of("role", "user", "content", "继续。"));
        }
        return new MessagePayload(systemPrompt.toString(), anthropicMessages);
    }

    private String extractStreamToken(JsonNode node) {
        String deltaText = node.path("delta").path("text").asText("");
        if (!deltaText.isEmpty()) {
            return deltaText;
        }

        JsonNode contentBlock = node.path("content_block");
        if ("text".equals(contentBlock.path("type").asText(""))) {
            String blockText = contentBlock.path("text").asText("");
            if (!blockText.isEmpty()) {
                return blockText;
            }
        }

        JsonNode delta = node.path("delta");
        if ("text_delta".equals(delta.path("type").asText(""))) {
            return delta.path("text").asText("");
        }
        return "";
    }

    private record MessagePayload(String systemPrompt, List<Map<String, String>> messages) {}
}
