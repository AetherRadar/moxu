package com.moxu.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory Pack 存储（对齐 Python MemoryPackStorage）
 * 存储路径：data/{projectId}/memory_packs/{chapter}.json
 */
@Slf4j
@Component
public class MemoryPackStorage extends BaseStorage {

    private static final int MAX_MEMORY_PACK_HISTORY = 3;

    /** Later chapters may use facts or summaries indirectly; invalidate conservatively, rebuild lazily. */
    public synchronized void invalidateFrom(String projectId, String changedChapter) throws IOException {
        String changed = com.moxu.analysis.AnalysisStorage.chapter(changedChapter);
        Path directory = getProjectPath(projectId).resolve("memory_packs");
        if (!Files.exists(directory)) return;
        String[] boundary = changed.substring(1).split("C");
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String chapter = file.getFileName().toString().replaceFirst("\\.(json|yaml)$", "");
                if (!chapter.matches("V\\d+C\\d+")) continue;
                String[] parts = chapter.substring(1).split("C");
                int volume = Integer.compare(Integer.parseInt(parts[0]), Integer.parseInt(boundary[0]));
                if (volume < 0 || volume == 0 && Integer.parseInt(parts[1]) < Integer.parseInt(boundary[1])) continue;
                Map<String, Object> pack = readPack(projectId, chapter);
                if (pack == null || Boolean.TRUE.equals(pack.get("context_stale"))) continue;
                pack.put("context_stale", true);
                pack.put("invalidated_by", changed);
                writePack(projectId, chapter, pack);
            }
        }
    }

    /**
     * 读取 memory pack
     */
    public Map<String, Object> readPack(String projectId, String chapter) {
        Path jsonPath = getPackPath(projectId, chapter);
        if (Files.exists(jsonPath)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> pack = jsonMapper.readValue(Files.readString(jsonPath), Map.class);
                String canonical = canonicalizeChapterId(chapter);
                if (!canonical.isBlank()) {
                    pack.put("chapter", canonical);
                }
                return pack;
            } catch (Exception e) {
                log.warn("readPack JSON 失败: {}", jsonPath, e);
            }
        }

        // 兼容：历史版本可能写成 YAML
        Path yamlPath = getProjectPath(projectId).resolve("memory_packs").resolve(canonicalizeChapterId(chapter) + ".yaml");
        if (Files.exists(yamlPath)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> pack = readYaml(yamlPath, Map.class);
                String canonical = canonicalizeChapterId(chapter);
                if (!canonical.isBlank()) {
                    pack.put("chapter", canonical);
                }
                return pack;
            } catch (Exception e) {
                log.warn("readPack YAML 失败: {}", yamlPath, e);
            }
        }

        return null;
    }

    /**
     * 写入 memory pack
     */
    public void writePack(String projectId, String chapter, Map<String, Object> pack) throws IOException {
        Path filePath = getPackPath(projectId, chapter);
        ensureDir(filePath.getParent());

        // 写入前 rotate 历史
        if (Files.exists(filePath)) {
            rotateHistory(filePath);
        }

        String canonical = canonicalizeChapterId(chapter);
        if (canonical != null && !canonical.isBlank()) {
            pack.put("chapter", canonical);
        }
        pack.putIfAbsent("built_at", Instant.now().toString());

        String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pack);
        atomicWrite(filePath, json);
    }

    /**
     * 删除 memory pack
     */
    public boolean deletePack(String projectId, String chapter) {
        Path filePath = getPackPath(projectId, chapter);
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("deletePack 失败: {}", filePath, e);
            return false;
        }
    }

    public Map<String, Object> buildStatus(String chapter, Map<String, Object> pack) {
        String canonical = canonicalizeChapterId(chapter);
        if (pack == null || pack.isEmpty()) {
            return Map.of(
                    "exists", false,
                    "chapter", canonical
            );
        }

        Map<String, Object> payload = pack.get("payload") instanceof Map<?, ?> map
                ? castStringObjectMap(map)
                : Map.of();
        Map<String, Object> evidencePack = payload.get("evidence_pack") instanceof Map<?, ?> map
                ? castStringObjectMap(map)
                : Map.of();
        Map<String, Object> evidenceStats = evidencePack.get("stats") instanceof Map<?, ?> map
                ? castStringObjectMap(map)
                : Map.of();

        Map<String, Object> snapshot = pack.get("card_snapshot") instanceof Map<?, ?> map
                ? castStringObjectMap(map)
                : Map.of();

        int characters = sizeOf(snapshot.get("characters"));
        int world = sizeOf(snapshot.get("world"));
        if (characters == 0 && payload.get("character_cards") instanceof List<?> list) {
            characters = list.size();
        }
        if (world == 0 && payload.get("world_cards") instanceof List<?> list) {
            world = list.size();
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("exists", true);
        status.put("chapter", canonical);
        status.put("built_at", pack.get("built_at"));
        status.put("source", pack.get("source"));
        status.put("evidence_stats", evidenceStats);
        status.put("card_snapshot", Map.of("characters", characters, "world", world));
        status.put("has_working_memory", payload.get("working_memory") != null && !String.valueOf(payload.get("working_memory")).isBlank());
        status.put("research_stop_reason", payload.getOrDefault("research_stop_reason", ""));
        status.put("questions_count", sizeOf(payload.get("questions")));
        return status;
    }

    private Path getPackPath(String projectId, String chapter) {
        // 规范化章节 ID 作为文件名
        String normalized = canonicalizeChapterId(chapter);
        return getProjectPath(projectId).resolve("memory_packs").resolve(normalized + ".json");
    }

    private String canonicalizeChapterId(String chapter) {
        if (chapter == null || chapter.isBlank()) return "unknown";
        String upper = chapter.trim().toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
                int ch = Integer.parseInt(upper.substring(cIdx + 1));
                return String.format("V%dC%d", vol, ch);
            } catch (NumberFormatException ignored) {}
        }
        return chapter.trim();
    }

    private void rotateHistory(Path packPath) {
        try {
            Path historyDir = packPath.getParent().resolve("history");
            ensureDir(historyDir);
            String stem = packPath.getFileName().toString().replace(".json", "");
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            Path backup = historyDir.resolve(stem + "_" + ts + ".json");
            Files.move(packPath, backup, StandardCopyOption.REPLACE_EXISTING);

            // prune old backups
            List<Path> backups = Files.list(historyDir)
                    .filter(p -> p.getFileName().toString().startsWith(stem + "_") && p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .toList();
            if (backups.size() > MAX_MEMORY_PACK_HISTORY) {
                for (int i = 0; i < backups.size() - MAX_MEMORY_PACK_HISTORY; i++) {
                    try {
                        Files.deleteIfExists(backups.get(i));
                    } catch (IOException ignored) {}
                }
            }
        } catch (Exception e) {
            // best-effort; do not fail write
            log.debug("rotateHistory skipped: {}", e.getMessage());
        }
    }

    private Map<String, Object> castStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private int sizeOf(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
