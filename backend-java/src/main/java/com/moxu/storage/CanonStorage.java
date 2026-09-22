package com.moxu.storage;

import com.moxu.model.entity.Fact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
/**
 * 事实表存储
 */
@Slf4j
@Component
public class CanonStorage extends BaseStorage {

    private static final String CHARACTER_STATE_LOG_NAME = "character_state.jsonl";
    private static final java.util.regex.Pattern VOLUME_CHAPTER_PATTERN =
            java.util.regex.Pattern.compile("(?i)V(\\d+)C(\\d+)");
    private static final java.util.regex.Pattern CHAPTER_ONLY_PATTERN =
            java.util.regex.Pattern.compile("(?i)C(\\d+)");
    
    public List<Fact> listFacts(String projectId) {
        Path canonDir = getProjectPath(projectId).resolve("canon");
        if (!Files.exists(canonDir)) {
            return new ArrayList<>();
        }
        
        List<Fact> facts = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(canonDir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                try {
                    Fact fact = readYaml(p, Fact.class);
                    if (fact.getId() == null || fact.getId().isEmpty()) {
                        fact.setId(p.getFileName().toString().replace(".yaml", ""));
                    }
                    facts.add(fact);
                } catch (IOException e) {
                    log.error("读取事实失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出事实失败: {}", canonDir, e);
        }
        
        return facts;
    }

    public List<Map<String, Object>> listFactsRaw(String projectId) {
        Path canonDir = getProjectPath(projectId).resolve("canon");
        if (!Files.exists(canonDir)) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> facts = new ArrayList<>();
        try (Stream<Path> paths = Files.list(canonDir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                try {
                    Map<String, Object> fact = new LinkedHashMap<>(readYamlAsMap(p));
                    fact.putIfAbsent("id", p.getFileName().toString().replace(".yaml", ""));
                    facts.add(fact);
                } catch (IOException e) {
                    log.error("读取原始事实失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出原始事实失败: {}", canonDir, e);
        }
        return facts;
    }
    
    public Optional<Fact> getFact(String projectId, String factId) {
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(factId + ".yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Fact fact = readYaml(filePath, Fact.class);
            if (fact.getId() == null || fact.getId().isEmpty()) {
                fact.setId(factId);
            }
            return Optional.of(fact);
        } catch (IOException e) {
            log.error("读取事实失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public Fact saveFact(String projectId, Fact fact) throws IOException {
        if (fact.getId() == null || fact.getId().isEmpty()) {
            fact.setId(generateFactId(projectId));
        }
        
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(fact.getId() + ".yaml");
        writeYaml(filePath, fact);
        return fact;
    }
    
    public boolean deleteFact(String projectId, String factId) {
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(factId + ".yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除事实失败: {}", filePath, e);
            return false;
        }
    }
    
    /**
     * 按章节批量删除 facts（overwrite 时使用，对齐 Python delete_facts_by_chapter）
     */
    public int deleteFactsByChapter(String projectId, String chapter) {
        List<Fact> facts = listFacts(projectId);
        int deleted = 0;
        for (Fact fact : facts) {
            if (chapter.equals(fact.getIntroducedIn())) {
                if (deleteFact(projectId, fact.getId())) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * 保存时间线事件（对齐 Python add_timeline_event）
     * 存储为 timeline/ 目录下的 YAML 文件
     */
    public void saveTimelineEvent(String projectId, Map<String, Object> event) throws IOException {
        Path dir = getProjectPath(projectId).resolve("timeline");
        Files.createDirectories(dir);

        String id = (String) event.get("id");
        if (id == null || id.isBlank()) {
            id = generateTimelineId(dir);
            event = new LinkedHashMap<>(event);
            event.put("id", id);
        }
        Path filePath = dir.resolve(id + ".yaml");
        writeYaml(filePath, event);
    }

    /**
     * 保存角色状态（对齐 Python update_character_state）
     * 主存储改为 canon/character_state.jsonl 的 append-only 历史日志；
     * 同时维护 character_states/<角色>.yaml 作为“最新快照”兼容层。
     */
    public void saveCharacterState(String projectId, Map<String, Object> state) throws IOException {
        Map<String, Object> normalized = normalizeCharacterState(state);
        String character = stringValue(normalized, "character");
        if (character.isBlank()) {
            log.warn("saveCharacterState: character name is blank, skipping");
            return;
        }

        List<Map<String, Object>> history = readCharacterStateHistory(projectId);
        String dedupKey = characterStateDedupKey(normalized);
        boolean duplicate = history.stream()
                .map(this::normalizeCharacterState)
                .anyMatch(existing -> characterStateDedupKey(existing).equals(dedupKey));
        if (!duplicate) {
            appendJsonl(characterStateLogPath(projectId), normalized);
        }

        rebuildCharacterStateSnapshots(projectId);
    }

    public List<Map<String, Object>> listTimelineEvents(String projectId) {
        Path dir = getProjectPath(projectId).resolve("timeline");
        return readYamlMaps(dir);
    }

    public List<Map<String, Object>> listTimelineEventsByChapter(String projectId, String chapter) {
        String canonical = chapter != null ? chapter.trim() : "";
        return listTimelineEvents(projectId).stream()
                .filter(item -> canonical.equals(item.getOrDefault("source", "")))
                .toList();
    }

    public int deleteTimelineEventsByChapter(String projectId, String chapter) {
        Path dir = getProjectPath(projectId).resolve("timeline");
        if (!Files.exists(dir)) {
            return 0;
        }
        int deleted = 0;
        for (Map<String, Object> item : listTimelineEventsByChapter(projectId, chapter)) {
            String id = String.valueOf(item.getOrDefault("id", "")).trim();
            if (id.isBlank()) {
                continue;
            }
            Path filePath = dir.resolve(id + ".yaml");
            try {
                if (Files.deleteIfExists(filePath)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("删除时间线事件失败: {}", filePath, e);
            }
        }
        return deleted;
    }

    private static final java.util.regex.Pattern TIMELINE_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

    /** id 白名单校验，避免路径穿越。 */
    private boolean isValidTimelineId(String eventId) {
        return eventId != null && TIMELINE_ID_PATTERN.matcher(eventId).matches();
    }

    public Optional<Map<String, Object>> getTimelineEvent(String projectId, String eventId) {
        if (!isValidTimelineId(eventId)) {
            return Optional.empty();
        }
        Path filePath = getProjectPath(projectId).resolve("timeline").resolve(eventId + ".yaml");
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>(readYamlAsMap(filePath));
            event.putIfAbsent("id", eventId);
            return Optional.of(event);
        } catch (IOException e) {
            log.error("读取时间线事件失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    /** 更新既有事件：id 以路径为准（忽略 body 里的 id），文件必须已存在。 */
    public void updateTimelineEvent(String projectId, String eventId, Map<String, Object> event)
            throws IOException {
        if (getTimelineEvent(projectId, eventId).isEmpty()) {
            throw new NoSuchElementException("Timeline event not found: " + eventId);
        }
        Map<String, Object> payload = new LinkedHashMap<>(event);
        payload.put("id", eventId);
        Path filePath = getProjectPath(projectId).resolve("timeline").resolve(eventId + ".yaml");
        writeYaml(filePath, payload);
    }

    public boolean deleteTimelineEvent(String projectId, String eventId) {
        if (!isValidTimelineId(eventId)) {
            return false;
        }
        Path filePath = getProjectPath(projectId).resolve("timeline").resolve(eventId + ".yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除时间线事件失败: {}", filePath, e);
            return false;
        }
    }

    public List<Map<String, Object>> listCharacterStates(String projectId) {
        return readCharacterStateHistory(projectId);
    }

    public Optional<Map<String, Object>> getCharacterState(String projectId, String characterName) {
        if (characterName == null || characterName.isBlank()) {
            return Optional.empty();
        }
        String target = characterName.trim();
        Map<String, Object> latest = null;
        for (Map<String, Object> state : readCharacterStateHistory(projectId)) {
            Map<String, Object> normalized = normalizeCharacterState(state);
            if (!target.equals(stringValue(normalized, "character"))) {
                continue;
            }
            if (latest == null || compareStateRecency(normalized, latest) >= 0) {
                latest = normalized;
            }
        }
        return Optional.ofNullable(latest);
    }

    public int deleteCharacterStatesByChapter(String projectId, String chapter) {
        String normalizedChapter = chapter == null ? "" : chapter.trim();
        Path logPath = characterStateLogPath(projectId);
        List<Map<String, Object>> history = readCharacterStateHistory(projectId);
        List<Map<String, Object>> rewritten = new ArrayList<>();
        int deleted = 0;

        for (Map<String, Object> state : history) {
            Map<String, Object> normalized = normalizeCharacterState(state);
            String lastSeen = stringValue(normalized, "last_seen");
            if (!normalizedChapter.isBlank() && normalizedChapter.equals(lastSeen)) {
                deleted++;
                continue;
            }
            rewritten.add(normalized);
        }

        try {
            if (!rewritten.isEmpty()) {
                writeJsonl(logPath, rewritten);
            } else {
                Files.deleteIfExists(logPath);
            }
            writeCharacterStateSnapshots(projectId, rewritten);
        } catch (IOException e) {
            log.warn("删除角色状态失败: {}", projectId, e);
        }
        return deleted;
    }

    private Path characterStateLogPath(String projectId) {
        return getProjectPath(projectId).resolve("canon").resolve(CHARACTER_STATE_LOG_NAME);
    }

    private Path characterStateSnapshotDir(String projectId) {
        return getProjectPath(projectId).resolve("character_states");
    }

    private List<Map<String, Object>> readCharacterStateHistory(String projectId) {
        List<Map<String, Object>> history = new ArrayList<>();
        Set<String> charactersSeenInLog = new LinkedHashSet<>();
        Path logPath = characterStateLogPath(projectId);

        if (Files.exists(logPath)) {
            try {
                for (Map<String, Object> raw : readJsonl(logPath)) {
                    Map<String, Object> normalized = normalizeCharacterState(raw);
                    if (stringValue(normalized, "character").isBlank()) {
                        continue;
                    }
                    history.add(normalized);
                    charactersSeenInLog.add(stringValue(normalized, "character"));
                }
            } catch (IOException e) {
                log.warn("读取角色状态历史失败: {}", logPath, e);
            }
        }

        Path snapshotDir = characterStateSnapshotDir(projectId);
        if (!Files.exists(snapshotDir)) {
            return history;
        }
        for (Map<String, Object> raw : readYamlMaps(snapshotDir)) {
            Map<String, Object> normalized = normalizeCharacterState(raw);
            String character = stringValue(normalized, "character");
            if (character.isBlank() || charactersSeenInLog.contains(character)) {
                continue;
            }
            history.add(normalized);
        }
        return history;
    }

    private void rebuildCharacterStateSnapshots(String projectId) throws IOException {
        writeCharacterStateSnapshots(projectId, readCharacterStateHistory(projectId));
    }

    private void writeCharacterStateSnapshots(String projectId, List<Map<String, Object>> history) throws IOException {
        Path dir = characterStateSnapshotDir(projectId);
        Files.createDirectories(dir);

        Map<String, Map<String, Object>> latestByCharacter = new LinkedHashMap<>();
        for (Map<String, Object> state : history == null ? List.<Map<String, Object>>of() : history) {
            Map<String, Object> normalized = normalizeCharacterState(state);
            String character = stringValue(normalized, "character");
            if (character.isBlank()) {
                continue;
            }
            Map<String, Object> previous = latestByCharacter.get(character);
            if (previous == null || compareStateRecency(normalized, previous) >= 0) {
                latestByCharacter.put(character, normalized);
            }
        }

        Set<String> desiredFiles = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : latestByCharacter.entrySet()) {
            String filename = stateFileName(entry.getKey());
            desiredFiles.add(filename + ".yaml");
            writeYaml(dir.resolve(filename + ".yaml"), entry.getValue());
        }

        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                String name = path.getFileName().toString();
                if (!desiredFiles.contains(name)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private Map<String, Object> normalizeCharacterState(Map<String, Object> state) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (state == null) {
            return normalized;
        }

        String character = firstNonBlank(
                stringValue(state, "character"),
                stringValue(state, "name")
        );
        if (character.isBlank() || looksLikeNonCharacterStateSubject(character)) {
            return normalized;
        }

        String location = stringValue(state, "location");
        String emotionalState = firstNonBlank(
                stringValue(state, "emotional_state"),
                stringValue(state, "emotion")
        );
        List<String> goals = normalizeStringList(state.get("goals"));
        List<String> injuries = normalizeStringList(state.get("injuries"));
        List<String> inventory = normalizeStringList(state.get("inventory"));
        Map<String, String> relationships = normalizeStringMap(state.get("relationships"));
        String stateSummary = buildCharacterStateSummary(
                firstNonBlank(stringValue(state, "state"), stringValue(state, "summary")),
                emotionalState,
                goals,
                injuries,
                inventory,
                relationships,
                location
        );
        String lastSeen = firstNonBlank(
                stringValue(state, "last_seen"),
                stringValue(state, "source"),
                stringValue(state, "chapter")
        );

        normalized.put("character", character);
        normalized.put("state", stateSummary);
        if (!location.isBlank()) {
            normalized.put("location", location);
        }
        if (!emotionalState.isBlank()) {
            normalized.put("emotional_state", emotionalState);
        }
        if (!goals.isEmpty()) {
            normalized.put("goals", goals);
        }
        if (!injuries.isEmpty()) {
            normalized.put("injuries", injuries);
        }
        if (!inventory.isEmpty()) {
            normalized.put("inventory", inventory);
        }
        if (!relationships.isEmpty()) {
            normalized.put("relationships", relationships);
        }
        if (!lastSeen.isBlank()) {
            normalized.put("last_seen", lastSeen);
        }
        return normalized;
    }

    private List<String> normalizeStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = String.valueOf(item == null ? "" : item).trim();
                if (!value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
            return values;
        }
        String text = String.valueOf(raw == null ? "" : raw).trim();
        if (text.isBlank()) {
            return values;
        }
        for (String part : text.split("[,，/、\\n]")) {
            String value = part.trim();
            if (!value.isBlank() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, String> normalizeStringMap(Object raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey() == null ? "" : entry.getKey()).trim();
                String value = String.valueOf(entry.getValue() == null ? "" : entry.getValue()).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
            return values;
        }
        String text = String.valueOf(raw == null ? "" : raw).trim();
        if (text.isBlank()) {
            return values;
        }
        for (String part : text.split("[,，;；\\n]")) {
            String[] pair = part.split("[:：]", 2);
            String key = pair.length > 0 ? pair[0].trim() : "";
            String value = pair.length > 1 ? pair[1].trim() : "";
            if (!key.isBlank() && !value.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private String buildCharacterStateSummary(String explicitState,
                                              String emotionalState,
                                              List<String> goals,
                                              List<String> injuries,
                                              List<String> inventory,
                                              Map<String, String> relationships,
                                              String location) {
        String direct = explicitState == null ? "" : explicitState.trim();
        if (!direct.isBlank()) {
            return direct;
        }

        List<String> parts = new ArrayList<>();
        if (emotionalState != null && !emotionalState.isBlank()) {
            parts.add("情绪：" + emotionalState.trim());
        }
        if (goals != null && !goals.isEmpty()) {
            parts.add("目标：" + String.join("、", goals.subList(0, Math.min(goals.size(), 2))));
        }
        if (injuries != null && !injuries.isEmpty()) {
            parts.add("伤势：" + String.join("、", injuries.subList(0, Math.min(injuries.size(), 2))));
        }
        if (relationships != null && !relationships.isEmpty()) {
            Map.Entry<String, String> first = relationships.entrySet().iterator().next();
            parts.add("关系：" + first.getKey() + "→" + first.getValue());
        }
        if (inventory != null && !inventory.isEmpty()) {
            parts.add("持有：" + String.join("、", inventory.subList(0, Math.min(inventory.size(), 2))));
        }
        if (parts.isEmpty() && location != null && !location.isBlank()) {
            parts.add("位于" + location.trim());
        }
        return String.join("；", parts);
    }

    private int compareStateRecency(Map<String, Object> left, Map<String, Object> right) {
        String leftChapter = stringValue(left, "last_seen");
        String rightChapter = stringValue(right, "last_seen");
        return compareChapterIds(leftChapter, rightChapter);
    }

    private int compareChapterIds(String left, String right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        ChapterRef l = parseChapterRef(left);
        ChapterRef r = parseChapterRef(right);
        if (l != null && r != null) {
            if (l.volume != r.volume) {
                return Integer.compare(l.volume, r.volume);
            }
            if (l.chapter != r.chapter) {
                return Integer.compare(l.chapter, r.chapter);
            }
            return 0;
        }
        if (l != null) {
            return 1;
        }
        if (r != null) {
            return -1;
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private ChapterRef parseChapterRef(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return null;
        }
        String normalized = chapterId.trim();
        java.util.regex.Matcher volumeMatcher = VOLUME_CHAPTER_PATTERN.matcher(normalized);
        if (volumeMatcher.find()) {
            return new ChapterRef(
                    safeParseInt(volumeMatcher.group(1)),
                    safeParseInt(volumeMatcher.group(2))
            );
        }
        java.util.regex.Matcher chapterMatcher = CHAPTER_ONLY_PATTERN.matcher(normalized);
        if (chapterMatcher.find()) {
            return new ChapterRef(1, safeParseInt(chapterMatcher.group(1)));
        }
        return null;
    }

    private int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String characterStateDedupKey(Map<String, Object> state) {
        Map<String, Object> normalized = normalizeCharacterState(state);
        return String.join("|",
                stringValue(normalized, "character"),
                stringValue(normalized, "last_seen"),
                stringValue(normalized, "state"),
                stringValue(normalized, "location"),
                stringValue(normalized, "emotional_state"),
                String.join("、", normalizeStringList(normalized.get("goals"))),
                String.join("、", normalizeStringList(normalized.get("injuries"))),
                String.join("、", normalizeStringList(normalized.get("inventory"))),
                normalizeStringMap(normalized.get("relationships")).toString()
        );
    }

    private String stateFileName(String character) {
        return character.replaceAll("[^\\w\\u4e00-\\u9fff]", "_");
    }

    private boolean looksLikeNonCharacterStateSubject(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.length() > 24) {
            return true;
        }
        if (normalized.contains("、") || normalized.contains("，") || normalized.contains(",")) {
            return true;
        }
        List<String> bodyPartHints = List.of(
                "小腹", "腹部", "胸口", "胸脯", "腰肢", "手指", "指尖", "双腿", "足踝", "脚踝",
                "身体", "肉身", "躯体", "骨骼", "经脉", "丹田", "识海", "子宫", "神魂", "魔胎", "本源"
        );
        for (String hint : bodyPartHints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ChapterRef(int volume, int chapter) {}

    private String generateFactId(String projectId) {
        List<Fact> facts = listFacts(projectId);
        int maxNum = 0;
        
        for (Fact fact : facts) {
            String id = fact.getId();
            if (id != null && id.startsWith("F")) {
                try {
                    int num = Integer.parseInt(id.substring(1));
                    maxNum = Math.max(maxNum, num);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        
        return String.format("F%03d", maxNum + 1);
    }

    private String generateTimelineId(Path dir) {
        int maxNum = 0;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                String name = p.getFileName().toString().replace(".yaml", "");
                if (name.startsWith("T")) {
                    try {
                        int num = Integer.parseInt(name.substring(1));
                        maxNum = Math.max(maxNum, num);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return String.format("T%03d", maxNum + 1);
    }

    /**
     * 冲突检测（对齐 Python canon_storage.detect_conflicts）
     * 存储路径：data/{projectId}/drafts/{chapter}/conflict_report.yaml
     */
    public Map<String, Object> detectConflicts(String projectId, String chapter,
                                                List<Map<String, Object>> newFacts,
                                                List<Map<String, Object>> newTimelineEvents,
                                                List<Map<String, Object>> newCharacterStates) {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        try {
            List<Fact> existingFacts = listFacts(projectId);
            for (Map<String, Object> newFact : safeList(newFacts)) {
                String newStmt = stringValue(newFact, "statement");
                if (newStmt.isBlank()) continue;

                for (Fact existing : existingFacts) {
                    String existingStmt = existing.getStatement() != null ? existing.getStatement() : existing.getContent();
                    if (existingStmt == null || existingStmt.isBlank()) continue;
                    // 跳过同章节的 facts（不与自己冲突）
                    if (chapter.equals(existing.getIntroducedIn())) continue;

                    if (maybeContradict(newStmt, existingStmt)) {
                        Map<String, Object> conflict = new LinkedHashMap<>();
                        conflict.put("type", "fact_conflict");
                        conflict.put("new_fact", newStmt);
                        conflict.put("existing_fact", existingStmt);
                        conflict.put("existing_chapter", existing.getIntroducedIn());
                        conflict.put("severity", "low");
                        conflicts.add(conflict);
                    }
                }
            }

            List<Map<String, Object>> existingEvents = listTimelineEvents(projectId);
            for (Map<String, Object> newEvent : safeList(newTimelineEvents)) {
                String newTime = normalizeText(stringValue(newEvent, "time"));
                if (newTime.isBlank()) {
                    continue;
                }
                Set<String> newParticipants = participantSet(newEvent.get("participants"));
                if (newParticipants.isEmpty()) {
                    continue;
                }
                for (Map<String, Object> existingEvent : existingEvents) {
                    String existingTime = normalizeText(stringValue(existingEvent, "time"));
                    if (!newTime.equals(existingTime)) {
                        continue;
                    }
                    Set<String> existingParticipants = participantSet(existingEvent.get("participants"));
                    Set<String> overlap = new HashSet<>(newParticipants);
                    overlap.retainAll(existingParticipants);
                    if (overlap.isEmpty()) {
                        continue;
                    }
                    String newLocation = normalizeText(stringValue(newEvent, "location"));
                    String oldLocation = normalizeText(stringValue(existingEvent, "location"));
                    String newEventText = normalizeText(stringValue(newEvent, "event"));
                    String oldEventText = normalizeText(stringValue(existingEvent, "event"));
                    if (!Objects.equals(newLocation, oldLocation) || !Objects.equals(newEventText, oldEventText)) {
                        Map<String, Object> conflict = new LinkedHashMap<>();
                        conflict.put("type", "timeline_conflict");
                        conflict.put("time", stringValue(newEvent, "time"));
                        conflict.put("participants", new ArrayList<>(overlap));
                        conflict.put("new_event", stringValue(newEvent, "event"));
                        conflict.put("new_location", stringValue(newEvent, "location"));
                        conflict.put("existing_event", stringValue(existingEvent, "event"));
                        conflict.put("existing_location", stringValue(existingEvent, "location"));
                        conflict.put("existing_chapter", stringValue(existingEvent, "source"));
                        conflict.put("severity", "medium");
                        conflicts.add(conflict);
                        break;
                    }
                }
            }

            int currentChapterNum = parseChapterNumber(chapter);
            for (Map<String, Object> newState : safeList(newCharacterStates)) {
                String character = stringValue(newState, "character");
                String newLocation = normalizeText(stringValue(newState, "location"));
                if (character.isBlank() || newLocation.isBlank()) {
                    continue;
                }
                Optional<Map<String, Object>> previousOpt = getCharacterState(projectId, character);
                if (previousOpt.isEmpty()) {
                    continue;
                }
                Map<String, Object> previous = previousOpt.get();
                String oldLocation = normalizeText(stringValue(previous, "location"));
                if (oldLocation.isBlank() || oldLocation.equals(newLocation)) {
                    continue;
                }
                int previousChapterNum = parseChapterNumber(stringValue(previous, "last_seen"));
                if (currentChapterNum >= 0 && previousChapterNum >= 0 && currentChapterNum - previousChapterNum <= 1) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("type", "character_state_conflict");
                    conflict.put("character", character);
                    conflict.put("previous_location", stringValue(previous, "location"));
                    conflict.put("previous_chapter", stringValue(previous, "last_seen"));
                    conflict.put("new_location", stringValue(newState, "location"));
                    conflict.put("new_chapter", chapter);
                    conflict.put("severity", "medium");
                    conflicts.add(conflict);
                }
            }
        } catch (Exception e) {
            log.warn("detectConflicts 失败: {}", e.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chapter", chapter);
        report.put("conflicts", conflicts);
        report.put("conflict_count", conflicts.size());
        report.put("has_conflicts", !conflicts.isEmpty());
        report.put("generated_at", java.time.Instant.now().toString());
        return report;
    }

    private boolean maybeContradict(String a, String b) {
        String na = normalizeText(a);
        String nb = normalizeText(b);
        if (na.isBlank() || nb.isBlank()) {
            return false;
        }
        if (na.equals(nb)) {
            return false;
        }
        if (containsNegation(na) == containsNegation(nb)) {
            return false;
        }
        int common = 0;
        for (char ch : na.toCharArray()) {
            if (nb.indexOf(ch) >= 0) {
                common += 1;
            }
        }
        return common >= Math.max(6, Math.min(na.length(), nb.length()) / 3);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private Set<String> participantSet(Object raw) {
        Set<String> participants = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = normalizeText(String.valueOf(item == null ? "" : item));
                if (!value.isBlank()) {
                    participants.add(value);
                }
            }
            return participants;
        }
        String text = String.valueOf(raw == null ? "" : raw).trim();
        if (text.isBlank()) {
            return participants;
        }
        for (String part : text.split("[,，/、]")) {
            String value = normalizeText(part);
            if (!value.isBlank()) {
                participants.add(value);
            }
        }
        return participants;
    }

    private int parseChapterNumber(String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:C|c)(\\d{1,4})").matcher(chapter);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        matcher = java.util.regex.Pattern.compile("(\\d{1,4})").matcher(chapter);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> items) {
        return items == null ? List.of() : items;
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        // 提取中文词（2-4字）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\u4e00-\u9fff]{2,4}").matcher(text);
        while (m.find()) keywords.add(m.group());
        // 提取英文词
        m = java.util.regex.Pattern.compile("[a-zA-Z]{3,}").matcher(text);
        while (m.find()) keywords.add(m.group().toLowerCase());
        return keywords;
    }

    private boolean containsNegation(String text) {
        return text.contains("不") || text.contains("没") || text.contains("无") ||
               text.contains("非") || text.contains("未") || text.contains("否");
    }

    private List<Map<String, Object>> readYamlMaps(Path dir) {
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = readYaml(p, Map.class);
                    if (item != null) {
                        items.add(item);
                    }
                } catch (IOException e) {
                    log.error("读取 YAML 数据失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出 YAML 目录失败: {}", dir, e);
        }
        return items;
    }
}
