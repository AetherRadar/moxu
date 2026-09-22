package com.moxu.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Responses API 提供商
 * 适配 /responses 接口（含部分返回 SSE 的兼容网关）
 */
@Slf4j
public class ResponsesProvider implements LlmProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final OkHttpClient httpClient;

    public ResponsesProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1");
        this.model = model != null && !model.isBlank() ? model : "gpt-4.1";
        this.maxTokens = maxTokens > 0 ? maxTokens : 8000;
        this.temperature = temperature;
        this.httpClient = new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        return chat(messages, temperature, maxTokens, true);
    }

    @Override
    public LlmResponse chatOnce(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        return chat(messages, temperature, maxTokens, false);
    }

    private LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens,
                             boolean rejectEmptyContent) {
        long startTime = System.currentTimeMillis();
        OkHttpClient transport = rejectEmptyContent ? httpClient : singleRequestClient();

        try {
            String requestBody = buildRequestBody(
                    messages,
                    temperature != null ? temperature : this.temperature,
                    maxTokens != null ? maxTokens : this.maxTokens,
                    false
            );

            Request request = new Request.Builder()
                    .url(baseUrl + "/responses")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Connection", "close")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = transport.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "(empty)";
                    throw LlmHttpException.from(response.code(), response.header("Retry-After"), body);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                String contentType = response.header("Content-Type", "");
                ParsedResponse parsed = parsePayload(responseBody, contentType, !rejectEmptyContent);

                if (rejectEmptyContent && parsed.content().isBlank()) {
                    log.warn("Responses API returned empty content: model={}, baseUrl={}, contentType={}, responsePreview={}",
                            model, baseUrl, contentType, preview(responseBody, 2000));
                    throw new RuntimeException("Responses API returned empty content");
                }

                return LlmResponse.builder()
                        .content(parsed.content())
                        .model(parsed.model().isBlank() ? model : parsed.model())
                        .finishReason(parsed.finishReason())
                        .provider(getProviderName())
                        .elapsedMs(System.currentTimeMillis() - startTime)
                        .promptTokens(parsed.promptTokens())
                        .completionTokens(parsed.completionTokens())
                        .totalTokens(parsed.totalTokens())
                        .build();
            }
        } catch (IOException e) {
            throw new RuntimeException("Responses chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.ofVirtual().start(() -> {
            try {
                String requestBody = buildRequestBody(
                        messages,
                        temperature != null ? temperature : this.temperature,
                        maxTokens != null ? maxTokens : this.maxTokens,
                        true
                );

                Request request = new Request.Builder()
                        .url(baseUrl + "/responses")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Connection", "close")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "(empty)";
                        sink.tryEmitError(LlmHttpException.from(response.code(), response.header("Retry-After"), body));
                        return;
                    }

                    String contentType = response.header("Content-Type", "");
                    if (!isEventStream(contentType)) {
                        String body = response.body() != null ? response.body().string() : "";
                        ParsedResponse parsed = parsePayload(body, contentType, false);
                        if (parsed.content().isBlank()) {
                            sink.tryEmitError(new RuntimeException("empty stream response"));
                            return;
                        }
                        sink.tryEmitNext(parsed.content());
                        sink.tryEmitComplete();
                        return;
                    }

                    boolean[] emittedDelta = {false};
                    boolean[] emittedFinalText = {false};

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if (data.isEmpty() || "[DONE]".equals(data)) {
                                continue;
                            }
                            try {
                                JsonNode node = MAPPER.readTree(data);
                                String eventType = node.path("type").asText("");

                                if ("response.output_text.delta".equals(eventType)) {
                                    String delta = node.path("delta").asText("");
                                    if (!delta.isEmpty()) {
                                        emittedDelta[0] = true;
                                        sink.tryEmitNext(delta);
                                    }
                                    continue;
                                }

                                if ("response.output_text.done".equals(eventType)) {
                                    String text = node.path("text").asText("");
                                    if (!emittedDelta[0] && !text.isEmpty()) {
                                        emittedFinalText[0] = true;
                                        sink.tryEmitNext(text);
                                    }
                                    continue;
                                }

                                if ("response.completed".equals(eventType)) {
                                    if (!emittedDelta[0] && !emittedFinalText[0]) {
                                        String text = extractText(node.path("response"));
                                        if (!text.isBlank()) {
                                            sink.tryEmitNext(text);
                                        }
                                    }
                                    break;
                                }

                                if ("error".equals(eventType) || node.has("error")) {
                                    sink.tryEmitError(new RuntimeException("Responses stream error: " + preview(data, 1000)));
                                    return;
                                }
                            } catch (Exception parseError) {
                                log.debug("Failed to parse responses SSE payload: {}", data, parseError);
                            }
                        }
                    }

                    sink.tryEmitComplete();
                }
            } catch (Exception e) {
                log.error("Responses stream chat failed", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }

    @Override
    public String getProviderName() {
        return "responses";
    }

    private OkHttpClient singleRequestClient() {
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        return httpClient.newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addNetworkInterceptor(chain -> {
                    // 限制实际发送次数，包括 OkHttp 的 HTTP 状态码补请求。
                    if (!sent.compareAndSet(false, true)) {
                        throw new IOException("Single-call transport refused an additional request");
                    }
                    return chain.proceed(chain.request());
                }).build();
    }

    private ParsedResponse parsePayload(String responseBody, String contentType, boolean requireCompletion) throws IOException {
        if (isEventStream(contentType) || responseBody.stripLeading().startsWith("event:")) {
            return parseSsePayload(responseBody, requireCompletion);
        }
        return parseJsonPayload(responseBody);
    }

    private ParsedResponse parseJsonPayload(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode responseNode = root.has("response") ? root.path("response") : root;
        String content = extractText(responseNode);
        String modelName = responseNode.path("model").asText(model);
        String finishReason = normalizeFinishReason(responseNode.path("status").asText("completed"));
        Usage usage = extractUsage(responseNode.path("usage"));
        return new ParsedResponse(content, modelName, finishReason, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private ParsedResponse parseSsePayload(String responseBody, boolean requireCompletion) throws IOException {
        StringBuilder content = new StringBuilder();
        JsonNode completedResponse = null;
        boolean sawDelta = false;

        for (String rawLine : responseBody.split("\\R")) {
            if (!rawLine.startsWith("data:")) {
                continue;
            }
            String data = rawLine.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }

            JsonNode node = MAPPER.readTree(data);
            String eventType = node.path("type").asText("");
            if ("response.output_text.delta".equals(eventType)) {
                String delta = node.path("delta").asText("");
                if (!delta.isEmpty()) {
                    sawDelta = true;
                    content.append(delta);
                }
                continue;
            }

            if ("response.output_text.done".equals(eventType) && !sawDelta) {
                String text = node.path("text").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                }
                continue;
            }

            if ("response.completed".equals(eventType)
                    || "response.incomplete".equals(eventType)
                    || "response.failed".equals(eventType)) {
                completedResponse = node.path("response");
            }
        }

        if (content.length() == 0 && completedResponse != null) {
            content.append(extractText(completedResponse));
        }

        JsonNode usageNode = completedResponse != null ? completedResponse.path("usage") : null;
        Usage usage = extractUsage(usageNode);
        String modelName = completedResponse != null ? completedResponse.path("model").asText(model) : model;
        String finishReason = completedResponse != null
                ? normalizeFinishReason(completedResponse.path("status").asText("completed"))
                : (requireCompletion ? "incomplete" : "stop");

        return new ParsedResponse(content.toString(), modelName, finishReason, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private String extractText(JsonNode responseNode) {
        if (responseNode == null || responseNode.isMissingNode() || responseNode.isNull()) {
            return "";
        }

        JsonNode outputText = responseNode.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText("").isBlank()) {
            return outputText.asText("");
        }

        StringBuilder sb = new StringBuilder();
        JsonNode outputArray = responseNode.path("output");
        if (outputArray.isArray()) {
            for (JsonNode item : outputArray) {
                JsonNode contentArray = item.path("content");
                if (!contentArray.isArray()) {
                    continue;
                }
                for (JsonNode part : contentArray) {
                    String type = part.path("type").asText("");
                    if ("output_text".equals(type) || "text".equals(type)) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            sb.append(text);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private Usage extractUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return new Usage(0, 0, 0);
        }
        int promptTokens = usageNode.path("input_tokens").asInt(0);
        int completionTokens = usageNode.path("output_tokens").asInt(0);
        int totalTokens = usageNode.path("total_tokens").asInt(promptTokens + completionTokens);
        return new Usage(promptTokens, completionTokens, totalTokens);
    }

    private String normalizeFinishReason(String status) {
        if (status == null || status.isBlank()) {
            return "stop";
        }
        return "completed".equalsIgnoreCase(status) ? "stop" : status;
    }

    private boolean isEventStream(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("text/event-stream");
    }

    private String buildRequestBody(List<Map<String, String>> messages, double temp, int maxTok, boolean stream)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", temp);
        root.put("max_output_tokens", maxTok);
        root.put("stream", stream);

        StringBuilder instructions = new StringBuilder();
        ArrayNode input = root.putArray("input");
        for (Map<String, String> msg : messages) {
            if (msg == null) {
                continue;
            }
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            if (content.isBlank()) {
                continue;
            }
            if ("system".equals(role)) {
                if (instructions.length() > 0) {
                    instructions.append("\n\n");
                }
                instructions.append(content);
                continue;
            }

            ObjectNode item = input.addObject();
            item.put("role", "assistant".equals(role) ? "assistant" : "user");
            item.put("content", content);
        }

        if (instructions.length() > 0) {
            root.put("instructions", instructions.toString());
        }

        ObjectNode text = root.putObject("text");
        text.putObject("format").put("type", "text");

        return MAPPER.writeValueAsString(root);
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/responses")) {
            normalized = normalized.substring(0, normalized.length() - "/responses".length());
        }
        return normalized;
    }

    private String preview(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...(truncated)";
    }

    private record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    private record ParsedResponse(
            String content,
            String model,
            String finishReason,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {}
}
