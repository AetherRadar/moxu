package com.moxu.analysis;

import lombok.Data;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@org.springframework.validation.annotation.Validated
@ConfigurationProperties(prefix = "moxu.analysis")
public class AnalysisProperties {
    private boolean incremental = true;
    private boolean combined = false;
    private boolean compactPrompt = true;
    private boolean legacyFallback = true;
    private boolean bulkMode = true;
    private boolean bulkDisableFocusBinding = true;
    private boolean bulkDisableSemanticRerank = true;
    @Min(0) private long maxTokensPerJob = 0;
    @Min(1) @Max(1440) private int maxMinutesPerJob = 30;
    @Min(1) @Max(20) private int maxCallsPerChapter = 6;
    @Min(128) @Max(32000) private int maxOutputTokens = 4000;
    @Min(2) @Max(50) private int reduceBatchSize = 20;
}
