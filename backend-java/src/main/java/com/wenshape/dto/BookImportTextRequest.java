package com.wenshape.dto;

import lombok.Data;

@Data
public class BookImportTextRequest {
    private String sourceName;
    private String text;
    private Boolean quickInit;
    private Boolean overwriteStyle;
    private Boolean overwriteCards;
    private Boolean autoExtractTitles;
    private Integer maxCharacterCards;
    private Integer maxWorldCards;
}
