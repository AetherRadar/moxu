package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 批量保存分析项
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisBatchItemRequest {
    private String chapter;
    private Map<String, Object> analysis;
}
