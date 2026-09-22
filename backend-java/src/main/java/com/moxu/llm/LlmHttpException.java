package com.moxu.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Keeps retry metadata without retaining the provider's possibly sensitive error body. */
public final class LlmHttpException extends RuntimeException {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final int statusCode;
    private final long retryAfterMillis;

    private LlmHttpException(int statusCode, long retryAfterMillis, boolean concurrencyLimited) {
        super("LLM API error " + statusCode + (concurrencyLimited ? " (gateway_concurrency_limit)" : ""));
        this.statusCode = statusCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static LlmHttpException from(int statusCode, String retryAfter, String body) {
        boolean concurrencyLimited = false;
        try {
            var json = MAPPER.readTree(body == null ? "{}" : body);
            concurrencyLimited = json != null && ("gateway_concurrency_limit".equals(json.path("error").path("code").asText())
                    || "gateway_concurrency_limit".equals(json.path("code").asText()));
        } catch (Exception ignored) { }
        return new LlmHttpException(statusCode, parseRetryAfter(retryAfter, Instant.now()), concurrencyLimited);
    }

    static long parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1000));
        } catch (NumberFormatException | ArithmeticException ignored) {
            try {
                var date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(now, date).toMillis());
            } catch (Exception invalid) { return 0; }
        }
    }

    public int getStatusCode() { return statusCode; }
    public long getRetryAfterMillis() { return retryAfterMillis; }
}
