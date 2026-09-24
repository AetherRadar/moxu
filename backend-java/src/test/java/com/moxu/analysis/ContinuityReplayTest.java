package com.moxu.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.quality.ContinuityProtection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local-only replay of paid outputs. No gateway or credentials are read. */
@EnabledIfEnvironmentVariable(named = "MOXU_CONTINUITY_REPLAY", matches = ".+")
class ContinuityReplayTest {
    @Test void recordsCurrentLocalReviewOfBothExistingDrafts() throws Exception {
        Path root = Path.of("target/live-evaluation").toRealPath();
        Path directory = Path.of(System.getenv("MOXU_CONTINUITY_REPLAY")).toRealPath();
        assertTrue(directory.startsWith(root), "Replay output must stay inside ignored evaluation artifacts");
        String source = Files.readString(directory.resolve("source.md"));
        for (String stage : new String[] {"full-source", "compressed"}) {
            String draft = Files.readString(directory.resolve(stage + "-draft.md"));
            var report = ContinuityProtection.review("live", "V1C2", "V1C1", source, draft);
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve(stage + "-continuity-review-v2.json").toFile(), report);
        }
    }
}
