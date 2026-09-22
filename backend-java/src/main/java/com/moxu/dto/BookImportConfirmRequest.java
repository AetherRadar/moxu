package com.moxu.dto;

import lombok.Data;

import java.util.List;

@Data
public class BookImportConfirmRequest {
    private String sourceName;
    private String format;
    private Boolean quickInit;
    private Boolean overwriteStyle;
    private Boolean overwriteCards;
    private Boolean autoExtractTitles;
    private Integer maxCharacterCards;
    private Integer maxWorldCards;
    private List<VolumeDraft> volumes;

    @Data
    public static class VolumeDraft {
        private String title;
        private Boolean explicit;
        private List<ChapterDraft> chapters;
    }

    @Data
    public static class ChapterDraft {
        private Boolean selected;
        private String title;
        private String content;
        private Boolean syntheticTitle;
    }
}
