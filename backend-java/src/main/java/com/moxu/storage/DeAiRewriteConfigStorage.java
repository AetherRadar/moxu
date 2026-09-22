package com.moxu.storage;

import com.moxu.model.entity.DeAiRewriteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 项目级去 AI 味配置存储
 */
@Slf4j
@Component
public class DeAiRewriteConfigStorage extends BaseStorage {

    public DeAiRewriteConfig getConfig(String projectId) {
        Path path = configPath(projectId);
        if (!Files.exists(path)) {
            return DeAiRewriteConfig.defaults();
        }
        try {
            String content = Files.readString(path);
            DeAiRewriteConfig loaded = jsonMapper.readValue(content, DeAiRewriteConfig.class);
            return loaded != null ? loaded.normalized() : DeAiRewriteConfig.defaults();
        } catch (Exception e) {
            log.warn("读取去 AI 味配置失败，回退默认配置: {}", e.getMessage());
            return DeAiRewriteConfig.defaults();
        }
    }

    public DeAiRewriteConfig saveConfig(String projectId, DeAiRewriteConfig config) throws IOException {
        DeAiRewriteConfig normalized = (config != null ? config : DeAiRewriteConfig.defaults()).normalized();
        Path path = configPath(projectId);
        ensureDir(path.getParent());
        atomicWrite(path, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized));
        return normalized;
    }

    private Path configPath(String projectId) {
        return getProjectPath(projectId).resolve("de_ai_config.json");
    }
}
