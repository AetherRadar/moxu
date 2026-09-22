package com.moxu.search;

import com.moxu.storage.BaseStorage;
import com.moxu.storage.CardStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * 统一解析某个项目当前生效的检索词典。
 *
 * <p>当前来源：
 * <ul>
 *     <li>角色卡 name / aliases</li>
 *     <li>世界卡 name / aliases</li>
 *     <li>scene_brief.title</li>
 *     <li>chapter_summary.title</li>
 * </ul>
 *
 * <p>带一层轻量缓存，避免每次查询都重新解析全部 YAML。
 */
@Slf4j
@Component
public class ProjectLexiconResolver extends BaseStorage {

    private final CardStorage cardStorage;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProjectLexiconResolver(CardStorage cardStorage) {
        this.cardStorage = cardStorage;
    }

    public ProjectLexicon resolve(String projectId) {
        String normalizedProjectId = projectId == null ? "" : projectId.trim();
        if (normalizedProjectId.isBlank()) {
            return ProjectLexicon.empty();
        }

        long stamp = projectLexiconStamp(normalizedProjectId);
        CacheEntry cached = cache.get(normalizedProjectId);
        if (cached != null && cached.stamp == stamp) {
            return cached.lexicon;
        }

        ProjectLexicon lexicon = buildLexicon(normalizedProjectId);
        cache.put(normalizedProjectId, new CacheEntry(stamp, lexicon));
        return lexicon;
    }

    private ProjectLexicon buildLexicon(String projectId) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addCardLexiconTerms(projectId, terms);
        addSceneTitleLexiconTerms(projectId, terms);
        addSummaryTitleLexiconTerms(projectId, terms);
        return ProjectLexicon.fromTerms(terms);
    }

    private void addCardLexiconTerms(String projectId, LinkedHashSet<String> terms) {
        try {
            for (String name : cardStorage.listCharacterCards(projectId)) {
                cardStorage.getCharacterCard(projectId, name).ifPresent(card -> {
                    addLexiconTerm(terms, card.getName());
                    addLexiconTerms(terms, card.getAliases());
                });
            }
            for (String name : cardStorage.listWorldCards(projectId)) {
                cardStorage.getWorldCard(projectId, name).ifPresent(card -> {
                    addLexiconTerm(terms, card.getName());
                    addLexiconTerms(terms, card.getAliases());
                });
            }
        } catch (Exception e) {
            log.debug("resolve project lexicon card terms failed for {}: {}", projectId, e.getMessage());
        }
    }

    private void addSceneTitleLexiconTerms(String projectId, LinkedHashSet<String> terms) {
        Path draftsDir = safeProjectPath(projectId).resolve("drafts");
        if (!Files.exists(draftsDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(draftsDir, 2)) {
            paths.filter(path -> Files.isRegularFile(path) && "scene_brief.yaml".equals(path.getFileName().toString()))
                    .forEach(path -> addLexiconTerm(terms, readTitle(path)));
        } catch (IOException e) {
            log.debug("resolve scene titles failed for {}: {}", projectId, e.getMessage());
        }
    }

    private void addSummaryTitleLexiconTerms(String projectId, LinkedHashSet<String> terms) {
        Path summariesDir = safeProjectPath(projectId).resolve("summaries");
        if (!Files.exists(summariesDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(summariesDir, 1)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("_summary.yaml"))
                    .forEach(path -> addLexiconTerm(terms, readTitle(path)));
        } catch (IOException e) {
            log.debug("resolve summary titles failed for {}: {}", projectId, e.getMessage());
        }
    }

    private String readTitle(Path path) {
        try {
            Map<String, Object> data = readYamlAsMap(path);
            Object title = data.get("title");
            return title == null ? "" : title.toString();
        } catch (Exception e) {
            log.debug("read lexicon title failed for {}: {}", path, e.getMessage());
            return "";
        }
    }

    private void addLexiconTerms(LinkedHashSet<String> terms, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (String value : values) {
            addLexiconTerm(terms, value);
        }
    }

    private void addLexiconTerm(LinkedHashSet<String> terms, String raw) {
        if (terms == null || raw == null) {
            return;
        }
        String cleaned = raw.strip();
        if (cleaned.length() < 2) {
            return;
        }
        terms.add(cleaned);
    }

    private long projectLexiconStamp(String projectId) {
        Path projectPath = safeProjectPath(projectId);
        long stamp = 0L;
        stamp = Math.max(stamp, maxLastModified(projectPath.resolve("cards/characters"), 8, path -> isYaml(path)));
        stamp = Math.max(stamp, maxLastModified(projectPath.resolve("cards/world"), 8, path -> isYaml(path)));
        stamp = Math.max(stamp, maxLastModified(projectPath.resolve("summaries"), 1,
                path -> Files.isDirectory(path) || path.getFileName().toString().endsWith("_summary.yaml")));
        stamp = Math.max(stamp, maxLastModified(projectPath.resolve("drafts"), 2,
                path -> Files.isDirectory(path) || "scene_brief.yaml".equals(path.getFileName().toString())));
        return stamp;
    }

    private long maxLastModified(Path root, int maxDepth, java.util.function.Predicate<Path> include) {
        if (root == null || !Files.exists(root)) {
            return 0L;
        }
        long max = lastModified(root);
        try (Stream<Path> paths = Files.walk(root, maxDepth)) {
            Optional<Long> nestedMax = paths
                    .filter(path -> !path.equals(root))
                    .filter(include)
                    .map(this::lastModified)
                    .max(Long::compareTo);
            if (nestedMax.isPresent()) {
                max = Math.max(max, nestedMax.get());
            }
        } catch (IOException e) {
            log.debug("scan lexicon stamp failed for {}: {}", root, e.getMessage());
        }
        return max;
    }

    private boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isDirectory(path) || name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Path safeProjectPath(String projectId) {
        return resolveBasePath().resolve(projectId);
    }

    private Path resolveBasePath() {
        String configuredDataDir = (dataDir == null || dataDir.isBlank()) ? "../data" : dataDir;
        List<Path> candidates = List.of(
                Path.of(configuredDataDir).toAbsolutePath().normalize(),
                Path.of("../data").toAbsolutePath().normalize(),
                Path.of("data").toAbsolutePath().normalize()
        );
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private record CacheEntry(long stamp, ProjectLexicon lexicon) {
    }
}
