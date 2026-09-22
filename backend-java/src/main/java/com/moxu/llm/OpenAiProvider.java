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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容提供商 - 通过 ALPN 协商 HTTP/2 或 HTTP/1.1。
 */
@Slf4j
public class OpenAiProvider implements LlmProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final OkHttpClient httpClient;
    private final boolean compatibilityMode;

    private final String providerName;

    public OpenAiProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        this(apiKey, baseUrl, model, maxTokens, temperature, "openai");
    }

    public OpenAiProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature,
                          String providerName) {
        this(apiKey, baseUrl, model, maxTokens, temperature, providerName, false);
    }

    public OpenAiProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature,
                          String providerName, boolean compatibilityMode) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                .replaceAll("/+$", "");
        this.model = model != null ? model : "gpt-4o";
        this.maxTokens = maxTokens > 0 ? maxTokens : 8000;
        this.temperature = temperature;
        this.providerName = (providerName != null && !providerName.isBlank()) ? providerName : "openai";
        this.compatibilityMode = compatibilityMode;

        this.httpClient = new OkHttpClient.Builder()
                .protocols(compatibilityMode ? List.of(Protocol.HTTP_1_1) : List.of(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
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
                             boolean allowReasoningRetry) {
        long startTime = System.currentTimeMillis();
        double actualTemp = temperature != null ? temperature : this.temperature;
        int actualMaxTokens = maxTokens != null ? maxTokens : this.maxTokens;
        java.util.function.Supplier<OkHttpClient> transport = allowReasoningRetry && !compatibilityMode
                ? () -> httpClient : this::singleRequestClient;

        try {
            ParsedChatResponse parsed = executeChatWithFallback(messages, actualTemp, actualMaxTokens, transport);
            if (allowReasoningRetry && shouldRetryReasoningOnly(parsed, actualMaxTokens)) {
                int retryMaxTokens = Math.min(Math.max(actualMaxTokens * 2, actualMaxTokens + 1200), 8000);
                log.warn(
                        "OpenAI-compatible response used reasoning tokens without final content; retrying once with larger max_tokens: model={}, baseUrl={}, finishReason={}, promptTokens={}, completionTokens={}, maxTokens={} -> {}, reasoningPreview={}",
                        model,
                        baseUrl,
                        parsed.finishReason(),
                        parsed.promptTokens(),
                        parsed.completionTokens(),
                        actualMaxTokens,
                        retryMaxTokens,
                        parsed.reasoningPreview()
                );
                parsed = executeChatWithFallback(messages, actualTemp, retryMaxTokens, transport);
            }

            if (parsed.content().isBlank()) {
                log.warn(
                        "OpenAI-compatible response parsed as empty content: model={}, baseUrl={}, finishReason={}, promptTokens={}, completionTokens={}, rootKeys={}, choiceKeys={}, messageKeys={}, contentNodeType={}, contentNodePreview={}, outputTextPreview={}, reasoningPreview={}, responsePreview={}",
                        model,
                        baseUrl,
                        parsed.finishReason(),
                        parsed.promptTokens(),
                        parsed.completionTokens(),
                        parsed.rootKeys(),
                        parsed.choiceKeys(),
                        parsed.messageKeys(),
                        parsed.contentNodeType(),
                        parsed.contentNodePreview(),
                        parsed.outputTextPreview(),
                        parsed.reasoningPreview(),
                        preview(parsed.responseBody(), 2000)
                );
            }

            return LlmResponse.builder()
                    .content(parsed.content())
                    .model(model)
                    .finishReason(parsed.finishReason())
                    .provider(getProviderName())
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .promptTokens(parsed.promptTokens())
                    .completionTokens(parsed.completionTokens())
                    .totalTokens(parsed.totalTokens())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.ofVirtual().start(() -> {
            try {
                if (compatibilityMode) {
                    // 不支持流式的中转接口整次返回；不自动重试，避免重复计费。
                    LlmResponse response = chatOnce(messages, temperature, maxTokens);
                    if ("length".equals(response.getFinishReason())
                            || "incomplete".equals(response.getFinishReason())) {
                        throw new IOException("Model output is incomplete; increase the output token limit");
                    }
                    if (response.getContent() != null && !response.getContent().isEmpty()) {
                        sink.tryEmitNext(response.getContent());
                    }
                    sink.tryEmitComplete();
                    return;
                }
                String requestBody = buildRequestBody(messages,
                        temperature != null ? temperature : this.temperature,
                        maxTokens != null ? maxTokens : this.maxTokens,
                        true);

                // 部分中转站聊天接口挂在 /v1 下而 /models 在根路径（或相反），逐个候选地址尝试
                for (String url : chatUrlCandidates()) {
                    Request request = new Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .post(RequestBody.create(requestBody, JSON))
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            String body = response.body() != null ? response.body().string() : "(empty)";
                            if (response.code() == 404 || looksLikeHtmlPage(body)) {
                                log.warn("Stream chat endpoint not an API (HTTP {} at {}), trying next candidate", response.code(), url);
                                continue;
                            }
                            sink.tryEmitError(LlmHttpException.from(response.code(), response.header("Retry-After"), body));
                            return;
                        }

                        boolean wrongEndpoint = false;
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.stripLeading().startsWith("<")) {
                                    wrongEndpoint = true;
                                    break;
                                }
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if ("[DONE]".equals(data)) break;
                                    try {
                                        JsonNode node = MAPPER.readTree(data);
                                        String token = node.path("choices").path(0)
                                                .path("delta").path("content").asText("");
                                        if (!token.isEmpty()) {
                                            sink.tryEmitNext(token);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        if (wrongEndpoint) {
                            log.warn("Stream chat endpoint returned HTML ({}), trying next candidate", url);
                            continue;
                        }
                        sink.tryEmitComplete();
                        return;
                    }
                }
                sink.tryEmitError(new RuntimeException("LLM stream error: no usable chat completions endpoint at " + baseUrl));
            } catch (Exception e) {
                log.error("Stream chat failed", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    private String fieldNames(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(iterator.next());
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }

    private String nodeType(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "missing";
        }
        if (node.isTextual()) {
            return "text";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isNull()) {
            return "null";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNumber()) {
            return "number";
        }
        return node.getNodeType().name().toLowerCase();
    }

    private String previewNode(JsonNode node, int maxChars) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual()) {
            return preview(node.asText(""), maxChars);
        }
        try {
            return preview(MAPPER.writeValueAsString(node), maxChars);
        } catch (Exception e) {
            return preview(String.valueOf(node), maxChars);
        }
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

    private boolean shouldRetryReasoningOnly(ParsedChatResponse parsed, int requestedMaxTokens) {
        if (parsed == null) {
            return false;
        }
        if (!parsed.content().isBlank()) {
            return false;
        }
        if (parsed.reasoningPreview().isBlank()) {
            return false;
        }
        if (!"length".equalsIgnoreCase(parsed.finishReason())) {
            return false;
        }
        return parsed.completionTokens() >= Math.max(256, (int) Math.round(requestedMaxTokens * 0.85));
    }

    private OkHttpClient singleRequestClient() {
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        return httpClient.newBuilder()
                // 不复用父 client 池中的连接：中转网关关闭的 keep-alive 连接一旦被复用，
                // 请求会写进死连接直接 EOF（unexpected end of stream）
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addNetworkInterceptor(chain -> {
                    // OkHttp 即使关闭连接重试，仍可能响应 503 + Retry-After: 0 再发请求。
                    if (!sent.compareAndSet(false, true)) {
                        throw new IOException("Single-call transport refused an additional request");
                    }
                    return chain.proceed(chain.request());
                }).build();
    }

    /**
     * 部分中转站把聊天接口挂在 /v1 下而 /models 在根路径（或相反）。
     * 返回依次尝试的 /chat/completions 候选地址：原地址 + 版本段互换地址。
     */
    private List<String> chatUrlCandidates() {
        List<String> urls = new ArrayList<>();
        urls.add(baseUrl + "/chat/completions");
        String stripped = baseUrl.replaceAll("/v\\d+[a-zA-Z]*$", "");
        if (!stripped.equals(baseUrl)) {
            urls.add(stripped + "/chat/completions");
        } else {
            urls.add(baseUrl + "/v1/chat/completions");
        }
        return urls;
    }

    private static boolean looksLikeHtmlPage(String body) {
        return body != null && body.stripLeading().startsWith("<");
    }

    /** 响应不是 API JSON（HTML 页面或 404），可换候选地址重试 */
    private static class WrongEndpointException extends IOException {
        WrongEndpointException(String message) {
            super(message);
        }
    }

    private ParsedChatResponse executeChatWithFallback(List<Map<String, String>> messages, double temp,
                                                       int maxTokens,
                                                       java.util.function.Supplier<OkHttpClient> transport) throws IOException {
        WrongEndpointException lastError = null;
        for (String url : chatUrlCandidates()) {
            try {
                // 每个候选地址使用独立的单次 client，避免单发拦截器把换址重试也拦掉。
                return executeChat(messages, temp, maxTokens, transport.get(), url);
            } catch (WrongEndpointException e) {
                log.warn("Chat endpoint not an API ({}), trying next candidate", e.getMessage());
                lastError = e;
            }
        }
        if (lastError != null) throw LlmHttpException.from(404, null, null);
        throw new IOException("No chat completions endpoint available");
    }

    private ParsedChatResponse executeChat(List<Map<String, String>> messages, double temp, int maxTokens,
                                           OkHttpClient transport, String url) throws IOException {
        // 用流式请求保活：网关在 Cloudflare 等代理后时，长生成超过约 100s 的空闲
        // 非流式请求会被掐断（524/EOF）。服务端忽略 stream 时仍能按普通 JSON 解析。
        String requestBody = buildRequestBody(messages, temp, maxTokens, !compatibilityMode);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = transport.newCall(request).execute()) {
            String responseBody = readBodyTolerant(response);
            if (!response.isSuccessful()) {
                if (response.code() == 404 || looksLikeHtmlPage(responseBody)) {
                    throw new WrongEndpointException("HTTP " + response.code() + " at " + url);
                }
                throw LlmHttpException.from(response.code(), response.header("Retry-After"), responseBody);
            }
            if (looksLikeHtmlPage(responseBody)) {
                throw new WrongEndpointException("HTML page returned at " + url);
            }

            String contentType = response.header("Content-Type", "");
            if (looksLikeSseResponse(contentType, responseBody)) {
                return parseSseChatResponse(responseBody);
            }

            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choice0 = root.path("choices").path(0);
            JsonNode messageNode = choice0.path("message");
            JsonNode contentNode = messageNode.get("content");

            String content = extractMessageContent(messageNode);
            String finishReason = choice0.path("finish_reason").asText("stop");
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            int totalTokens = promptTokens + completionTokens;
            String reasoningPreview = previewNode(messageNode.get("reasoning_content"), 600);

            return new ParsedChatResponse(
                    content,
                    finishReason,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    reasoningPreview,
                    responseBody,
                    fieldNames(root),
                    fieldNames(choice0),
                    fieldNames(messageNode),
                    nodeType(contentNode),
                    previewNode(contentNode, 400),
                    previewNode(root.get("output_text"), 400)
            );
        }
    }

    // 中转网关常在 SSE 末尾缺少终止分块，OkHttp 读 body().string() 会抛 EOFException。
    // 逐行读取并容忍截断，已收到的内容仍然有效。
    private String readBodyTolerant(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                // The model has finished; a proxy may keep the HTTP body open afterwards.
                if (line.startsWith("data:") && "[DONE]".equals(line.substring(5).trim())) {
                    break;
                }
            }
        } catch (IOException truncated) {
            if (sb.isEmpty()) {
                throw truncated;
            }
            log.warn("Response stream truncated ({}), using {} chars already received", truncated, sb.length());
        }
        return sb.toString();
    }

    private boolean looksLikeSseResponse(String contentType, String responseBody) {
        String normalizedType = contentType == null ? "" : contentType.toLowerCase();
        if (normalizedType.contains("text/event-stream")) {
            return true;
        }
        String trimmed = responseBody == null ? "" : responseBody.trim();
        return trimmed.startsWith("data:");
    }

    private ParsedChatResponse parseSseChatResponse(String responseBody) throws IOException {
        List<String> payloads = extractSsePayloads(responseBody);
        if (payloads.isEmpty()) {
            throw new IOException("SSE response did not contain any data payloads");
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = "incomplete";
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String rootKeys = "(sse)";
        String choiceKeys = "(none)";
        String messageKeys = "(none)";

        for (String payload : payloads) {
            if ("[DONE]".equals(payload)) {
                if ("incomplete".equals(finishReason)) finishReason = "stop";
                break;
            }
            if (payload == null || payload.isBlank()) {
                continue;
            }

            JsonNode node = MAPPER.readTree(payload);
            rootKeys = fieldNames(node);

            JsonNode choice0 = node.path("choices").path(0);
            if (!choice0.isMissingNode()) {
                choiceKeys = fieldNames(choice0);
            }

            JsonNode deltaNode = choice0.path("delta");
            if (!deltaNode.isMissingNode()) {
                String deltaContent = deltaNode.path("content").asText("");
                if (!deltaContent.isEmpty()) {
                    content.append(deltaContent);
                }
                String deltaReasoning = deltaNode.path("reasoning_content").asText("");
                if (!deltaReasoning.isEmpty()) {
                    reasoning.append(deltaReasoning);
                }
            }

            JsonNode messageNode = choice0.path("message");
            if (!messageNode.isMissingNode()) {
                messageKeys = fieldNames(messageNode);
                String fullMessageContent = extractMessageContent(messageNode);
                if (!fullMessageContent.isEmpty() && content.isEmpty()) {
                    content.append(fullMessageContent);
                }
                String fullReasoning = messageNode.path("reasoning_content").asText("");
                if (!fullReasoning.isEmpty() && reasoning.isEmpty()) {
                    reasoning.append(fullReasoning);
                }
            }

            String textFallback = choice0.path("text").asText("");
            if (!textFallback.isEmpty() && content.isEmpty()) {
                content.append(textFallback);
            }

            String outputText = node.path("output_text").asText("");
            if (!outputText.isEmpty() && content.isEmpty()) {
                content.append(outputText);
            }

            String chunkFinishReason = choice0.path("finish_reason").asText("");
            if (!chunkFinishReason.isBlank() && !"null".equalsIgnoreCase(chunkFinishReason)) {
                finishReason = chunkFinishReason;
            }

            JsonNode usageNode = node.path("usage");
            if (!usageNode.isMissingNode()) {
                int prompt = usageNode.path("prompt_tokens").asInt(0);
                int completion = usageNode.path("completion_tokens").asInt(0);
                int total = usageNode.path("total_tokens").asInt(0);
                if (prompt > 0) {
                    promptTokens = prompt;
                }
                if (completion > 0) {
                    completionTokens = completion;
                }
                if (total > 0) {
                    totalTokens = total;
                }
            }
        }

        if (totalTokens <= 0) {
            totalTokens = promptTokens + completionTokens;
        }

        return new ParsedChatResponse(
                content.toString(),
                finishReason,
                promptTokens,
                completionTokens,
                totalTokens,
                preview(reasoning.toString(), 600),
                responseBody,
                rootKeys,
                choiceKeys,
                messageKeys,
                "text",
                preview(content.toString(), 400),
                ""
        );
    }

    private List<String> extractSsePayloads(String responseBody) {
        List<String> payloads = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            return payloads;
        }

        String[] lines = responseBody.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("data:")) {
                String payload = line.substring(5).trim();
                if (!payload.isEmpty()) {
                    payloads.add(payload);
                }
            }
        }
        if (!payloads.isEmpty()) {
            return payloads;
        }

        int cursor = 0;
        while (cursor < responseBody.length()) {
            int marker = responseBody.indexOf("data:", cursor);
            if (marker < 0) {
                break;
            }
            int start = marker + 5;
            while (start < responseBody.length() && Character.isWhitespace(responseBody.charAt(start))) {
                start++;
            }
            if (start >= responseBody.length()) {
                break;
            }
            if (responseBody.startsWith("[DONE]", start)) {
                payloads.add("[DONE]");
                cursor = start + "[DONE]".length();
                continue;
            }

            char first = responseBody.charAt(start);
            if (first == '{' || first == '[') {
                int end = findJsonBoundary(responseBody, start);
                if (end <= start) {
                    break;
                }
                payloads.add(responseBody.substring(start, end));
                cursor = end;
                continue;
            }

            int nextMarker = responseBody.indexOf("data:", start);
            String payload = (nextMarker >= 0 ? responseBody.substring(start, nextMarker) : responseBody.substring(start)).trim();
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
            cursor = nextMarker >= 0 ? nextMarker : responseBody.length();
        }

        return payloads;
    }

    private int findJsonBoundary(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    continue;
                }
                if (ch == '\\') {
                    escaping = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{' || ch == '[') {
                depth++;
                continue;
            }
            if (ch == '}' || ch == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private String extractMessageContent(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode()) {
            return "";
        }
        JsonNode contentNode = messageNode.get("content");
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isTextual()) {
                    sb.append(item.asText(""));
                    continue;
                }
                String type = item.path("type").asText("");
                if ("text".equals(type) || "output_text".equals(type)) {
                    String text = item.path("text").asText("");
                    if (!text.isEmpty()) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String buildRequestBody(List<Map<String, String>> messages, double temp, int maxTok, boolean stream)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        // 某些兼容网关带 temperature 会直接断开；兼容模式使用模型默认采样参数。
        if (!compatibilityMode) root.put("temperature", temp);
        root.put("max_tokens", maxTok);
        root.put("stream", stream);

        ArrayNode msgs = root.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
        }

        return MAPPER.writeValueAsString(root);
    }

    private record ParsedChatResponse(String content,
                                      String finishReason,
                                      int promptTokens,
                                      int completionTokens,
                                      int totalTokens,
                                      String reasoningPreview,
                                      String responseBody,
                                      String rootKeys,
                                      String choiceKeys,
                                      String messageKeys,
                                      String contentNodeType,
                                      String contentNodePreview,
                                      String outputTextPreview) {
    }
}
