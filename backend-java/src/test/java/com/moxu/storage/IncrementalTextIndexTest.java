package com.moxu.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moxu.search.ProjectLexicon;
import com.moxu.search.ProjectLexiconResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncrementalTextIndexTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    DraftStorage storage;

    @BeforeEach void setup() {
        storage = new DraftStorage();
        ReflectionTestUtils.setField(storage, "dataDir", directory.toString());
    }
    void write(String chapter, String text) throws Exception {
        storage.saveCurrentDraft("book", chapter, text, text.length(), null, false);
    }
    List<Map<String, Object>> search(String query) {
        return storage.searchTextChunks("book", query, 10, null, null, false);
    }
    Map<String, Object> meta() throws Exception {
        return mapper.readValue(Files.readString(directory.resolve("book/text_chunk_index/meta.json")), new TypeReference<>() {});
    }
    @Test void firstQueryBuildsAnIndexAndUnchangedQueriesDoNotRewriteIt() throws Exception {
        write("V1C1", "Alice owns the silver key.");
        assertFalse(search("silver").isEmpty());
        Path path = directory.resolve("book/text_chunk_index/meta.json");
        String before = Files.readString(path);
        var timestamp = Files.getLastModifiedTime(path);
        for (int i = 0; i < 3; i++) assertFalse(search("silver").isEmpty());
        assertEquals(timestamp, Files.getLastModifiedTime(path));
        assertEquals(before, Files.readString(path));
    }
    @Test void sameLengthEditWithRestoredTimestampStillInvalidatesOldProse() throws Exception {
        write("V1C1", "Alice owns a silver key.");
        assertFalse(search("silver").isEmpty());
        Path draft = directory.resolve("book/drafts/V1C1/final.md");
        var timestamp = Files.getLastModifiedTime(draft);
        long length = Files.size(draft);
        Files.writeString(draft, "Alice owns a golden key.");
        Files.setLastModifiedTime(draft, timestamp);
        assertEquals(length, Files.size(draft));
        assertTrue(search("silver").isEmpty());
        assertFalse(search("golden").isEmpty());
        assertEquals(1, meta().get("refreshed_chapters"));
    }
    @Test void twoHundredChaptersOnlyRetokenizeTheEditedChapter() throws Exception {
        for (int i = 1; i <= 200; i++) write("V1C" + i, "Alice scene " + i + " silver key.");
        search("silver");
        write("V1C4", "Alice finds the obsidian key.");
        var hits = search("obsidian");
        assertEquals(1, hits.size());
        assertEquals("V1C4", hits.getFirst().get("chapter"));
        assertEquals(1, meta().get("refreshed_chapters"));
        assertEquals(199, meta().get("reused_chapters"));
        assertEquals(200, meta().get("chapters_processed"));
        assertTrue(storage.searchTextChunks("book", "silver", 3, List.of("V1C4"), null, false).isEmpty());
    }
    @Test void additionsEmptyChaptersAndDeletionUpdatePostingsAndCorpusStatistics() throws Exception {
        write("V1C1", "Alice silver key."); write("V1C2", "Alice golden key.");
        search("key");
        write("V1C1", "");
        assertTrue(search("silver").isEmpty());
        assertEquals(1, meta().get("chunks_total"));
        write("V1C3", "Alice copper key.");
        assertFalse(search("copper").isEmpty());
        Files.delete(directory.resolve("book/drafts/V1C2/final.md"));
        assertTrue(search("golden").isEmpty());
        assertEquals(1, meta().get("removed_chapters"));
        assertEquals(1, meta().get("chunks_total"));
    }
    @Test void cachedIndexIsInvalidatedWhenProjectLexiconChanges() throws Exception {
        var resolver = mock(ProjectLexiconResolver.class);
        when(resolver.resolve("book")).thenReturn(ProjectLexicon.empty());
        ReflectionTestUtils.setField(storage, "projectLexiconResolver", resolver);
        write("V1C1", "Alice silver key.");
        search("key"); search("key");
        var updated = ProjectLexicon.fromTerms(List.of("白银审判骑士团"));
        when(resolver.resolve("book")).thenReturn(updated);
        search("key");
        assertEquals(updated.fingerprint(), meta().get("lexicon_fingerprint"));
        assertEquals(1, meta().get("refreshed_chapters"));
        assertEquals(0, meta().get("reused_chapters"));
    }
    @Test void persistedIndexCanBeIncrementallyReusedAfterRestart() throws Exception {
        write("V1C1", "Alice silver key."); write("V1C2", "Alice golden key.");
        search("key");
        storage = new DraftStorage();
        ReflectionTestUtils.setField(storage, "dataDir", directory.toString());
        write("V1C2", "Alice copper key.");
        assertFalse(search("copper").isEmpty());
        assertTrue(search("golden").isEmpty());
        assertEquals(1, meta().get("reused_chapters"));
    }
    @Test void incompleteOrCorruptIndexIsRebuiltFromCurrentSources() throws Exception {
        write("V1C1", "Alice silver key."); search("key");
        Files.writeString(directory.resolve("book/text_chunk_index/chunks.json"), "{}");
        storage = new DraftStorage();
        ReflectionTestUtils.setField(storage, "dataDir", directory.toString());
        assertFalse(search("silver").isEmpty());
        Files.writeString(directory.resolve("book/text_chunk_index/inverted_index.json"), "invalid");
        assertFalse(search("silver").isEmpty());
        assertEquals(1, meta().get("refreshed_chapters"));
    }
    @Test void oldIndexFormatMigratesRatherThanBeingTrusted() throws Exception {
        write("V1C1", "Alice silver key."); search("key");
        var old = new LinkedHashMap<>(meta());
        old.remove("source_hashes"); old.remove("format_version");
        Files.writeString(directory.resolve("book/text_chunk_index/meta.json"), mapper.writeValueAsString(old));
        assertFalse(search("silver").isEmpty());
        assertEquals(2, meta().get("format_version"));
        assertTrue(meta().containsKey("source_hashes"));
    }
    @Test void explicitInvalidIncludeFilterCannotTurnIntoAnUnrestrictedSearch() throws Exception {
        write("V1C99", "Alice silver key.");
        for (boolean rebuild : List.of(false, true)) {
            assertTrue(storage.searchTextChunks("book", "silver", 5,
                    List.of("__no_previous_written_chapter__"), null, rebuild).isEmpty());
            assertTrue(storage.searchTextChunks("book", "silver", 5, List.of("  "), null, rebuild).isEmpty());
        }
        assertTrue(storage.searchTextChunks("book", "silver", 5, List.of("V1C1"), null, false).isEmpty());
        assertTrue(storage.searchTextChunks("book", "silver", 5, null, List.of("V1C99"), false).isEmpty());
        assertFalse(storage.searchTextChunks("book", "silver", 5, List.of("V01C099"), null, false).isEmpty());
    }
    @Test void longParagraphChunksAreBoundedAndPreserveAllUnicodeCharacters() throws Exception {
        String text = "a".repeat(419) + "\uD83D\uDE00" + "b".repeat(2000);
        write("V1C1", text); storage.rebuildTextChunkIndex("book");
        Map<String, DraftStorage.ChunkMeta> chunks = mapper.readValue(
                Files.readString(directory.resolve("book/text_chunk_index/chunks.json")), new TypeReference<>() {});
        StringBuilder joined = new StringBuilder();
        for (var chunk : chunks.values()) {
            assertTrue(chunk.text().length() <= 420);
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            joined.append(chunk.text());
        }
        assertEquals(text, joined.toString());
    }
    @Test void tiesHaveStableNumericChapterOrderingAndRespectTopK() throws Exception {
        for (String chapter : List.of("V1C10", "V1C2", "V2C1", "V1C1")) write(chapter, "Alice silver key.");
        var hits = storage.searchTextChunks("book", "silver", 2, null, null, false);
        assertEquals(List.of("V1C1", "V1C2"), hits.stream().map(h -> h.get("chapter")).toList());
        assertEquals(hits, storage.searchTextChunks("book", "silver", 2, null, null, true));
    }
    @Test void readFailureDoesNotFallBackToAnOldIndex() throws Exception {
        write("V1C1", "Alice silver key."); search("silver");
        Path file = directory.resolve("book/drafts/V1C1/final.md");
        Files.delete(file); Files.createDirectory(file);
        assertTrue(search("silver").isEmpty());
    }
    @Test void cacheCannotLeakBetweenProjects() throws Exception {
        write("V1C1", "Alice silver key.");
        storage.saveCurrentDraft("other", "V1C1", "Bob golden key.", 15, null, false);
        assertFalse(search("silver").isEmpty());
        assertTrue(storage.searchTextChunks("other", "silver", 5, null, null, false).isEmpty());
        assertFalse(search("silver").isEmpty());
    }
    @Test void concurrentFirstSearchesUseOneConsistentIndexGeneration() throws Exception {
        write("V1C1", "Alice silver key.");
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<List<Map<String, Object>>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> search("silver"));
            var results = pool.invokeAll(calls);
            for (var result : results) assertEquals(1, result.get(30, TimeUnit.SECONDS).size());
        }
        assertEquals(1, meta().get("chunks_total"));
    }
    @Test void nonChapterDirectoriesAndLegacyVersionsDoNotLeakIntoFinalProseSearch() throws Exception {
        Path notes = directory.resolve("book/drafts/notes");
        Files.createDirectories(notes);
        Files.writeString(notes.resolve("final.md"), "secret notes");
        Path chapter = directory.resolve("book/drafts/V1C1");
        Files.createDirectories(chapter);
        Files.writeString(chapter.resolve("draft_1.md"), "legacy secret");
        assertTrue(search("secret").isEmpty());
        assertEquals(Map.of("V1C1", "legacy secret"), storage.readManuscriptForAudit("book"));
    }
}
