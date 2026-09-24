package com.moxu.orchestrator;

import com.moxu.agent.ArchivistAgent;
import com.moxu.model.entity.*;
import com.moxu.quality.*;
import com.moxu.storage.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Persisted, resumable archive of a saved final draft. Never invokes writer research. */
@lombok.extern.slf4j.Slf4j
public final class ChapterArchive {
    private final ArchivistAgent archivist;
    private final DraftStorage drafts;
    private final CanonStorage canon;
    private final MemoryPackStorage memory;

    public ChapterArchive(ArchivistAgent archivist, DraftStorage drafts, CanonStorage canon, MemoryPackStorage memory) {
        this.archivist = archivist; this.drafts = drafts; this.canon = canon; this.memory = memory;
    }

    public Map<String, Object> run(String project, String chapter, String content, String title,
                                   Consumer<String> progress) throws Exception {
        String fingerprint = hash(content);
        String titleHash = hash(title == null ? "" : title);
        Map<String, Object> previous = drafts.getArchiveStatus(project, chapter);
        Map<String, Object> status = fingerprint.equals(previous.get("content_hash"))
                ? new LinkedHashMap<>(previous) : new LinkedHashMap<>();
        Map<String, Object> stages = new LinkedHashMap<>();
        if (status.get("stages") instanceof Map<?, ?> saved) saved.forEach((key, value) -> stages.put(key.toString(), value));
        if (!titleHash.equals(status.get("title_hash"))) {
            stages.remove("summary");
            stages.remove("volume");
        }
        status.put("chapter", chapter);
        status.put("content_hash", fingerprint);
        status.put("title_hash", titleHash);
        status.put("status", "running");
        status.put("stages", stages);
        persist(project, chapter, status);
        String volume = chapter.replaceFirst("C\\d+$", "");
        stage(project, chapter, status, stages, "summary", "正在归档：章节摘要", progress, () -> {
            ChapterSummary summary = archivist.generateChapterSummary(project, chapter, title, content);
            if (summary == null || "fallback".equals(summary.getGenerationStatus())) {
                throw new IllegalStateException("摘要生成失败，原有摘要已保留");
            }
            summary.setNewFacts(complete(stages, "canon")
                    ? drafts.getChapterSummary(project, chapter).map(ChapterSummary::getNewFacts).orElse(List.of())
                    : List.of());
            summary.setChapter(chapter); summary.setTitle(title); summary.setVolumeId(volume); summary.setWordCount(content.length());
            drafts.saveChapterSummary(project, summary);
            memory.invalidateFrom(project, chapter);
        });
        stage(project, chapter, status, stages, "canon", "正在归档：事实与人物状态", progress, () -> {
            Map<String, Object> updates = CanonEvidenceGuard.apply(archivist.extractCanonUpdates(project, chapter, content), content);
            status.put("pending_facts", updates.getOrDefault("pending_facts", List.of()));
            if (complete(stages, "summary")) {
                ChapterSummary summary = drafts.getChapterSummary(project, chapter).orElseThrow();
                summary.setNewFacts(CanonEvidenceGuard.maps(updates.get("facts")).stream()
                        .map(item -> Objects.toString(item.get("statement"), "")).toList());
                drafts.saveChapterSummary(project, summary);
            }
            // Check against previous state before writing this chapter's state.
            Map<String, Object> report = canon.detectConflicts(project, chapter, CanonEvidenceGuard.maps(updates.get("facts")),
                    CanonEvidenceGuard.maps(updates.get("timeline_events")), CanonEvidenceGuard.maps(updates.get("character_states")));
            if ("failed".equals(report.get("status"))) throw new IllegalStateException("事实冲突检查未完成");
            status.put("canon_conflicts", report.getOrDefault("conflicts", List.of()));
            for (Map<String, Object> item : CanonEvidenceGuard.maps(updates.get("facts"))) {
                String statement = Objects.toString(item.get("statement"), "");
                String id = canon.listFacts(project).stream()
                        .filter(f -> chapter.equals(f.getIntroducedIn()) && statement.equals(f.getStatement()))
                        .map(Fact::getId).findFirst().orElse("A" + hash(chapter + statement).substring(0, 20));
                canon.saveFact(project, Fact.builder().id(id).statement(statement).content(statement)
                        .source(chapter).introducedIn(chapter).evidence(Objects.toString(item.get("evidence"), ""))
                        .certainty("explicit").origin("chapter_archive").build());
            }
            for (Map<String, Object> item : CanonEvidenceGuard.maps(updates.get("timeline_events"))) {
                item.put("source", chapter);
                item.put("id", "A" + hash(chapter + new TreeMap<>(item)).substring(0, 20));
                canon.saveTimelineEvent(project, item);
            }
            for (Map<String, Object> item : CanonEvidenceGuard.maps(updates.get("character_states"))) {
                item.put("last_seen", chapter); canon.saveCharacterState(project, item);
            }
        });
        stage(project, chapter, status, stages, "volume", "正在归档：分卷摘要", progress, () -> {
            if (!complete(stages, "summary")) throw new IllegalStateException("等待章节摘要成功后重试");
            Map<String, Object> summary = archivist.generateVolumeSummary(project, volume, drafts.listChapterSummaries(project, volume));
            if ("fallback".equals(summary.get("generation_status"))) throw new IllegalStateException("分卷摘要生成失败，原有摘要已保留");
            drafts.saveVolumeSummary(project, summary);
        });
        // Cheap checks always run, even when the relay is unavailable.
        stages.remove("checks");
        stage(project, chapter, status, stages, "checks", "正在核对正文数字", progress, () -> {
            List<Map<String, Object>> issues = new ArrayList<>(CanonEvidenceGuard.maps(status.get("canon_conflicts")));
            issues.addAll(ProseChecks.check(content));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("chapter", chapter); report.put("conflicts", issues); report.put("conflict_count", issues.size());
            report.put("has_conflicts", issues.isEmpty() && !complete(stages, "canon") ? null : !issues.isEmpty());
            report.put("status", complete(stages, "canon") ? "complete" : "partial");
            report.put("scope", "仅事实启发式与连续页码数量检查，不代表完整小说一致性审查");
            drafts.saveConflictReport(project, chapter, report);
            status.put("review", report);
        });
        stages.remove("memory");
        stage(project, chapter, status, stages, "memory", "正在保存归档记录", progress, () -> {
            Map<String, Object> existing = memory.readPack(project, chapter);
            Map<String, Object> pack = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
            pack.put("project_id", project); pack.put("chapter", chapter);
            pack.put("context_stale", true);
            pack.put("archive_snapshot", Map.of("content_hash", fingerprint, "word_count", content.length(),
                    "stages", new LinkedHashMap<>(stages), "built_at", java.time.Instant.now().toString()));
            // Existing payload records the actual writing context; no new research or stale regeneration.
            memory.writePack(project, chapter, pack);
        });
        status.put("status", stages.values().stream().allMatch(value -> value instanceof Map<?, ?> m && "complete".equals(m.get("status")))
                ? "complete" : "partial");
        persist(project, chapter, status);
        return status;
    }

