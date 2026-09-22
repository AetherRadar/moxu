package com.moxu.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * bindings 索引状态存储。
 *
 * <p>当前仅持久化“哪些章节的 bindings 处于 stale 状态”，
 * 用于卡片变更后的延迟/后台重建。</p>
 */
@Component
public class BindingsStateStorage extends BaseStorage {

    private static final String INDEX_DIR = "index";
    private static final String STATE_FILE = "bindings_state.yaml";

    public Set<String> getStaleChapters(String projectId) {
        return readState(projectId)
                .map(this::extractStaleChapters)
                .orElseGet(LinkedHashSet::new);
    }

    public void saveStaleChapters(String projectId, Set<String> chapters) throws IOException {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (chapters != null) {
            for (String chapter : chapters) {
                if (chapter != null && !chapter.isBlank()) {
                    normalized.add(chapter.trim());
                }
            }
        }

        Path path = statePath(projectId);
        if (normalized.isEmpty()) {
            Files.deleteIfExists(path);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stale_chapters", new ArrayList<>(normalized));
        writeYaml(path, payload);
    }

    private Optional<Map<String, Object>> readState(String projectId) {
        Path path = statePath(projectId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readYamlAsMap(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private LinkedHashSet<String> extractStaleChapters(Map<String, Object> raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        Object value = raw.get("stale_chapters");
        if (!(value instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String chapter = String.valueOf(item).trim();
            if (!chapter.isBlank()) {
                result.add(chapter);
            }
        }
        return result;
    }

    private Path statePath(String projectId) {
        return getProjectPath(projectId)
                .resolve(INDEX_DIR)
                .resolve(STATE_FILE);
    }
}
