package com.moxu.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;

class PreviousChapterEndingTest {
    @TempDir Path directory;

    private DraftStorage storage() {
        var storage = new DraftStorage();
        ReflectionTestUtils.setField(storage, "dataDir", directory.toString());
        return storage;
    }

    private Path write(String chapter, String file, String content) throws Exception {
        Path path = directory.resolve("book/drafts").resolve(chapter).resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    @Test void selectsNumericPredecessorAcrossVolumesAndSkipsBlankPlans() throws Exception {
        write("V1C9", "final.md", "older");
        write("V1C100", "final.md", "latest written");
        write("V1C101", "final.md", " \n ");
        write("V2C1", "final.md", "current must not leak");
        write("V2C2", "final.md", "future must not leak");
        write("notes", "final.md", "not a chapter");
        var ending = storage().readPreviousChapterEnding("book", "V2C1", 2000).orElseThrow();
        assertEquals("V1C100", ending.chapter());
        assertEquals("latest written", ending.text());
    }

    @Test void readsLatestDiskTextWithoutAnArchiveOrSummary() throws Exception {
        Path source = write("V1C1", "final.md", "The card is held.");
        var storage = storage();
        assertEquals("The card is held.", storage.readPreviousChapterEnding("book", "V1C2", 2000).orElseThrow().text());
        Files.writeString(source, "The card is in the pocket.");
        assertEquals("The card is in the pocket.", storage.readPreviousChapterEnding("book", "V1C2", 2000).orElseThrow().text());
    }

    @Test void honorsFinalEvenWhenBlankAndUsesLatestLegacyOnlyWithoutFinal() throws Exception {
        Path older = write("V1C1", "draft_v1.md", "old version");
        Path newer = write("V1C1", "draft_v2.md", "new version");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
        assertEquals("new version", storage().readPreviousChapterEnding("book", "V1C2", 2000).orElseThrow().text());
        write("V1C1", "final.md", "");
        assertTrue(storage().readPreviousChapterEnding("book", "V1C2", 2000).isEmpty());
    }

    @Test void boundsExcerptWithoutSplittingSurrogatesOrDroppingEnding() throws Exception {
        write("V1C1", "final.md", "x".repeat(3000) + "\uD83D\uDD11" + "ending");
        String tail = storage().readPreviousChapterEnding("book", "V1C2", 7).orElseThrow().text();
        assertTrue(tail.length() <= 7);
        assertFalse(Character.isLowSurrogate(tail.charAt(0)));
        assertTrue(tail.endsWith("ending"));
    }

    @Test void refusesUnreadableNearestSourceInsteadOfUsingOlderContext() throws Exception {
        write("V1C1", "final.md", "older");
        Files.createDirectories(directory.resolve("book/drafts/V1C2/final.md"));
        assertThrows(java.io.IOException.class,
                () -> storage().readPreviousChapterEnding("book", "V1C3", 2000));
    }

    @Test void handlesFirstChapterMissingBookAndInvalidTarget() throws Exception {
        write("V1C1", "final.md", "first");
        assertTrue(storage().readPreviousChapterEnding("book", "V1C1", 2000).isEmpty());
        assertTrue(storage().readPreviousChapterEnding("missing", "V1C2", 2000).isEmpty());
        assertTrue(storage().readPreviousChapterEnding("book", "../secret", 2000).isEmpty());
        assertTrue(storage().readPreviousChapterEnding("book", "V1C2", 0).isEmpty());
    }
}
