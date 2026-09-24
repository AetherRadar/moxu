package com.moxu.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.storage.ProjectStorage;
import com.moxu.analysis.AnalysisContext;
import com.moxu.analysis.AnalysisStorage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 网关 - 统一管理多个 LLM 提供商
 */
@Slf4j
@Component
public class LlmGateway {
    
    @Value("${llm.openai.api-key:}")
    private String openaiApiKey;
    
    @Value("${llm.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;
    
    @Value("${llm.openai.model:gpt-4o}")
    private String openaiModel;
    
    @Value("${llm.openai.temperature:0.7}")
    private double openaiTemperature;
    
    @Value("${llm.openai.max-tokens:8000}")
    private int openaiMaxTokens;

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "kimi", "https://api.moonshot.cn/v1",
            "glm", "https://open.bigmodel.cn/api/paas/v4",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai/",
            "grok", "https://api.x.ai/v1"
    );

    private final ProjectStorage projectStorage;
    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> loadedProfiles = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicLong totalTokens = new AtomicLong(0);
    
    private static final int MAX_RETRIES = 3;
    private final LlmRetryCoordinator retries = new LlmRetryCoordinator();

    public LlmGateway(ProjectStorage projectStorage) {
        this.projectStorage = projectStorage;
    }
    
    @PostConstruct
    public synchronized void init() {
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            providers.put("openai", new OpenAiProvider(
                    openaiApiKey, openaiBaseUrl, openaiModel, openaiMaxTokens, openaiTemperature
            ));
            log.info("Initialized OpenAI provider with model: {}", openaiModel);
        }

        loadConfiguredProfiles();

