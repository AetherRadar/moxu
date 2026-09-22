package com.moxu.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 文风卡片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleCard {
    
    private String style;

    @JsonProperty("representative_excerpts")
    @Builder.Default
    private List<String> representativeExcerpts = new ArrayList<>();

    @JsonProperty("source_chapters")
    @Builder.Default
    private List<String> sourceChapters = new ArrayList<>();
}
