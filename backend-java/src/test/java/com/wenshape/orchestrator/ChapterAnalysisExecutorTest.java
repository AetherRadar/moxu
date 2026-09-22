package com.wenshape.orchestrator;

import com.wenshape.agent.ArchivistAgent;
import com.wenshape.model.entity.ChapterSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class ChapterAnalysisExecutorTest {
    @Test
    void independentStagesOverlapWithoutChangingInputsOrOutputs() throws Exception {
        ArchivistAgent agent = mock(ArchivistAgent.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        ChapterSummary summary = ChapterSummary.builder().chapter("V1C1").title("Title").build();
        Map<String, Object> canon = Map.of("facts", List.of(Map.of("statement", "Unchanged fact")));
        when(agent.generateChapterSummary("p", "V1C1", "Title", "Complete draft")).thenAnswer(call -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(3, TimeUnit.SECONDS));
            return summary;
        });
        when(agent.extractCanonUpdates("p", "V1C1", "Complete draft")).thenAnswer(call -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(3, TimeUnit.SECONDS));
            return canon;
        });
        try (var executor = new ChapterAnalysisExecutor(2)) {
            var result = executor.analyze(agent, "p", "V1C1", "Title", "Complete draft");
            assertSame(summary, result.summary());
            assertSame(canon, result.canonUpdates());
            assertEquals(3, result.timings().size());
        }
        verify(agent).generateChapterSummary("p", "V1C1", "Title", "Complete draft");
        verify(agent).extractCanonUpdates("p", "V1C1", "Complete draft");
        verifyNoMoreInteractions(agent);
    }

    @Test
    void concurrencyLimitIsSharedAcrossChaptersAndOneRestoresSerialExecution() throws Exception {
        for (int limit : List.of(1, 2)) {
            var agent = mock(ArchivistAgent.class);
            var running = new AtomicInteger();
            var peak = new AtomicInteger();
            var calls = new AtomicInteger();
            var started = new CountDownLatch(limit);
            var release = new CountDownLatch(1);
            org.mockito.stubbing.Answer<Object> answer = call -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                calls.incrementAndGet();
                started.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return call.getMethod().getName().equals("generateChapterSummary")
                            ? ChapterSummary.builder().chapter(call.getArgument(1)).build() : Map.of();
                } finally { running.decrementAndGet(); }
            };
            when(agent.generateChapterSummary(anyString(), anyString(), anyString(), anyString())).thenAnswer(answer);
            when(agent.extractCanonUpdates(anyString(), anyString(), anyString())).thenAnswer(answer);
            try (var executor = new ChapterAnalysisExecutor(limit);
                 var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = callers.submit(() -> executor.analyze(agent, "p", "V1C1", "Title", "Draft"));
                var second = callers.submit(() -> executor.analyze(agent, "p", "V1C2", "Title", "Draft"));
                try { assertTrue(started.await(3, TimeUnit.SECONDS)); }
                finally { release.countDown(); }
                first.get(3, TimeUnit.SECONDS);
                second.get(3, TimeUnit.SECONDS);
                assertEquals(limit, peak.get());
                assertEquals(4, calls.get());
            }
        }
    }

    @Test
    void failureInEitherStageCancelsItsSiblingAndReleasesSlots() throws Exception {
        var agent = mock(ArchivistAgent.class);
        var summaryStarted = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(agent.generateChapterSummary(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            summaryStarted.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return null;
        });
        when(agent.extractCanonUpdates(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertTrue(summaryStarted.await(3, TimeUnit.SECONDS));
            throw new IllegalStateException("fixture failure");
        });
        try (var executor = new ChapterAnalysisExecutor(2)) {
            assertEquals("fixture failure", assertThrows(IllegalStateException.class,
                    () -> executor.analyze(agent, "p", "V1C1", "Title", "Draft")).getMessage());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            reset(agent);
            when(agent.generateChapterSummary(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(ChapterSummary.builder().chapter("V1C2").build());
            when(agent.extractCanonUpdates(anyString(), anyString(), anyString())).thenReturn(Map.of());
            assertEquals("V1C2", executor.analyze(agent, "p", "V1C2", "Title", "Draft").summary().getChapter());
        }
    }

    @Test
    void reportsControlledLatencyForSerialAndParallelRuns() throws Exception {
        var agent = mock(ArchivistAgent.class);
        when(agent.generateChapterSummary(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            Thread.sleep(200);
            return ChapterSummary.builder().chapter("V1C1").build();
        });
        when(agent.extractCanonUpdates(anyString(), anyString(), anyString())).thenAnswer(call -> {
            Thread.sleep(200);
            return Map.of();
        });
        try (var serial = new ChapterAnalysisExecutor(1); var parallel = new ChapterAnalysisExecutor(2)) {
            var before = serial.analyze(agent, "p", "V1C1", "Title", "Draft");
            var after = parallel.analyze(agent, "p", "V1C1", "Title", "Draft");
            assertEquals(before.summary(), after.summary());
            assertEquals(before.canonUpdates(), after.canonUpdates());
            System.out.printf("Controlled analysis latency (two 200ms stages): serial=%dms parallel=%dms%n",
                    before.timings().get("total"), after.timings().get("total"));
        }
    }
}
