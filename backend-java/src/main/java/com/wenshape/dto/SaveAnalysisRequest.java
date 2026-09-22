package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 保存单章分析结果请求
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaveAnalysisRequest {
    private String language;
    private String chapter;
    private Map<String, Object> analysis;
    private Boolean overwrite;
}
