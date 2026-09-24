package com.moxu.analysis;

import com.moxu.storage.BaseStorage;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class AnalysisStorage extends BaseStorage {
    @Override
    protected void atomicWrite(Path path, String content) throws IOException {
        // Windows readers/scanners can briefly deny an atomic replacement.
        for (int attempt = 0; ; attempt++) {
            try {
                super.atomicWrite(path, content);
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(25L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Analysis snapshot write interrupted", interrupted);
                }
            }
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Path root(String project) {
        Path base = getDataPath().toAbsolutePath().normalize();
        Path target = base.resolve(project).normalize();
        if (project.isBlank() || !base.equals(target.getParent())) throw new IllegalArgumentException("Invalid project");
        return target.resolve("analysis");
    }
    public static String chapter(String chapter) {
        if (chapter == null || !chapter.matches("(?i)V\\d+C\\d+")) throw new IllegalArgumentException("Invalid chapter");
        var matcher = java.util.regex.Pattern.compile("(?i)V(\\d+)C(\\d+)").matcher(chapter);
        matcher.matches();
        return "V" + Integer.parseInt(matcher.group(1)) + "C" + Integer.parseInt(matcher.group(2));
    }
    private String jobId(String id) { return UUID.fromString(id).toString(); }
    public synchronized Map<String, Object> causalReport(String project, String target) throws IOException {
        return read(root(project).resolve("causal/reports/" + chapter(target) + ".json"));
    }
    public synchronized void causalReport(String project, String target, Map<String, Object> value) throws IOException {
        writeJson(root(project).resolve("causal/reports/" + chapter(target) + ".json"), value);
    }
    public synchronized Map<String, Object> causalCache(String project, String kind, String hash) throws IOException {
        return read(causalCachePath(project, kind, hash));
    }
    public synchronized void causalCache(String project, String kind, String hash, Map<String, Object> value) throws IOException {
        writeJson(causalCachePath(project, kind, hash), value);
    }
    private Path causalCachePath(String project, String kind, String hash) {
        if (!Set.of("extraction", "verification", "graph", "draft_review").contains(kind) || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid causal artifact");
        return root(project).resolve("causal/" + kind + "/" + hash + ".json");
    }
    public synchronized Map<String, Object> applied(String project, String chapter) throws IOException {
        return read(root(project).resolve("applied/" + chapter(chapter) + ".json"));
    }
    public synchronized void applied(String project, String chapter, Map<String, Object> value) throws IOException {
        writeJson(root(project).resolve("applied/" + chapter(chapter) + ".json"), value);
    }
    public synchronized List<String> appliedChapters(String project) throws IOException {
        Path directory = root(project).resolve("applied");
        if (!Files.exists(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).filter(name -> name.matches("V\\d+C\\d+\\.json"))
                    .map(name -> name.substring(0, name.length() - 5)).toList();
        }
    }
    public synchronized Map<String, Object> continuity(String project, String chapter) throws IOException {
        return read(root(project).resolve("continuity/" + chapter(chapter) + ".json"));
    }
    public synchronized void continuity(String project, String chapter, Map<String, Object> value) throws IOException {
        writeJson(root(project).resolve("continuity/" + chapter(chapter) + ".json"), value);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Path path) throws IOException {
        return Files.exists(path) ? readJson(path, Map.class) : new LinkedHashMap<>();
    }
    public synchronized Map<String, Object> entry(String project, String chapter) throws IOException {
        Object entry = read(root(project).resolve("manifest.json")).get(chapter(chapter));
        return entry instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }
    public synchronized void entry(String project, String chapter, Map<String, Object> entry) throws IOException {
        Path path = root(project).resolve("manifest.json");
        Map<String, Object> manifest = read(path);
        manifest.put(chapter(chapter), entry);
        writeJson(path, manifest);
    }
    public synchronized Map<String, Object> result(String project, String chapter) throws IOException {
        return read(root(project).resolve("results/" + chapter(chapter) + ".json"));
    }
    public synchronized void result(String project, String chapter, Map<String, Object> result) throws IOException {
        writeJson(root(project).resolve("results/" + chapter(chapter) + ".json"), result);
    }
    public synchronized Map<String, Object> job(String project, String id) throws IOException {
        return read(root(project).resolve("jobs/" + jobId(id) + ".json"));
    }
    public synchronized void job(String project, String id, Map<String, Object> job) throws IOException {
        writeJson(root(project).resolve("jobs/" + jobId(id) + ".json"), job);
    }
    public synchronized void bookSummary(String project, Map<String, Object> value) throws IOException {
        writeJson(root(project).resolve("book_summary.json"), value);
    }
    public synchronized Map<String, Object> bookSummary(String project) throws IOException {
        return read(root(project).resolve("book_summary.json"));
    }
    public synchronized void event(String project, Map<String, Object> event) {
        try {
            Path path = root(project).resolve("cost_ledger.ndjson");
            ensureDir(path.getParent());
            Files.writeString(path, jsonMapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public synchronized long spent(String project, String jobId) throws IOException {
        Path path = root(project).resolve("cost_ledger.ndjson");
        if (!Files.exists(path)) return 0;
        long total = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Map<?, ?> event = jsonMapper.readValue(line, Map.class);
            if (jobId.equals(event.get("job_id")) && event.get("budget_tokens") instanceof Number tokens) total += tokens.longValue();
        }
        return total;
    }
    public synchronized Map<String, Object> usage(String project, String jobId) throws IOException {
        Path path = root(project).resolve("cost_ledger.ndjson");
        Map<String, Map<String, Long>> stages = new LinkedHashMap<>(), chapters = new LinkedHashMap<>();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank()) continue;
                    Map<?, ?> event = jsonMapper.readValue(line, Map.class);
                    if (!jobId.equals(event.get("job_id"))) continue;
                    for (var grouping : List.of(Map.entry(stages, "stage"), Map.entry(chapters, "chapter"))) {
                        var counters = grouping.getKey().computeIfAbsent(Objects.toString(event.get(grouping.getValue()), "unknown"),
                                key -> new LinkedHashMap<>());
                        counters.merge("budget_tokens", number(event.get("budget_tokens")), Long::sum);
                        counters.merge("reported_tokens", number(event.get("reported_tokens")), Long::sum);
                        counters.merge("latency_ms", number(event.get("latency_ms")), Long::sum);
                        if ("started".equals(event.get("status"))) counters.merge("calls", 1L, Long::sum);
                        if ("failed".equals(event.get("status"))) counters.merge("failed_calls", 1L, Long::sum);
                        if (Boolean.FALSE.equals(event.get("usage_known"))) counters.merge("unknown_usage_calls", 1L, Long::sum);
                    }
                }
            }
        }
        return Map.of("stages", stages, "chapters", chapters);
    }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    public String fingerprint(Object data) {
        try { return hash(jsonMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(data)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public synchronized Map<String, Object> reduction(String project, String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid hash");
        return read(root(project).resolve("reductions/" + hash + ".json"));
    }
    public synchronized void reduction(String project, String hash, Map<String, Object> value) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid hash");
        writeJson(root(project).resolve("reductions/" + hash + ".json"), value);
    }
    public String cardsFingerprint(String project) throws IOException {
        Path directory = root(project).getParent().resolve("cards");
        if (!Files.exists(directory)) return hash("");
        StringBuilder content = new StringBuilder();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                content.append(directory.relativize(file)).append(':')
                        .append(hash(Files.readString(file, StandardCharsets.UTF_8))).append('\n');
            }
        }
        return hash(content.toString());
    }
}