        if (providers.isEmpty()) {
            providers.put("mock", new MockProvider());
            log.warn("No LLM providers configured, fallback to mock provider");
        }
    }
    
    /**
     * 同步聊天（带重试）
     */
    public LlmResponse chat(List<Map<String, String>> messages, String providerId,
                            Double temperature, Integer maxTokens) {
        return chatWithRetries(messages, providerId, temperature, maxTokens, MAX_RETRIES);
    }

    /**
     * 同步聊天（单次尝试，无网关重试或提供商正文补请求）
     */
    public LlmResponse chatOnce(List<Map<String, String>> messages, String providerId,
                                Double temperature, Integer maxTokens) {
        LlmResponse response = measuredChat(getProvider(providerId), providerId, messages, temperature, maxTokens, true);
        totalRequests.incrementAndGet();
        totalTokens.addAndGet(response.getTotalTokens());
        log.info("LLM single chat completed: provider={}, model={}, elapsed={}ms, tokens={}",
                response.getProvider(), response.getModel(), response.getElapsedMs(), response.getTotalTokens());
        return response;
    }

    private LlmResponse chatWithRetries(List<Map<String, String>> messages, String providerId,
                                        Double temperature, Integer maxTokens, int maxRetries) {
        if (providerId == null || providerId.isBlank()) providerId = getProviderForAgent("writer");
        LlmProvider provider = getProvider(providerId);

        Exception lastException = null;
        int attempts = Math.max(1, maxRetries);
        int attempted = 0;
        long started = System.nanoTime();
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                long waitBudget = 240_000 - (System.nanoTime() - started) / 1_000_000;
                if (attempt > 0 && waitBudget <= 0) break;
                if (!retries.awaitTurn(providerId, waitBudget)) {
                    if (lastException == null) lastException = LlmHttpException.from(429, null, null);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during retry", e);
            }
            try {
                attempted++;
                LlmResponse response = measuredChat(provider, providerId, messages, temperature, maxTokens, false);

                totalRequests.incrementAndGet();
                totalTokens.addAndGet(response.getTotalTokens());

                log.info("LLM chat completed: provider={}, model={}, elapsed={}ms, tokens={}",
                        response.getProvider(), response.getModel(),
                        response.getElapsedMs(), response.getTotalTokens());

                return response;
            } catch (AnalysisContext.Paused | java.io.UncheckedIOException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                var decision = LlmRetryCoordinator.decide(e, attempt);
                log.warn("LLM chat failed: provider={}, attempt={}/{}, reason={}", providerId, attempt + 1, attempts, decision.reason());
                if (decision.retry()) {
                    long delay = decision.delayMillis() + LlmRetryCoordinator.jitter();
                    // Keep the cooldown even after the last attempt so a manual retry cannot flood the provider.
                    retries.defer(providerId, delay);
                    if (attempt < attempts - 1) log.info("LLM retry queued: provider={}, delay={}ms", providerId, delay);
                }
                if (!decision.retry()) break;
            }
        }

        throw new RuntimeException("LLM chat failed after " + attempted + " attempts", lastException);
    }

    private LlmResponse measuredChat(LlmProvider provider, String providerId, List<Map<String, String>> messages,
                                    Double temperature, Integer maxTokens, boolean once) {
        AnalysisContext.Ticket ticket = AnalysisContext.beforeCall(messages, maxTokens);
        Integer limit = maxTokens;
        if (AnalysisContext.options() != null && limit == null) limit = AnalysisContext.options().getMaxOutputTokens();
        LlmResponse response;
        try {
            // Analysis retries are owned by this gateway, so hidden provider retries cannot evade the ledger.
            response = once || ticket != null ? provider.chatOnce(messages, temperature, limit)
                    : provider.chat(messages, temperature, limit);
        } catch (RuntimeException e) {
            if (ticket != null) ticket.finish(providerId, null, true);
            throw e;
        }
        if (ticket != null) ticket.finish(providerId, response, false);
        return response;
    }

    public synchronized String analysisFingerprint() {
        String providerId = getProviderForAgent("archivist");
        try {
            return AnalysisStorage.hash(objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(Map.of(
                    "provider", providerId, "profiles", loadedProfiles,
                    "model", openaiModel == null ? "" : openaiModel,
                    "base", openaiBaseUrl == null ? "" : openaiBaseUrl,
                    "temperature", openaiTemperature, "max_tokens", openaiMaxTokens)));
        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    
    /**
     * 流式聊天
     */
    public Flux<String> streamChat(List<Map<String, String>> messages, String providerId,
                                    Double temperature, Integer maxTokens) {
        LlmProvider provider = getProvider(providerId);
        totalRequests.incrementAndGet();
        return provider.streamChat(messages, temperature, maxTokens);
    }
    
    /**
     * 简单生成（单轮对话）
     */
    public reactor.core.publisher.Mono<String> generate(String prompt, String agentName, int maxTokens) {
        String providerId = getProviderForAgent(agentName);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        
        return reactor.core.publisher.Mono.fromCallable(() -> {
            LlmResponse response = chat(messages, providerId, null, maxTokens);
            return response.getContent();
        });
    }
    
    /**
     * 获取指定 Agent 的提供商 ID
     */
    public synchronized String getProviderForAgent(String agentName) {
        Optional<String> assigned = resolveAssignedProfileId(agentName);
        if (assigned.isPresent()) {
            return assigned.get();
        }

        synchronized (this) {
            loadConfiguredProfiles();
        }

        if (providers.containsKey("openai")) {
            return "openai";
        }
        if (providers.containsKey("mock")) {
            return "mock";
        }
        if (!providers.isEmpty()) {
            return providers.keySet().iterator().next();
        }
        providers.put("mock", new MockProvider());
        return "mock";
    }

    /**
     * 获取指定 Agent 的显式分配提供商 ID（仅当 assignments 中明确配置时返回）
     */
    public synchronized Optional<String> getExplicitProviderForAgent(String agentName) {
        return resolveAssignedProfileId(agentName);
    }
    
    /**
     * 获取统计信息
     */
    public synchronized Map<String, Object> getStats() {
        return Map.of(
                "totalRequests", totalRequests.get(),
                "totalTokens", totalTokens.get(),
                "providers", List.copyOf(providers.keySet())
        );
    }

    /**
     * 测试临时配置是否可用（不落盘、不写入 providers 池）
     */
    public LlmResponse testProfile(Map<String, Object> profile) {
        LlmProvider provider = createProvider(profile == null ? Map.of() : profile);
        if (provider == null) {
            throw new IllegalArgumentException("LLM 配置不完整，无法测试");
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", "Return OK only.")
        );

        LlmResponse response = provider.chat(messages, null, 32);
        totalRequests.incrementAndGet();
        totalTokens.addAndGet(response.getTotalTokens());
        return response;
    }
    
    private synchronized LlmProvider getProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            providerId = getProviderForAgent("writer");
        }
        
        LlmProvider provider = providers.get(providerId);
        if (provider == null) {
            synchronized (this) {
                ensureProfileLoaded(providerId);
                provider = providers.get(providerId);
            }
        }
        if (provider == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }
        return provider;
    }

    private synchronized void loadConfiguredProfiles() {
        List<Map<String, Object>> profiles = readProfiles();
        List<String> currentIds = profiles.stream()
                .map(profile -> stringValue(profile.get("id"))).toList();
        loadedProfiles.keySet().removeIf(id -> {
            int sep = id.indexOf("::");
            String baseId = sep > 0 ? id.substring(0, sep) : id;
            if (currentIds.contains(baseId)) return false;
            providers.remove(id);
            return true;
        });
        for (Map<String, Object> profile : profiles) {
            ensureProfileLoaded(profile);
        }
    }

    /**
     * 分工解析出的 provider key：profileId，或带模型覆盖的 profileId::model。
     * 同一档案（中转）可以被不同分工挂上不同模型。
     */
    private Optional<String> resolveAssignedProfileId(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Optional.empty();
        }
        Object raw = readAssignments().get(agentName);
        String profileId;
        String model = "";
        if (raw instanceof Map<?, ?> entry) {
            profileId = stringValue(entry.get("profile_id"));
            model = stringValue(entry.get("model"));
        } else {
            profileId = stringValue(raw);
        }
        if (profileId.isBlank()) {
            return Optional.empty();
        }
        String providerKey = model.isBlank() ? profileId : profileId + "::" + model;
        synchronized (this) {
            ensureProfileLoaded(providerKey);
        }
        return providers.containsKey(providerKey) ? Optional.of(providerKey) : Optional.empty();
    }

    private void ensureProfileLoaded(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return;
        }
        String profileId = providerKey;
        String modelOverride = null;
        int sep = providerKey.indexOf("::");
        if (sep > 0) {
            profileId = providerKey.substring(0, sep);
            modelOverride = providerKey.substring(sep + 2);
        }
        for (Map<String, Object> profile : readProfiles()) {
            if (profileId.equals(String.valueOf(profile.getOrDefault("id", "")))) {
                Map<String, Object> effective = profile;
                if (modelOverride != null && !modelOverride.isBlank()) {
                    effective = new LinkedHashMap<>(profile);
                    effective.put("model", modelOverride);
                }
                ensureProfileLoaded(providerKey, effective);
                return;
            }
        }
        if (loadedProfiles.remove(providerKey) != null) {
            providers.remove(providerKey);
        }
    }

    private void ensureProfileLoaded(Map<String, Object> profile) {
        ensureProfileLoaded(String.valueOf(profile.getOrDefault("id", "")).trim(), profile);
    }

    private void ensureProfileLoaded(String providerKey, Map<String, Object> profile) {
        if (providerKey.isBlank()) {
            return;
        }
        // Preserve the HTTP client's connection pool until this profile actually changes.
        if (profile.equals(loadedProfiles.get(providerKey)) && providers.containsKey(providerKey)) {
            return;
        }
        LlmProvider provider = createProvider(profile);
        if (provider == null) {
            loadedProfiles.remove(providerKey);
            if (providers.remove(providerKey) != null) {
                log.info("Removed unavailable LLM profile: {} ({})", providerKey, profile.get("provider"));
            }
            return;
        }
        providers.put(providerKey, provider);
        loadedProfiles.put(providerKey, new LinkedHashMap<>(profile));
        log.info("Loaded LLM profile: {} ({})", providerKey, profile.get("provider"));
        // 基础档案重建后，派生的按模型覆盖的 provider 全部作废，下次用到时按新档案懒加载
        String baseId = String.valueOf(profile.getOrDefault("id", "")).trim();
        if (providerKey.equals(baseId)) {
            String prefix = baseId + "::";
            loadedProfiles.keySet().removeIf(key -> {
                if (!key.startsWith(prefix)) {
                    return false;
                }
                providers.remove(key);
                return true;
            });
        }
    }

    private LlmProvider createProvider(Map<String, Object> profile) {
        String providerId = String.valueOf(profile.getOrDefault("provider", "custom")).trim().toLowerCase();
        String apiKey = stringValue(profile.get("api_key"));
        if (!"mock".equals(providerId) && apiKey.isBlank()) {
            return null;
        }

        String baseUrl = stringValue(profile.get("base_url"));
        String model = stringValue(profile.get("model"));
        String apiStyle = stringValue(profile.get("api_style"));
        int maxTokens = intValue(profile.get("max_tokens"), openaiMaxTokens);
        double temperature = doubleValue(profile.get("temperature"), openaiTemperature);

        if (usesResponsesApi(providerId, baseUrl, apiStyle)) {
            return new ResponsesProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
        }

        return switch (providerId) {
            case "anthropic" -> new AnthropicProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            case "mock" -> new MockProvider();
            // 其余（openai/custom/deepseek/qwen/gemini/grok/kimi/glm 及未知类型）统一走 OpenAI 兼容协议
            default -> new OpenAiProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature,
                    DEFAULT_BASE_URLS.containsKey(providerId) || "custom".equals(providerId) ? providerId : "openai",
                    Boolean.parseBoolean(stringValue(profile.get("compatibility_mode"))));
        };
    }

    private String defaultBaseUrl(String providerId, String explicitBaseUrl) {
        if (explicitBaseUrl != null && !explicitBaseUrl.isBlank()) {
            return explicitBaseUrl;
        }
        return DEFAULT_BASE_URLS.getOrDefault(providerId, openaiBaseUrl);
    }

    private boolean usesResponsesApi(String providerId, String baseUrl, String apiStyle) {
        String normalizedStyle = stringValue(apiStyle).toLowerCase(Locale.ROOT);
        if ("responses".equals(normalizedStyle)) {
            return true;
        }
        if (!"custom".equals(providerId) && !"openai".equals(providerId)) {
            return false;
        }
        String normalizedBaseUrl = stringValue(baseUrl).toLowerCase(Locale.ROOT);
        return normalizedBaseUrl.endsWith("/codex")
                || normalizedBaseUrl.contains("/codex/")
                || normalizedBaseUrl.endsWith("/responses");
    }

    private List<Map<String, Object>> readProfiles() {
        Path path = projectStorage.getDataPath().resolve("llm_profiles.json");
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 llm_profiles.json 失败", e);
            return List.of();
        }
    }

    private Map<String, Object> readAssignments() {
        Path path = projectStorage.getDataPath().resolve("agent_assignments.json");
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 agent_assignments.json 失败", e);
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
