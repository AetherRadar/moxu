package com.moxu.storage;

import com.moxu.model.entity.CharacterCard;
import com.moxu.model.entity.StyleCard;
import com.moxu.model.entity.WorldCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 卡片存储 - 角色卡、世界观卡、文风卡
 */
@Slf4j
@Component
public class CardStorage extends BaseStorage {
    
    // ========== 角色卡 ==========
    
    public Optional<CharacterCard> getCharacterCard(String projectId, String characterName) {
        Path cardsDir = characterCardsDir(projectId);
        Optional<Path> filePath = findCardFileByName(cardsDir, characterName);
        if (filePath.isEmpty()) {
            return Optional.empty();
        }

        return readCharacterCard(filePath.get());
    }
    
    public void saveCharacterCard(String projectId, CharacterCard card) throws IOException {
        Path cardsDir = characterCardsDir(projectId);
        ensureDir(cardsDir);
        Path target = cardsDir.resolve(safeCardFileStem(card.getName()) + ".yaml");
        Optional<Path> existing = findCardFileByName(cardsDir, card.getName());

        writeYaml(target, card);
        cleanupLegacyDuplicate(cardsDir, target, existing);
    }
    
    public List<String> listCharacterCards(String projectId) {
        return listCardNames(characterCardsDir(projectId));
    }
    
    public boolean deleteCharacterCard(String projectId, String characterName) {
        Path cardsDir = characterCardsDir(projectId);
        boolean deleted = false;
        for (Path filePath : findCardFilesByName(cardsDir, characterName)) {
            try {
                deleted |= Files.deleteIfExists(filePath);
                cleanupEmptyParents(filePath, cardsDir);
            } catch (IOException e) {
                log.error("删除角色卡失败: {}", filePath, e);
            }
        }
        return deleted;
    }
    
    // ========== 世界观卡 ==========
    
    public Optional<WorldCard> getWorldCard(String projectId, String cardName) {
        Path cardsDir = worldCardsDir(projectId);
        Optional<Path> filePath = findCardFileByName(cardsDir, cardName);
        if (filePath.isEmpty()) {
            return Optional.empty();
        }

        return readWorldCard(filePath.get());
    }
    
    public void saveWorldCard(String projectId, WorldCard card) throws IOException {
        Path cardsDir = worldCardsDir(projectId);
        ensureDir(cardsDir);
        Path target = cardsDir.resolve(safeCardFileStem(card.getName()) + ".yaml");
        Optional<Path> existing = findCardFileByName(cardsDir, card.getName());

        writeYaml(target, card);
        cleanupLegacyDuplicate(cardsDir, target, existing);
    }
    
    public List<String> listWorldCards(String projectId) {
        return listCardNames(worldCardsDir(projectId));
    }
    
    public boolean deleteWorldCard(String projectId, String cardName) {
        Path cardsDir = worldCardsDir(projectId);
        boolean deleted = false;
        for (Path filePath : findCardFilesByName(cardsDir, cardName)) {
            try {
                deleted |= Files.deleteIfExists(filePath);
                cleanupEmptyParents(filePath, cardsDir);
            } catch (IOException e) {
                log.error("删除世界观卡失败: {}", filePath, e);
            }
        }
        return deleted;
    }
    
    // ========== 文风卡 ==========
    
