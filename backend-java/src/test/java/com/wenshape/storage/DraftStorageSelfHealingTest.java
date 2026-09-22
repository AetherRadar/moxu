package com.wenshape.storage;

import com.wenshape.model.entity.ChapterSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DraftStorageSelfHealingTest {

    @TempDir
    Path tempDir;

    @Test
    void saveChapterSummaryPreservesOldOrderIndexWhenNull() throws Exception {
        DraftStorage storage = new DraftStorage();
        storage.dataDir = tempDir.toString();

        // 1. 保存一个有 orderIndex 的 summary
        ChapterSummary initial = ChapterSummary.builder()
                .chapter("V1C1")
                .volumeId("V1")
                .orderIndex(5)
                .title("第1章")
                .briefSummary("初始摘要")
                .build();
        storage.saveChapterSummary("p1", initial);

        // 2. 覆盖保存一个没有 orderIndex 的 summary
        ChapterSummary updated = ChapterSummary.builder()
                .chapter("V1C1")
                .volumeId("V1")
                .title("第1章修改版")
                .briefSummary("修改后的摘要")
                .build();
        storage.saveChapterSummary("p1", updated);

        // 3. 验证 orderIndex 是否被正确保留
        ChapterSummary loaded = storage.getChapterSummary("p1", "V1C1").orElseThrow();
        assertEquals(5, loaded.getOrderIndex(), "当传入的 orderIndex 为 null 时，应自动从磁盘已有实体中恢复旧的 orderIndex");
        assertEquals("第1章修改版", loaded.getTitle());
    }

    @Test
    void listChapterSummariesHealsDuplicateIndices() throws Exception {
        DraftStorage storage = new DraftStorage();
        storage.dataDir = tempDir.toString();

        // 1. 模拟产生重复 index 的数据 (V1C1 和 V1C8 都是 0)
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C1").volumeId("V1").orderIndex(0).title("C1").build());
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C2").volumeId("V1").orderIndex(1).title("C2").build());
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C3").volumeId("V1").orderIndex(2).title("C3").build());
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C8").volumeId("V1").orderIndex(0).title("C8").build());

        // 2. 调用 listChapterSummaries，应当触发自愈
        List<ChapterSummary> list = storage.listChapterSummaries("p1", "V1");

        // 3. 验证自愈后的顺序及持久化状态
        assertEquals(4, list.size());
        assertEquals("V1C1", list.get(0).getChapter());
        assertEquals(0, list.get(0).getOrderIndex());

        assertEquals("V1C2", list.get(1).getChapter());
        assertEquals(1, list.get(1).getOrderIndex());

        assertEquals("V1C3", list.get(2).getChapter());
        assertEquals(2, list.get(2).getOrderIndex());

        assertEquals("V1C8", list.get(3).getChapter());
        assertEquals(3, list.get(3).getOrderIndex());

        // 4. 从磁盘直接读取，验证自愈的数据已被写入文件
        ChapterSummary diskC8 = storage.getChapterSummary("p1", "V1C8").orElseThrow();
        assertEquals(3, diskC8.getOrderIndex(), "自愈后的新 orderIndex 应持久化保存到磁盘中");
    }

    @Test
    void listChapterSummariesHealsGapsAndNulls() throws Exception {
        DraftStorage storage = new DraftStorage();
        storage.dataDir = tempDir.toString();

        // 1. 模拟含有 null index 的情况
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C2").volumeId("V1").orderIndex(1).title("C2").build());
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C1").volumeId("V1").orderIndex(0).title("C1").build());
        storage.saveChapterSummary("p1", ChapterSummary.builder().chapter("V1C3").volumeId("V1").orderIndex(null).title("C3").build());

        // 2. 调用 listChapterSummaries 触发自愈
        List<ChapterSummary> list = storage.listChapterSummaries("p1", "V1");

        // 3. 验证保留了非 null 的相对顺序，并将 null 的章节追加到最后
        assertEquals(3, list.size());
        assertEquals("V1C1", list.get(0).getChapter());
        assertEquals(0, list.get(0).getOrderIndex());

        assertEquals("V1C2", list.get(1).getChapter());
        assertEquals(1, list.get(1).getOrderIndex());

        assertEquals("V1C3", list.get(2).getChapter());
        assertEquals(2, list.get(2).getOrderIndex());

        // 验证持久化
        ChapterSummary diskC3 = storage.getChapterSummary("p1", "V1C3").orElseThrow();
        assertEquals(2, diskC3.getOrderIndex());
    }
}
