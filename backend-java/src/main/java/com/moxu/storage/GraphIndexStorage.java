package com.moxu.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 导书/拆书关系索引存储。
 */
@Component
public class GraphIndexStorage extends BaseStorage {

    private static final String INDEX_DIR = "indexes";
    private static final String CHARACTER_ALIAS_INDEX = "character_alias_index.json";
    private static final String CHAPTER_MENTIONS_INDEX = "chapter_mentions.json";
    private static final String RELATION_CANDIDATES_INDEX = "relation_candidates.json";

    public void saveCharacterAliasIndex(String projectId, Map<String, Object> data) throws IOException {
        writeJson(indexDir(projectId).resolve(CHARACTER_ALIAS_INDEX), data);
    }

    public Optional<Map<String, Object>> getCharacterAliasIndex(String projectId) {
        return readIndex(indexDir(projectId).resolve(CHARACTER_ALIAS_INDEX));
    }

    public void saveChapterMentionsIndex(String projectId, Map<String, Object> data) throws IOException {
        writeJson(indexDir(projectId).resolve(CHAPTER_MENTIONS_INDEX), data);
    }

    public Optional<Map<String, Object>> getChapterMentionsIndex(String projectId) {
        return readIndex(indexDir(projectId).resolve(CHAPTER_MENTIONS_INDEX));
    }

    public void saveRelationCandidatesIndex(String projectId, Map<String, Object> data) throws IOException {
        writeJson(indexDir(projectId).resolve(RELATION_CANDIDATES_INDEX), data);
    }

    public Optional<Map<String, Object>> getRelationCandidatesIndex(String projectId) {
        return readIndex(indexDir(projectId).resolve(RELATION_CANDIDATES_INDEX));
    }

    private Optional<Map<String, Object>> readIndex(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readJson(path, new TypeReference<>() {}));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path indexDir(String projectId) {
        return getProjectPath(projectId).resolve(INDEX_DIR);
    }
}
