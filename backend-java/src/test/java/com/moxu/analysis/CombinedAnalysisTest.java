package com.moxu.analysis;

import com.moxu.agent.ArchivistAgent;
import com.moxu.llm.*;
import com.moxu.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CombinedAnalysisTest {
    @TempDir Path directory;
    private <T extends BaseStorage> T configured(T storage) {
        ReflectionTestUtils.setField(storage, "dataDir", directory.toString()); return storage;
    }
    private ArchivistAgent agent(LlmGateway gateway) {
        when(gateway.getProviderForAgent(anyString())).thenReturn("fixture");
        return new ArchivistAgent(gateway, configured(new CardStorage()), configured(new DraftStorage()), null,
                configured(new CanonStorage()));
    }
    private String response(double confidence) {
        return """
                {"summary":{"brief_summary":"探员找到编号明确的证物，案情出现转机。","key_events":["发现证物"],
                "character_state_changes":[],"open_loops":[]},"facts":[
                {"statement":"证物编号是302","evidence":"证物编号是302。","certainty":"explicit","confidence":0.9}],
                "timeline_events":[],"character_states":[],"confidence":%s}
                """.formatted(confidence);
    }
    @Test void combinedUsesOneCallFullSourceAndPreservesEvidence() {
        var gateway = mock(LlmGateway.class);
        when(gateway.chatOnce(anyList(), any(), any(), any()))
                .thenReturn(LlmResponse.builder().content(response(0.95)).finishReason("stop").build());
        String text = "开头线索。".repeat(1000) + "证物编号是302。" + "结尾线索。".repeat(1000);
        var result = agent(gateway).analyzeCombined("book", "V1C1", "证物", text, true, 4000);
        assertEquals(1, ((List<?>) result.canon().get("facts")).size());
        verify(gateway, times(1)).chatOnce(argThat(messages -> messages.stream().anyMatch(m -> m.get("content").contains(text))),
                eq("fixture"), eq(0.0), eq(4000));
        verify(gateway, never()).chat(anyList(), any(), any(), any());
    }
    @Test void malformedTruncatedAndLowConfidenceOutputsAreEligibleForRecovery() {
        for (LlmResponse output : List.of(LlmResponse.builder().content("invalid").build(),
                LlmResponse.builder().content(response(0.2)).build(),
                LlmResponse.builder().content(response(0.9)).finishReason("length").build())) {
            var gateway = mock(LlmGateway.class);
            when(gateway.chatOnce(anyList(), any(), any(), any())).thenReturn(output);
            assertThrows(ArchivistAgent.InvalidExtraction.class,
                    () -> agent(gateway).analyzeCombined("book", "V1C1", "证物", "证物编号是302。", true, 4000));
        }
    }
    @Test void transportFailureIsNotDisguisedAsLowConfidenceRecovery() {
        var gateway = mock(LlmGateway.class);
        when(gateway.chatOnce(anyList(), any(), any(), any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class,
                () -> agent(gateway).analyzeCombined("book", "V1C1", "", "source", true, 4000));
    }
}
