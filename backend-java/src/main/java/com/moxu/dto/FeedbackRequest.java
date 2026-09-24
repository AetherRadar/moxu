package com.moxu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * 用户反馈请求
 */
@Data
public class FeedbackRequest {
    private String chapter;
    private String feedback;
    private String action = "revise";

    @JsonProperty("continuity_revision")
    private String continuityRevision;

    @JsonProperty("rejected_entities")
    private List<String> rejectedEntities;
}
