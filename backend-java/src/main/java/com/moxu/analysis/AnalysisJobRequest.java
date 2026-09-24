package com.moxu.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

@Data
public class AnalysisJobRequest {
    @Size(max = 2000)
    private List<@Pattern(regexp = "(?i)V\\d+C\\d+") String> chapters;
    @Pattern(regexp = "zh|en")
    private String language;
    @JsonProperty("job_id")
    private String jobId;
    private Boolean force;
    private Boolean combined;
    @JsonProperty("bulk_mode")
    private Boolean bulkMode;
    @JsonProperty("max_tokens")
    @Min(0) @Max(1000000000)
    private Long maxTokens;
    @JsonProperty("max_minutes")
    @Min(1) @Max(1440)
    private Integer maxMinutes;
    @JsonProperty("max_calls_per_chapter")
    @Min(1) @Max(20)
    private Integer maxCallsPerChapter;
    @JsonProperty("from_chapter")
    @Pattern(regexp = "(?i)V\\d+C\\d+")
    private String fromChapter;
    @JsonProperty("book_summary")
    private Boolean bookSummary;
}
