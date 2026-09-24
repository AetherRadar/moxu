package com.moxu.controller;

import com.moxu.storage.DraftStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class DraftContinuityCheckTest {
    @TempDir Path directory;
    DraftStorage drafts;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        drafts = new DraftStorage();
        ReflectionTestUtils.setField(drafts, "dataDir", directory.toString());
        drafts.saveCurrentDraft("book", "V1C1", "第二天一早。", 6, null, false);
        mvc = MockMvcBuilders.standaloneSetup(new DraftController(drafts, null, null, null,
                null, null, null, null, null)).build();
    }
    @Test void returnsRevisionAndWarningsWithoutSavingPreview() throws Exception {
        mvc.perform(post("/projects/book/drafts/V1C2/continuity-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"时间定格在十三点零四分。\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").isNotEmpty())
                .andExpect(jsonPath("$.source_chapter").value("V1C1"))
                .andExpect(jsonPath("$.issues[0].type").value("unverified_clock"));
        assertTrue(drafts.getLatestDraft("book", "V1C2").isEmpty());
    }
    @Test void rejectsMissingContentAndInvalidChapterBeforeReadingSources() throws Exception {
        mvc.perform(post("/projects/book/drafts/V1C2/continuity-check").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/projects/book/drafts/not-a-chapter/continuity-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"text\"}")).andExpect(status().isBadRequest());
    }

    @Test void semanticEndpointRequiresExplicitRevisionAndPositiveBoundedBudget() throws Exception {
        var semantic = mock(com.moxu.analysis.DraftSemanticReviewService.class);
        var controller = new DraftController(drafts, null, null, null, null, null, null, null, null);
        controller.setSemanticReview(semantic);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var review = com.moxu.quality.ContinuityProtection.review(drafts, "book", "V1C2", "Draft");
        when(semantic.run("book", "V1C2", "Draft", "revision", 60000)).thenReturn(review);
        mvc.perform(post("/projects/book/drafts/V1C2/semantic-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Draft\",\"revision\":\"revision\",\"max_tokens\":60000}"))
                .andExpect(status().isOk());
        verify(semantic).run("book", "V1C2", "Draft", "revision", 60000);
        clearInvocations(semantic);
        for (String payload : new String[]{
                "{\"content\":\"Draft\",\"revision\":\"revision\",\"max_tokens\":0}",
                "{\"content\":\"Draft\",\"revision\":\"revision\",\"max_tokens\":200001}",
                "{\"content\":\"Draft\",\"max_tokens\":60000}"}) {
            mvc.perform(post("/projects/book/drafts/V1C2/semantic-check").contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(semantic);
    }
}
