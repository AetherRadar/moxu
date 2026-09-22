package com.wenshape.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 配置控制器 - LLM 配置管理（持久化到 data/ 目录，与 Python 后端共享）
 */
@Slf4j
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final int DEFAULT_PROFILE_MAX_TOKENS = 8000;

    private final ProjectStorage projectStorage;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Set<String> ALLOWED_AGENTS = new LinkedHashSet<>(
            List.of("archivist", "writer", "editor", "rewrite")
    );

    // ========== LLM Profiles ==========

    @GetMapping("/llm/profiles")
    public List<Map<String, Object>> getProfiles() {
        return readProfiles();
    }

    @PostMapping("/llm/profiles")
    public Map<String, Object> saveProfile(@RequestBody Map<String, Object> profile) {
        Map<String, Object> incoming = profile == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profile);
        String rawId = incoming.get("id") == null ? "" : incoming.get("id").toString().trim();
        final String id = rawId.isBlank() ? UUID.randomUUID().toString() : rawId;
        incoming.put("id", id);

        long now = System.currentTimeMillis() / 1000;
        incoming.put("updated_at", now);

        String provider = stringValue(incoming.get("provider")).toLowerCase(Locale.ROOT);
        incoming.put("provider", provider.isBlank() ? "custom" : provider);
        String apiStyle = stringValue(incoming.get("api_style")).toLowerCase(Locale.ROOT);
        if (apiStyle.isBlank()) {
            incoming.remove("api_style");
        } else {
            incoming.put("api_style", apiStyle);
        }

        String name = stringValue(incoming.get("name"));
        if (name.isBlank()) {
            incoming.put("name", incoming.get("provider") + ":" + id.substring(0, Math.min(8, id.length())));
        }
        incoming.put("max_tokens", normalizeMaxTokens(incoming.get("max_tokens")));
        incoming.put("temperature", normalizeTemperature(incoming.get("temperature")));

        List<Map<String, Object>> profiles = readProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            Map<String, Object> existing = profiles.get(i);
            if (!id.equals(stringValue(existing.get("id")))) {
                continue;
            }
            incoming.put("created_at", longValue(existing.get("created_at"), now));
            Map<String, Object> merged = new LinkedHashMap<>(existing);
            merged.putAll(incoming);
            profiles.set(i, merged);
            incoming = merged;
            replaced = true;
            break;
        }
        if (!replaced) {
            incoming.putIfAbsent("created_at", now);
            profiles.add(incoming);
        }
        writeProfiles(profiles);

        log.info("Saved LLM profile: {}", incoming.get("name"));
        return incoming;
    }

    @PostMapping("/llm/profiles/test")
    public Map<String, Object> testProfile(@RequestBody Map<String, Object> profile) {
        Map<String, Object> incoming = profile == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profile);
        String rawId = stringValue(incoming.get("id"));
        String id = rawId.isBlank() ? "test-profile" : rawId;
        incoming.put("id", id);

        String provider = stringValue(incoming.get("provider")).toLowerCase(Locale.ROOT);
        provider = provider.isBlank() ? "custom" : provider;
        incoming.put("provider", provider);

        String apiStyle = stringValue(incoming.get("api_style")).toLowerCase(Locale.ROOT);
        if (apiStyle.isBlank()) {
            incoming.remove("api_style");
        } else {
            incoming.put("api_style", apiStyle);
        }

        String name = stringValue(incoming.get("name"));
        if (name.isBlank()) {
            incoming.put("name", provider + ":" + id.substring(0, Math.min(8, id.length())));
        }
        incoming.put("max_tokens", normalizeMaxTokens(incoming.get("max_tokens")));
        incoming.put("temperature", normalizeTemperature(incoming.get("temperature")));

        if (!"mock".equals(provider) && stringValue(incoming.get("api_key")).isBlank()) {
            return failedTestResult("请先填写接口密钥", "validation");
        }
        if (!"mock".equals(provider) && stringValue(incoming.get("model")).isBlank()) {
            return failedTestResult("请先填写模型名称", "validation");
        }

        try {
            LlmResponse response = llmGateway.testProfile(incoming);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("provider", response.getProvider());
            result.put("model", response.getModel());
            result.put("finish_reason", response.getFinishReason());
            result.put("elapsed_ms", response.getElapsedMs());
            result.put("preview", abbreviate(response.getContent(), 160));
            return result;
        } catch (ResponseStatusException e) {
            return failedTestResult(friendlyTestErrorMessage(rootCauseMessage(e)), "upstream");
        } catch (IllegalArgumentException e) {
            return failedTestResult(friendlyTestErrorMessage(rootCauseMessage(e)), "validation");
        } catch (Exception e) {
            return failedTestResult(friendlyTestErrorMessage(rootCauseMessage(e)), "upstream");
        }
    }

    @DeleteMapping("/llm/profiles/{profileId}")
    public Map<String, Object> deleteProfile(@PathVariable String profileId) {
        List<Map<String, Object>> profiles = readProfiles();
        profiles.removeIf(p -> profileId.equals(p.get("id")));
        writeProfiles(profiles);

        Map<String, Object> assignments = readAssignments();
        boolean changed = false;
        for (String agent : ALLOWED_AGENTS) {
            if (profileId.equals(stringValue(assignments.get(agent)))) {
                assignments.put(agent, "");
                changed = true;
            }
        }
        if (changed) {
            writeAssignments(assignments);
        }

        log.info("Deleted LLM profile: {}", profileId);
        return Map.of("success", true);
    }

    // ========== Agent Assignments ==========

    @GetMapping("/llm/assignments")
    public Map<String, Object> getAssignments() {
        return readAssignments();
    }

    @PostMapping("/llm/assignments")
    public Map<String, Object> updateAssignments(@RequestBody Map<String, Object> newAssignments) {
        Map<String, Object> merged = new LinkedHashMap<>(readAssignments());
        if (newAssignments != null) {
            for (Map.Entry<String, Object> e : newAssignments.entrySet()) {
                if (!ALLOWED_AGENTS.contains(e.getKey()) || e.getValue() == null) {
                    continue;
                }
                merged.put(e.getKey(), stringValue(e.getValue()));
            }
        }
        writeAssignments(merged);
        log.info("Updated agent assignments: {}", merged);
        return Map.of("success", true);
    }

    // ========== Providers Meta ==========

    @GetMapping("/llm/providers_meta")
    public List<Map<String, Object>> getProvidersMeta() {
        return List.of(
                Map.of("id", "openai", "label", "OpenAI", "fields", List.of("api_key", "model")),
                Map.of("id", "anthropic", "label", "Anthropic (Claude)", "fields", List.of("api_key", "model")),
                Map.of("id", "deepseek", "label", "DeepSeek", "fields", List.of("api_key", "model")),
                Map.of("id", "qwen", "label", "通义千问", "fields", List.of("api_key", "model")),
                Map.of("id", "gemini", "label", "Gemini (Google)", "fields", List.of("api_key", "model")),
                Map.of("id", "kimi", "label", "Kimi (Moonshot)", "fields", List.of("api_key", "model")),
                Map.of("id", "glm", "label", "GLM (智谱)", "fields", List.of("api_key", "model")),
                Map.of("id", "grok", "label", "Grok (xAI)", "fields", List.of("api_key", "model")),
                Map.of("id", "mock", "label", "Mock / Demo", "fields", List.of()),
                Map.of("id", "custom", "label", "Custom / OpenAI Compatible",
                        "fields", List.of("base_url", "api_key", "model", "api_style"))
        );
    }

    // ========== 私有方法 ==========

    private Path profilesPath() {
        return projectStorage.getDataPath().resolve("llm_profiles.json");
    }

    private Path assignmentsPath() {
        return projectStorage.getDataPath().resolve("agent_assignments.json");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readProfiles() {
        Path path = profilesPath();
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String content = Files.readString(path);
            List<Map<String, Object>> profiles = objectMapper.readValue(content, new TypeReference<>() {});
            List<Map<String, Object>> cleaned = new ArrayList<>();
            long now = System.currentTimeMillis() / 1000;
            for (Map<String, Object> raw : profiles) {
                if (raw == null) {
                    continue;
                }
                Map<String, Object> profile = new LinkedHashMap<>(raw);
                String id = stringValue(profile.get("id"));
                if (id.isBlank()) {
                    id = UUID.randomUUID().toString();
                    profile.put("id", id);
                }
                String provider = stringValue(profile.get("provider")).toLowerCase(Locale.ROOT);
                profile.put("provider", provider.isBlank() ? "custom" : provider);
                String apiStyle = stringValue(profile.get("api_style")).toLowerCase(Locale.ROOT);
                if (apiStyle.isBlank()) {
                    profile.remove("api_style");
                } else {
                    profile.put("api_style", apiStyle);
                }
                String name = stringValue(profile.get("name"));
                if (name.isBlank()) {
                    profile.put("name", profile.get("provider") + ":" + id.substring(0, Math.min(8, id.length())));
                }
                profile.put("max_tokens", normalizeMaxTokens(profile.get("max_tokens")));
                profile.put("temperature", normalizeTemperature(profile.get("temperature")));
                profile.putIfAbsent("created_at", now);
                cleaned.add(profile);
            }
            return cleaned;
        } catch (IOException e) {
            log.error("读取 llm_profiles.json 失败", e);
            return new ArrayList<>();
        }
    }

    private void writeProfiles(List<Map<String, Object>> profiles) {
        try {
            Files.createDirectories(profilesPath().getParent());
            String content = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(profiles);
            Files.writeString(profilesPath(), content);
        } catch (IOException e) {
            log.error("写入 llm_profiles.json 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAssignments() {
        Path path = assignmentsPath();
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (String agent : ALLOWED_AGENTS) {
            cleaned.put(agent, "");
        }
        if (!Files.exists(path)) return cleaned;
        try {
            String content = Files.readString(path);
            Map<String, Object> assignments = objectMapper.readValue(content, new TypeReference<>() {});
            Set<String> profileIds = new LinkedHashSet<>();
            for (Map<String, Object> profile : readProfiles()) {
                profileIds.add(stringValue(profile.get("id")));
            }
            for (String agent : ALLOWED_AGENTS) {
                String value = stringValue(assignments.get(agent));
                cleaned.put(agent, profileIds.contains(value) ? value : "");
            }
            return cleaned;
        } catch (IOException e) {
            log.error("读取 agent_assignments.json 失败", e);
            return cleaned;
        }
    }

    private void writeAssignments(Map<String, Object> assignments) {
        try {
            Files.createDirectories(assignmentsPath().getParent());
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (String agent : ALLOWED_AGENTS) {
                cleaned.put(agent, stringValue(assignments.get(agent)));
            }
            String content = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cleaned);
            Files.writeString(assignmentsPath(), content);
        } catch (IOException e) {
            log.error("写入 agent_assignments.json 失败", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
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

    private int normalizeMaxTokens(Object value) {
        int parsed = intValue(value, DEFAULT_PROFILE_MAX_TOKENS);
        if (parsed <= 0) {
            return DEFAULT_PROFILE_MAX_TOKENS;
        }
        return Math.min(200000, parsed);
    }

    private double normalizeTemperature(Object value) {
        double parsed = doubleValue(value, 0.7);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return 0.7;
        }
        return Math.max(0.0, Math.min(2.0, parsed));
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private Map<String, Object> failedTestResult(String message, String kind) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("kind", kind);
        result.put("message", message);
        return result;
    }

    private String friendlyTestErrorMessage(String raw) {
        String message = raw == null ? "" : raw.trim();
        if (message.isBlank()) {
            return "模型测试失败";
        }
        if (message.contains("LLM API error 401")) {
            return "上游接口返回 401：API Key 无效，或当前渠道不接受这把 Key。";
        }
        if (message.contains("LLM API error 403")) {
            return "上游接口返回 403：当前 Key 没有权限访问该模型，或该渠道禁用了此模型。";
        }
        if (message.contains("LLM API error 404")) {
            return "上游接口返回 404：Base URL、接口路径或模型名不匹配。";
        }
        if (message.contains("LLM API error 429")) {
            return "上游接口返回 429：触发限流或额度不足，请稍后再试。";
        }
        if (message.contains("LLM API error 500")) {
            return "上游接口返回 500：说明接口已经连通，但当前模型调用失败。通常是模型名不可用、网关兼容性不足，或渠道本身故障。";
        }
        if (message.contains("Failed to connect")
                || message.contains("ConnectException")
                || message.contains("UnknownHostException")) {
            return "无法连接到上游接口：请检查 Base URL、网络连通性或代理配置。";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "请求超时：上游接口响应过慢，或当前渠道不稳定。";
        }
        return message;
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "模型测试失败";
        }
        return message;
    }
}
