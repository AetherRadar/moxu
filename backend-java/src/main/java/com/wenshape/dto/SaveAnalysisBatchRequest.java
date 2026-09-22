package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 批量保存分析结果请求
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaveAnalysisBatchRequest {
    private String language;
    private List<AnalysisBatchItemRequest> items;
    private Boolean overwrite;
}
