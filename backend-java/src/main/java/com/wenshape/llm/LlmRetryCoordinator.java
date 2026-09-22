package com.wenshape.llm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Normal chat calls sharing a provider also share its cooldown after transient failures. */
final class LlmRetryCoordinator {
    record Decision(boolean retry, long delayMillis, String reason) { }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private static final class Gate { long nextAttempt = Long.MIN_VALUE; long paceUntil = Long.MIN_VALUE; }
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Sleeper sleeper;

    LlmRetryCoordinator() { this(() -> System.nanoTime() / 1_000_000, Thread::sleep); }
    LlmRetryCoordinator(LongSupplier clock, Sleeper sleeper) { this.clock = clock; this.sleeper = sleeper; }

    static Decision decide(Throwable failure, int attempt) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean network = false, disconnected = false;
        String networkType = "IOException";
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof LlmHttpException http) {
                int status = http.getStatusCode();
                boolean retry = status == 408 || status == 429 || status >= 500;
                long delay = status == 429 ? 10_000L << Math.min(attempt, 2) : 1_000L << Math.min(attempt, 2);
                return new Decision(retry, Math.max(delay, http.getRetryAfterMillis()), "HTTP " + status);
            }
            if (cause instanceof InterruptedException) return new Decision(false, 0, "interrupted");
            network |= cause instanceof IOException;
            if (cause instanceof IOException) networkType = cause.getClass().getSimpleName();
            // HTTP/2 stream resets and connection shutdowns need the same protection as HTTP/1 EOF.
            disconnected |= cause instanceof IOException && !(cause instanceof ConnectException)
                    && !(cause instanceof UnknownHostException) && !(cause instanceof JsonProcessingException);
        }
        // A dropped connection can leave generation running upstream; allow it time to finish.
        if (disconnected) return new Decision(true, 15_000L << Math.min(attempt, 2), "connection interrupted (" + networkType + ")");
        return new Decision(network, 1_000L << Math.min(attempt, 2), network ? "network error" : "non-transient error");
    }

    void defer(String providerId, long delayMillis) {
        Gate gate = gates.computeIfAbsent(providerId, ignored -> new Gate());
        synchronized (gate) {
            gate.nextAttempt = Math.max(gate.nextAttempt, clock.getAsLong() + delayMillis);
            gate.paceUntil = Math.max(gate.paceUntil, gate.nextAttempt + 60_000);
        }
    }

    boolean awaitTurn(String providerId, long maxWaitMillis) throws InterruptedException {
        Gate gate = gates.get(providerId);
        if (gate == null) return true;
        long deadline = clock.getAsLong() + Math.max(0, maxWaitMillis);
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long remaining;
            synchronized (gate) {
                long now = clock.getAsLong();
                remaining = gate.nextAttempt - now;
                if (remaining <= 0) {
                    // Stagger queued retries instead of waking all three modules into another burst.
                    if (now < gate.paceUntil) gate.nextAttempt = now + 1000;
                    return true;
                }
                if (gate.nextAttempt > deadline) return false;
            }
            sleeper.sleep(remaining);
        }
    }

    static long jitter() { return ThreadLocalRandom.current().nextLong(250, 1001); }
}
