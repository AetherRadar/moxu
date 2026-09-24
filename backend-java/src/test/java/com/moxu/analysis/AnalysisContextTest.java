package com.moxu.analysis;

import com.moxu.llm.*;
import com.moxu.storage.ProjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisContextTest {
    private final List<Map<String, Object>> events = new ArrayList<>();
    private AnalysisContext.Account account(long tokens, int calls) {
        return new AnalysisContext.Account("job", tokens, 30, calls, 0, events::add);
    }
    @Test void missingUsageIsConservativelyChargedAndNotReportedAsMeasuredZero() {
        var account = account(0, 3);
        try (var scope = new AnalysisContext(account, "V1C1", "canon", new AnalysisProperties())) {
            var ticket = AnalysisContext.beforeCall(List.of(Map.of("content", "private prose")), 100);
            assertEquals("started", events.getFirst().get("status"));
            ticket.finish("fixture", LlmResponse.builder().content("private output").build(), false);
        }
        assertEquals(1, account.metrics().get("unknown_usage_calls_this_run"));
        assertTrue((long) account.metrics().get("budget_tokens") >= 100);
        assertFalse(events.toString().contains("private prose"));
        assertFalse(events.toString().contains("private output"));
        assertNull(AnalysisContext.current());
    }
    @Test void accountingCountsFailuresAndEnforcesCallsBeforeNetwork() {
        var account = account(0, 1);
        try (var scope = new AnalysisContext(account, "V1C1", "summary", new AnalysisProperties())) {
            var ticket = AnalysisContext.beforeCall(List.of(Map.of("content", "source")), 100);
            ticket.finish("fixture", null, true);
            assertThrows(AnalysisContext.Paused.class,
                    () -> AnalysisContext.beforeCall(List.of(Map.of("content", "source")), 100));
        }
        assertEquals(1, account.metrics().get("calls_this_run"));
        assertEquals("chapter_call_budget", account.stopReason());
    }
    @Test void cancelPreventsFurtherCallsAndScopeRestoresAfterExceptions() {
        var account = account(0, 5);
        try (var scope = new AnalysisContext(account, "V1C1", "summary", new AnalysisProperties())) {
            account.cancel();
            assertThrows(AnalysisContext.Paused.class, () -> AnalysisContext.stage("canon",
                    () -> AnalysisContext.beforeCall(List.of(), 10)));
            assertSame(scope, AnalysisContext.current());
        }
        assertNull(AnalysisContext.current());
        assertEquals(0, account.metrics().get("calls_this_run"));
    }
    @Test @SuppressWarnings("unchecked") void realGatewayHonoursScopeAndCapsUnspecifiedOutput() {
        var gateway = new LlmGateway(mock(ProjectStorage.class));
        var provider = mock(LlmProvider.class);
        ((Map<String, LlmProvider>) ReflectionTestUtils.getField(gateway, "providers")).put("fixture", provider);
        when(provider.chatOnce(anyList(), any(), any())).thenReturn(LlmResponse.builder().totalTokens(20).build());
        try (var scope = new AnalysisContext(account(0, 2), "V1C1", "combined", new AnalysisProperties())) {
            gateway.chatOnce(List.of(Map.of("content", "source")), "fixture", null, null);
        }
        verify(provider).chatOnce(anyList(), isNull(), eq(4000));
        assertEquals(2, events.size());
        assertEquals(20L, events.stream().mapToLong(event -> ((Number) event.get("budget_tokens")).longValue()).sum());
    }
    @Test void digestIsDeterministicBoundedAndKeepsEnding() {
        String text = String.join("。", java.util.stream.IntStream.range(0, 100).mapToObj(i -> "Scene " + i).toList()) + "。END";
        String digest = ChapterDigest.extract(text, 300);
        assertEquals(digest, ChapterDigest.extract(text, 300));
        assertTrue(digest.length() <= 300);
        assertTrue(digest.contains("END"));
        assertTrue(digest.contains("Scene 0"));
    }
}
