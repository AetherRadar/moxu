package com.moxu.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class CanonTimelineEventTest {

    @TempDir
    Path tempDir;

    private CanonStorage storage() {
        CanonStorage storage = new CanonStorage();
        storage.dataDir = tempDir.toString();
        return storage;
    }

    @Test
    void updateKeepsPathIdAndOverwritesContent() throws Exception {
        CanonStorage storage = storage();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("time", "第3天");
        event.put("event", "主角进城");
        storage.saveTimelineEvent("p", event);

        Map<String, Object> saved = storage.listTimelineEvents("p").getFirst();
        String id = String.valueOf(saved.get("id"));
        assertTrue(id.startsWith("T"), "新建事件应拿到 T 开头的 id");

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("time", "第4天");
        edit.put("event", "主角出城");
        edit.put("id", "T999");
        storage.updateTimelineEvent("p", id, edit);

        Map<String, Object> loaded = storage.getTimelineEvent("p", id).orElseThrow();
        assertEquals(id, loaded.get("id"), "路径 id 优先于 body 里的 id");
        assertEquals("第4天", loaded.get("time"));
        assertEquals(1, storage.listTimelineEvents("p").size(), "编辑不应新增文件");
    }

    @Test
    void updateRejectsUnknownEvent() {
        CanonStorage storage = storage();
        assertThrows(NoSuchElementException.class,
                () -> storage.updateTimelineEvent("p", "T404", Map.of("event", "无中生有")));
    }

    @Test
    void deleteRemovesOnlyValidatedIds() throws Exception {
        CanonStorage storage = storage();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "删我");
        storage.saveTimelineEvent("p", event);
        String id = String.valueOf(storage.listTimelineEvents("p").getFirst().get("id"));

        assertTrue(storage.deleteTimelineEvent("p", id));
        assertTrue(storage.getTimelineEvent("p", id).isEmpty());
        assertTrue(storage.listTimelineEvents("p").isEmpty());

        assertFalse(storage.deleteTimelineEvent("p", "../outside"), "路径穿越 id 必须被拒绝");
        assertFalse(storage.deleteTimelineEvent("p", null));
    }
}