package com.moxu.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BaseStorageDataPathTest {
    @TempDir Path directory;

    @Test void missingExplicitDirectoryDoesNotFallBackToExistingRepositoryData() throws Exception {
        var storage = new ProjectStorage();
        Path target = directory.resolve("isolated/new-data");
        ReflectionTestUtils.setField(storage, "dataDir", target.toString());
        assertEquals(target, storage.getDataPath());
        assertTrue(storage.listProjects().isEmpty());
        storage.writeYaml(target.resolve("test.yaml"), Map.of("isolated", true));
        assertTrue(Files.exists(target.resolve("test.yaml")));
        assertEquals(target, storage.getDataPath());
    }

    @Test void missingRelativeDirectoryKeepsItsConfiguredLocation() {
        var storage = new ProjectStorage();
        String relative = "target/nonexistent-" + directory.getFileName();
        ReflectionTestUtils.setField(storage, "dataDir", relative);
        assertEquals(Path.of(relative).toAbsolutePath().normalize(), storage.getDataPath());
    }

    @Test void invalidOrBlankDirectoryCannotSelectAnotherDataRoot() throws Exception {
        var storage = new ProjectStorage();
        Path file = Files.createFile(directory.resolve("not-a-directory"));
        ReflectionTestUtils.setField(storage, "dataDir", file.toString());
        assertThrows(IllegalStateException.class, storage::getDataPath);
        ReflectionTestUtils.setField(storage, "dataDir", "");
        assertThrows(IllegalStateException.class, storage::getDataPath);
    }
}
