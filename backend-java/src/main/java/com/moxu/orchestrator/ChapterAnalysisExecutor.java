package com.moxu.orchestrator;

import com.moxu.agent.ArchivistAgent;
import com.moxu.model.entity.ChapterSummary;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Overlaps independent, read-only analysis stages; chapter writes remain ordered.
 */
@Component
public class ChapterAnalysisExecutor implements AutoCloseable {
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("moxu-analysis-", 0).factory());
    private final Semaphore slots;

    public ChapterAnalysisExecutor(
            @Value("${moxu.generation.analysis-concurrency:${MOXU_ANALYSIS_CONCURRENCY:2}}") int concurrency) {
        slots = new Semaphore(Math.max(1, Math.min(4, concurrency)), true);
    }

    public Result analyze(ArchivistAgent agent, String projectId, String chapter,
                          String title, String content) {
        long started = System.nanoTime();
        ExecutorCompletionService<Stage> completion = new ExecutorCompletionService<>(executor);
        List<Future<Stage>> tasks = new ArrayList<>(2);
        try {
            tasks.add(completion.submit(() -> limited("summary", () -> new Stage(
                    agent.generateChapterSummary(projectId, chapter, title, content), null, "", 0))));
            tasks.add(completion.submit(() -> limited("canon", () -> new Stage(
                    null, agent.extractCanonUpdates(projectId, chapter, content), "", 0))));
            ChapterSummary summary = null;
            Map<String, Object> canon = null;
            Map<String, Long> timings = new LinkedHashMap<>();
            for (int i = 0; i < tasks.size(); i++) {
                Stage result = completion.take().get();
                timings.put(result.name(), result.elapsedMs());
                if ("summary".equals(result.name())) summary = result.summary();
                else canon = result.canon();
            }
            timings.put("total", elapsed(started));
            return new Result(summary, canon, Map.copyOf(timings));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chapter analysis interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Chapter analysis failed", e.getCause());
        } finally {
            // Completion order detects either failure promptly and cancels its sibling.
            tasks.forEach(task -> task.cancel(true));
        }
    }

    private Stage limited(String name, Callable<Stage> task) throws Exception {
        slots.acquire();
        try {
            long started = System.nanoTime();
            Stage result = task.call();
            return new Stage(result.summary(), result.canon(), name, elapsed(started));
        } finally {
            slots.release();
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    public record Result(ChapterSummary summary, Map<String, Object> canonUpdates,
                         Map<String, Long> timings) {}

    private record Stage(ChapterSummary summary, Map<String, Object> canon,
                         String name, long elapsedMs) {}
}
