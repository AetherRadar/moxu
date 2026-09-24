package com.moxu.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftHistoryTest {
    @TempDir Path directory;
    private <T extends DraftStorage> T configured(T value) {
        ReflectionTestUtils.setField(value, "dataDir", directory.toString());
        return value;
    }
    private List<Path> backups() throws IOException {
        try (var files = Files.list(directory.resolve("book/drafts/V1C1/history"))) {
            return files.filter(p -> p.toString().endsWith(".md")).toList();
        }
    }

    @Test void rapidSavesRetainLastThreeDistinctRevisionsWithoutNameCollisions() throws Exception {
        var storage = configured(new DraftStorage());
        for (int i = 0; i <= 12; i++)
            storage.saveCurrentDraft("book", "V1C1", "revision-" + i, null, null, true);
        assertEquals("revision-12", storage.getFinalDraft("book", "V1C1").orElseThrow());
        Set<String> previous = new HashSet<>();
        for (Path backup : backups()) previous.add(Files.readString(backup));
        assertEquals(Set.of("revision-9", "revision-10", "revision-11"), previous);
        storage.saveCurrentDraft("book", "V1C1", "revision-12", null, null, true);
        storage.saveFinalDraft("book", "V1C1", "revision-12");
        assertEquals(3, backups().size());
        for (Path backup : backups()) assertTrue(previous.contains(Files.readString(backup)));
    }

    @Test void failedReplacementDoesNotRemoveTheCurrentManuscript() throws Exception {
        var storage = configured(new DraftStorage() {
            @Override protected void writeText(Path path, String text) throws IOException {
                if (text.equals("failed")) throw new IOException("Injected write failure");
                super.writeText(path, text);
            }
        });
        storage.saveCurrentDraft("book", "V1C1", "original", null, null, true);
        assertThrows(IOException.class, () -> storage.saveCurrentDraft("book", "V1C1", "failed", null, null, true));
        assertEquals("original", storage.getFinalDraft("book", "V1C1").orElseThrow());
        assertEquals("original", Files.readString(backups().getFirst()));
    }

    @Test void simultaneousFinalAndCurrentSavesShareOneChapterWriteLock() throws Exception {
        var storage = configured(new DraftStorage());
        storage.saveCurrentDraft("book", "V1C1", "initial", null, null, false);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                calls.add(() -> {
                    if (index % 2 == 0) storage.saveFinalDraft("book", "V1C1", "text-" + index);
                    else storage.saveCurrentDraft("book", "V01C01", "text-" + index, null, null, true);
                    return null;
                });
            }
            for (var future : workers.invokeAll(calls)) future.get();
        }
        String text = storage.getFinalDraft("book", "V1C1").orElseThrow();
        assertTrue(text.startsWith("text-"));
        assertEquals(text, storage.readYamlAsMap(directory.resolve("book/drafts/V1C1/final.meta.yaml")).get("content"));
        assertEquals(3, backups().size());
    }
}
