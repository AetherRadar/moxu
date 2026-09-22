package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 同步分析章节请求
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyzeSyncRequest {
    private String language;
    private List<String> chapters;
}
