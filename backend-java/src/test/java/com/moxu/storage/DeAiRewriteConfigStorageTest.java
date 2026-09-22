package com.moxu.storage;

import com.moxu.model.entity.DeAiRewriteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeAiRewriteConfigStorageTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"algorithm", "hybrid", "local", "unknown", "  HYBRID ", "  "})
    void defaultsAndLegacyEnginesNormalizeToLocal(String engine) {
        assertEquals("local", DeAiRewriteConfig.defaults().getEngine());
        assertEquals("local", DeAiRewriteConfig.builder().engine(engine).build().normalized().getEngine());
    }

    @Test
    void explicitGenericChoiceAndOldFieldsRemainRoundTrippable() throws Exception {
        DeAiRewriteConfigStorage storage = storage();
        assertEquals("local", storage.getConfig("project").getEngine());
        DeAiRewriteConfig config = DeAiRewriteConfig.defaults();
        config.setEngine(" LLM ");
        config.setBeamWidth(7);
        config.setExtraModelPassEnabled(true);
        config.setExtraModelPassPrompt("旧提示，不再执行");
        storage.saveConfig("project", config);
        DeAiRewriteConfig loaded = storage.getConfig("project");
        assertEquals("llm", loaded.getEngine());
        assertEquals(7, loaded.getBeamWidth());
        assertTrue(loaded.getExtraModelPassEnabled());
        assertEquals("旧提示，不再执行", loaded.getExtraModelPassPrompt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"algorithm", "hybrid"})
    void readingLegacyConfigMigratesInMemoryWithoutRewritingProjectFile(String engine) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path config = project.resolve("de_ai_config.json");
        String json = "{\"engine\":\"" + engine + "\",\"beamWidth\":6,\"returnDebug\":false}";
        Files.writeString(config, json);
        DeAiRewriteConfig loaded = storage().getConfig("project");
        assertEquals("local", loaded.getEngine());
        assertEquals(6, loaded.getBeamWidth());
        assertFalse(loaded.getReturnDebug());
        assertEquals(json, Files.readString(config));
    }

    private DeAiRewriteConfigStorage storage() {
        DeAiRewriteConfigStorage storage = new DeAiRewriteConfigStorage();
        ReflectionTestUtils.setField(storage, "dataDir", tempDir.toString());
        return storage;
    }
}
