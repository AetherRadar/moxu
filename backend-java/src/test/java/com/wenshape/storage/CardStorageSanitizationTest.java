package com.wenshape.storage;

import com.wenshape.model.entity.CharacterCard;
import com.wenshape.model.entity.WorldCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CardStorageSanitizationTest {

    @TempDir
    Path tempDir;

    @Test
    void saveCharacterCardWithSlashNameUsesSafeFlatFileButKeepsDisplayName() throws Exception {
        CardStorage storage = new CardStorage();
        storage.dataDir = tempDir.toString();

        storage.saveCharacterCard("p", CharacterCard.builder()
                .name("艾尔/艾莉娅")
                .aliases(List.of("艾尔", "艾莉娅"))
                .description("双阶段主角。")
                .stars(2)
                .build());

        Path legacyNestedPath = tempDir.resolve("p/cards/characters/艾尔/艾莉娅.yaml");
        List<Path> yamlFiles = listYamlFiles(tempDir.resolve("p/cards/characters"));

        assertFalse(Files.exists(legacyNestedPath), "新保存逻辑不应再把名字中的 / 当成目录层级");
        assertEquals(1, yamlFiles.size(), "应只生成一个角色卡文件");
        assertEquals(tempDir.resolve("p/cards/characters"), yamlFiles.getFirst().getParent(), "新卡片应写在 characters 顶层目录");
        assertEquals(List.of("艾尔/艾莉娅"), storage.listCharacterCards("p"));

        CharacterCard loaded = storage.getCharacterCard("p", "艾尔/艾莉娅").orElseThrow();
        assertEquals("艾尔/艾莉娅", loaded.getName());
        assertEquals(List.of("艾尔", "艾莉娅"), loaded.getAliases());
    }

    @Test
    void legacyNestedCharacterCardCanStillBeReadAndMigratedOnNextSave() throws Exception {
        CardStorage storage = new CardStorage();
        storage.dataDir = tempDir.toString();

        Path legacyNestedPath = tempDir.resolve("p/cards/characters/艾尔/艾莉娅.yaml");
        storage.writeYaml(legacyNestedPath, Map.of(
                "name", "艾尔/艾莉娅",
                "aliases", List.of("艾尔", "艾莉娅"),
                "description", "旧路径卡片。",
                "stars", 2
        ));

        assertTrue(storage.listCharacterCards("p").contains("艾尔/艾莉娅"), "旧坏路径卡片也应该能被列出来");
        CharacterCard before = storage.getCharacterCard("p", "艾尔/艾莉娅").orElseThrow();
        assertEquals("旧路径卡片。", before.getDescription());

        storage.saveCharacterCard("p", CharacterCard.builder()
                .name("艾尔/艾莉娅")
                .aliases(List.of("艾尔", "艾莉娅"))
                .description("已迁移到安全文件名。")
                .stars(3)
                .build());

        List<Path> yamlFiles = listYamlFiles(tempDir.resolve("p/cards/characters"));
        assertFalse(Files.exists(legacyNestedPath), "再次保存后应清理旧的嵌套坏路径");
        assertEquals(1, yamlFiles.size(), "迁移后应只保留一个安全路径文件");
        assertEquals(tempDir.resolve("p/cards/characters"), yamlFiles.getFirst().getParent(), "迁移后的文件应回到顶层目录");

        CharacterCard migrated = storage.getCharacterCard("p", "艾尔/艾莉娅").orElseThrow();
        assertEquals("已迁移到安全文件名。", migrated.getDescription());
        assertEquals(3, migrated.getStars());
    }

    @Test
    void worldCardsAlsoSupportUnsafeDisplayNames() throws Exception {
        CardStorage storage = new CardStorage();
        storage.dataDir = tempDir.toString();

        storage.saveWorldCard("p", WorldCard.builder()
                .name("人类/魅魔转化")
                .description("危险设定名")
                .category("concept")
                .rules(List.of("失败者化为干尸"))
                .build());

        assertTrue(storage.listWorldCards("p").contains("人类/魅魔转化"));
        WorldCard loaded = storage.getWorldCard("p", "人类/魅魔转化").orElseThrow();
        assertEquals("人类/魅魔转化", loaded.getName());

        assertTrue(storage.deleteWorldCard("p", "人类/魅魔转化"));
        assertTrue(storage.listWorldCards("p").isEmpty());
    }

    private List<Path> listYamlFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var paths = Files.walk(dir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }
    }
}
