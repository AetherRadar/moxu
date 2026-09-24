package com.moxu.analysis;

import com.moxu.llm.LlmResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A request-local scope, explicitly propagated to analysis worker threads. */
public final class AnalysisContext implements AutoCloseable {
    private static final ThreadLocal<AnalysisContext> CURRENT = new ThreadLocal<>();
    private final AnalysisContext previous;
    private final Account account;
    private final String chapter;
    private final String stage;
    private final AnalysisProperties options;

    public AnalysisContext(Account account, String chapter, String stage, AnalysisProperties options) {
        this.previous = CURRENT.get();
        this.account = account;
        this.chapter = chapter;
        this.stage = stage;
        this.options = options;
        CURRENT.set(this);
    }

    public static AnalysisContext current() { return CURRENT.get(); }
    public static AnalysisProperties options() {
        return current() == null ? null : current().options;
    }
    public static boolean bulk() { return options() != null && options().isBulkMode(); }
    public void awaitCalls() { account.awaitIdle(); }
    public static <T> T stage(String name, Supplier<T> action) {
        return stage(current(), name, action);
    }
    public static <T> T stage(AnalysisContext parent, String name, Supplier<T> action) {
        if (parent == null) return action.get();
        try (var ignored = new AnalysisContext(parent.account, parent.chapter, name, parent.options)) {
            return action.get();
        }
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    public static Ticket beforeCall(List<Map<String, String>> messages, Integer outputLimit) {
        AnalysisContext scope = current();
        if (scope == null) return null;
        // UTF-8 byte count is a deliberately conservative admission estimate, not measured usage.
        long input = 32;
        for (var message : messages) input += 16 + message.getOrDefault("content", "").getBytes(StandardCharsets.UTF_8).length;
        long reserved = input + (outputLimit == null ? scope.options.getMaxOutputTokens() : outputLimit);
        scope.account.reserve(scope.chapter, reserved);
        String attempt = UUID.randomUUID().toString();
        try {
            scope.account.sink.accept(Map.of("job_id", scope.account.jobId, "attempt_id", attempt,
                    "chapter", scope.chapter, "stage", scope.stage, "status", "started",
                    "at", Instant.now().toString(), "budget_tokens", reserved));
        } catch (RuntimeException e) {
            scope.account.release(reserved);
            throw e;
        }
        return new Ticket(scope, attempt, input, reserved, System.nanoTime());
    }

    public record Ticket(AnalysisContext scope, String attemptId, long inputEstimate, long reserved, long started) {
        public void finish(String provider, LlmResponse response, boolean failed) {
            boolean known = response != null && (response.getTotalTokens() > 0
                    || response.getPromptTokens() > 0 || response.getCompletionTokens() > 0);
            long reported = response == null ? 0 : Math.max(response.getTotalTokens(),
                    response.getPromptTokens() + response.getCompletionTokens());
            long charged = known ? reported : reserved;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("at", Instant.now().toString());
            event.put("job_id", scope.account.jobId);
            event.put("attempt_id", attemptId);
            event.put("chapter", scope.chapter);
            event.put("stage", scope.stage);
            event.put("provider_id", provider == null ? "" : provider);
            event.put("status", failed ? "failed" : "complete");
            event.put("usage_known", known);
            event.put("reported_tokens", reported);
            event.put("budget_tokens", charged - reserved);
            event.put("accounted_tokens", charged);
            event.put("prompt_tokens", response == null ? 0 : response.getPromptTokens());
            event.put("completion_tokens", response == null ? 0 : response.getCompletionTokens());
            event.put("input_token_upper_estimate", inputEstimate);
            event.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
            scope.account.finish(reserved, charged, reported, known, event);
        }
    }

    public static final class Paused extends RuntimeException {
        public Paused(String reason) { super(reason); }
    }

    public static final class Account {
        private final String jobId;
        private final long limit;
        private final long deadline;
        private final int callLimit;
        private final Consumer<Map<String, Object>> sink;
        private final Map<String, Integer> chapterCalls = new HashMap<>();
        private long spent;
        private long reserved;
        private long reported;
        private int calls;
        private int unknownCalls;
        private volatile boolean cancelled;
        private volatile String stopReason = "";

        public Account(String jobId, long limit, int minutes, int callLimit, long spent,
                       Consumer<Map<String, Object>> sink) {
            this(jobId, limit, minutes, callLimit, spent, 0, sink);
        }
        public Account(String jobId, long limit, int minutes, int callLimit, long spent,
                       long elapsedMillis, Consumer<Map<String, Object>> sink) {
            this.jobId = jobId;
            this.limit = limit;
            this.deadline = minutes == 0 ? Long.MAX_VALUE
                    : System.nanoTime() + Math.max(0, minutes * 60_000L - elapsedMillis) * 1_000_000L;
            this.callLimit = callLimit;
            this.spent = spent;
            this.sink = sink;
        }
        public void cancel() { cancelled = true; }
        public String stopReason() { return stopReason; }
        private Paused pause(String reason) { stopReason = reason; return new Paused(reason); }
        public void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw pause("cancelled");
            if (System.nanoTime() >= deadline) throw pause("time_budget");
        }
        private synchronized void reserve(String chapter, long tokens) {
            check();
            if (limit > 0 && spent + reserved + tokens > limit) throw pause("token_budget");
            if (chapterCalls.getOrDefault(chapter, 0) >= callLimit) throw pause("chapter_call_budget");
            chapterCalls.merge(chapter, 1, Integer::sum);
            reserved += tokens;
            calls++;
        }
        private synchronized void finish(long reservation, long charged, long measured, boolean known,
                                         Map<String, Object> event) {
            reserved -= reservation;
            spent += charged;
            reported += measured;
            if (!known) unknownCalls++;
            notifyAll();
            sink.accept(event);
        }
        private synchronized void release(long reservation) {
            reserved -= reservation;
            notifyAll();
        }
        private synchronized void awaitIdle() {
            boolean interrupted = false;
            while (reserved > 0) {
                try { wait(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        public synchronized Map<String, Object> metrics() {
            return Map.of("budget_tokens", spent + reserved, "reported_tokens_this_run", reported,
                    "calls_this_run", calls, "unknown_usage_calls_this_run", unknownCalls);
        }
    }
}