    private void stage(String project, String chapter, Map<String, Object> status, Map<String, Object> stages,
                       String name, String message, Consumer<String> progress, CheckedAction action) throws Exception {
        if (complete(stages, name)) return;
        stages.put(name, Map.of("status", "running"));
        persist(project, chapter, status);
        progress.accept(message);
        try {
            action.run(); stages.put(name, Map.of("status", "complete"));
        } catch (Exception e) {
            log.warn("Chapter archive stage failed: project={}, chapter={}, stage={}, cause={}", project, chapter, name, e.getClass().getSimpleName());
            stages.put(name, Map.of("status", "failed", "message", safeMessage(name)));
        }
        persist(project, chapter, status);
    }

    private String safeMessage(String stage) {
        return switch (stage) {
            case "summary" -> "章节摘要未完成，原有摘要保留，可重试";
            case "canon" -> "事实或人物状态归档未完成，可重试；不能据此判断无冲突";
            case "volume" -> "分卷摘要未完成，请在章节摘要成功后重试";
            case "checks" -> "正文检查未完成，可重试";
            default -> "归档记录保存未完成，可重试";
        };
    }

    private void persist(String project, String chapter, Map<String, Object> status) throws Exception {
        status.put("updated_at", java.time.Instant.now().toString());
        drafts.saveArchiveStatus(project, chapter, status);
    }
    private boolean complete(Map<String, Object> stages, String name) {
        return stages.get(name) instanceof Map<?, ?> state && "complete".equals(state.get("status"));
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