    public Optional<StyleCard> getStyleCard(String projectId) {
        Path filePath = getProjectPath(projectId).resolve("cards/style.yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceStyleData(data));
        } catch (IOException e) {
            log.error("读取文风卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public void saveStyleCard(String projectId, StyleCard card) throws IOException {
        Path filePath = getProjectPath(projectId).resolve("cards/style.yaml");
        writeYaml(filePath, card);
    }
    
    // ========== 数据转换 ==========
    
    @SuppressWarnings("unchecked")
    private CharacterCard coerceCharacterData(Map<String, Object> data) {
        String name = getString(data, "name");
        List<String> aliases = normalizeAliases(data.get("aliases"));
        Integer stars = normalizeStars(data.get("stars"));
        String description = getString(data, "description");
        
        if (description == null || description.isBlank()) {
            // 从其他字段组装 description
            StringBuilder sb = new StringBuilder();
            appendIfPresent(sb, "身份", getString(data, "identity"));
            appendIfPresent(sb, "外貌", getString(data, "appearance"));
            appendIfPresent(sb, "动机", getString(data, "motivation"));
            
            Object personality = data.get("personality");
            if (personality instanceof List<?> list && !list.isEmpty()) {
                sb.append("性格: ").append(String.join(", ", list.stream().map(Object::toString).toList())).append("\n");
            }
            
            appendIfPresent(sb, "口吻", getString(data, "speech_pattern"));
            appendIfPresent(sb, "角色弧线", getString(data, "arc"));
            
            description = sb.toString().trim();
        }
        
        return CharacterCard.builder()
                .name(name)
                .aliases(aliases)
                .description(description)
                .stars(stars)
                .build();
    }
    
    @SuppressWarnings("unchecked")
    private WorldCard coerceWorldData(Map<String, Object> data) {
        String name = getString(data, "name");
        String description = getString(data, "description");
        List<String> aliases = normalizeAliases(data.get("aliases"));
        Integer stars = normalizeStars(data.get("stars"));
        String category = getString(data, "category");
        
        List<String> rules = new ArrayList<>();
        Object rulesObj = data.get("rules");
        if (rulesObj instanceof List<?> list) {
            rules = list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        } else if (rulesObj instanceof String str) {
            rules = List.of(str.split("[,\n，;；]+"));
        }
        
        Boolean immutable = data.get("immutable") instanceof Boolean b ? b : null;
        
        return WorldCard.builder()
                .name(name)
                .description(description)
                .aliases(aliases)
                .category(category)
                .rules(new ArrayList<>(rules))
                .immutable(immutable)
                .stars(stars)
                .build();
    }
    
    private StyleCard coerceStyleData(Map<String, Object> data) {
        String style = getString(data, "style");
        if (style == null || style.isBlank()) {
            style = getString(data, "content");
        }
        return StyleCard.builder()
                .style(style)
                .representativeExcerpts(normalizeAliases(data.get("representative_excerpts")))
                .sourceChapters(normalizeAliases(data.get("source_chapters")))
                .build();
    }
    
    private String getString(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? val.toString().trim() : "";
    }
    
    @SuppressWarnings("unchecked")
    private List<String> normalizeAliases(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof String str) {
            for (String part : str.split("[,，;；\n]+")) {
                if (!part.isBlank()) result.add(part.trim());
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                String s = item.toString().trim();
                if (!s.isBlank()) result.add(s);
            }
        }
        return result;
    }
    
    private Integer normalizeStars(Object value) {
        try {
            int stars = Integer.parseInt(value.toString());
            return Math.max(1, Math.min(stars, 3));
        } catch (Exception e) {
            return 1;
        }
    }
    
    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private Path characterCardsDir(String projectId) {
        return getProjectPath(projectId).resolve("cards/characters");
    }

    private Path worldCardsDir(String projectId) {
        return getProjectPath(projectId).resolve("cards/world");
    }

    private Optional<CharacterCard> readCharacterCard(Path filePath) {
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceCharacterData(data));
        } catch (IOException e) {
            log.error("读取角色卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    private Optional<WorldCard> readWorldCard(Path filePath) {
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceWorldData(data));
        } catch (IOException e) {
            log.error("读取世界观卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    private List<String> listCardNames(Path cardsDir) {
        if (!Files.exists(cardsDir)) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Path filePath : listCardFiles(cardsDir)) {
            String name = readDeclaredCardName(filePath).orElseGet(() -> fallbackCardName(filePath));
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names.stream()
                .sorted()
                .toList();
    }

    private List<Path> listCardFiles(Path cardsDir) {
        if (!Files.exists(cardsDir)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.walk(cardsDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            log.error("列出卡片失败: {}", cardsDir, e);
            return List.of();
        }
    }

    private Optional<Path> findCardFileByName(Path cardsDir, String cardName) {
        if (cardName == null || cardName.isBlank() || !Files.exists(cardsDir)) {
            return Optional.empty();
        }

        Path directPath = cardsDir.resolve(safeCardFileStem(cardName) + ".yaml");
        if (Files.exists(directPath)) {
            Optional<String> declaredName = readDeclaredCardName(directPath);
            if (declaredName.isEmpty() || cardName.trim().equals(declaredName.get().trim())) {
                return Optional.of(directPath);
            }
        }

        for (Path filePath : listCardFiles(cardsDir)) {
            Optional<String> declaredName = readDeclaredCardName(filePath);
            if (declaredName.isPresent() && cardName.trim().equals(declaredName.get().trim())) {
                return Optional.of(filePath);
            }
        }
        return Optional.empty();
    }

    private List<Path> findCardFilesByName(Path cardsDir, String cardName) {
        if (cardName == null || cardName.isBlank() || !Files.exists(cardsDir)) {
            return List.of();
        }

        List<Path> matches = new ArrayList<>();
        for (Path filePath : listCardFiles(cardsDir)) {
            Optional<String> declaredName = readDeclaredCardName(filePath);
            if (declaredName.isPresent() && cardName.trim().equals(declaredName.get().trim())) {
                matches.add(filePath);
            }
        }
        Path directPath = cardsDir.resolve(safeCardFileStem(cardName) + ".yaml");
        if (Files.exists(directPath) && !matches.contains(directPath)) {
            matches.add(directPath);
        }
        return matches;
    }

    private Optional<String> readDeclaredCardName(Path filePath) {
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            String name = getString(data, "name");
            return name == null || name.isBlank() ? Optional.empty() : Optional.of(name.trim());
        } catch (Exception e) {
            log.debug("读取卡片名称失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    private String fallbackCardName(Path filePath) {
        String filename = filePath.getFileName().toString();
        return filename.endsWith(".yaml")
                ? filename.substring(0, filename.length() - 5)
                : filename;
    }

    private String safeCardFileStem(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isBlank()) {
            return "unnamed_card";
        }

        String sanitized = name
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("\\.+$", "");
        if (sanitized.isBlank()) {
            sanitized = "unnamed_card";
        }

        if (sanitized.equals(name)) {
            return sanitized;
        }
        return sanitized + "__" + shortHash(name);
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(4, bytes.length); i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((raw == null ? "" : raw).hashCode());
        }
    }

    private void cleanupLegacyDuplicate(Path cardsDir, Path target, Optional<Path> existing) throws IOException {
        if (existing.isEmpty()) {
            return;
        }
        Path legacy = existing.get();
        if (legacy.equals(target)) {
            return;
        }
        Files.deleteIfExists(legacy);
        cleanupEmptyParents(legacy, cardsDir);
    }

    private void cleanupEmptyParents(Path filePath, Path stopDir) {
        Path parent = filePath == null ? null : filePath.getParent();
        while (parent != null && stopDir != null && !parent.equals(stopDir) && parent.startsWith(stopDir)) {
            try {
                Files.deleteIfExists(parent);
            } catch (IOException ignored) {
                break;
            }
            parent = parent.getParent();
        }
    }
}
