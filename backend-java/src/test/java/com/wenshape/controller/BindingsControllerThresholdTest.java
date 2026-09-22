package com.wenshape.controller;

import com.wenshape.config.BindingsProperties;
import com.wenshape.model.entity.WorldCard;
import com.wenshape.search.ProjectLexicon;
import com.wenshape.search.ProjectLexiconResolver;
import com.wenshape.storage.BindingsStateStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingsControllerThresholdTest {

    @TempDir
    Path tempDir;

    @Test
    void genericAliasDoesNotBindWorldCardByItself() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.worldCards = Map.of(
                "合欢宗", WorldCard.builder()
                        .name("合欢宗")
                        .aliases(List.of("宗门", "合欢宗"))
                        .description("以双修闻名的宗门。")
                        .build()
        );
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        draftStorage.drafts.put("V1C1", "宗门规矩森严，众弟子不敢妄动，却始终没有点出具体门派名号。");
        draftStorage.chapters = List.of("V1C1");

        BindingsController controller = new BindingsController(
                cardStorage,
                draftStorage,
                new BindingsProperties(),
                resolver(cardStorage),
                stateStorage()
        );
        Map<String, Object> binding = controller.rebuildBindingsForChapter("p", "V1C1");

        @SuppressWarnings("unchecked")
        List<String> worldEntities = (List<String>) binding.get("world_entities");
        assertFalse(worldEntities.contains("合欢宗"), "仅凭“宗门”这种泛化别名，不应该把卡片强行绑定上去");
    }

    @Test
    void genericSuffixProperNounUsesLooserThresholdThanTrulyGenericAlias() throws Exception {
        TestCardStorage cardStorage = new TestCardStorage();
        BindingsController controller = new BindingsController(
                cardStorage,
                new TestDraftStorage(tempDir),
                new BindingsProperties(),
                resolver(cardStorage),
                stateStorage()
        );

        Method isGenericName = BindingsController.class.getDeclaredMethod("isGenericName", String.class);
        isGenericName.setAccessible(true);
        Method candidateThreshold = BindingsController.class.getDeclaredMethod("candidateThreshold", String.class, ProjectLexicon.class);
        candidateThreshold.setAccessible(true);
        ProjectLexicon lexicon = ProjectLexicon.empty();

        boolean plainGeneric = (boolean) isGenericName.invoke(controller, "宗门");
        boolean properNoun = (boolean) isGenericName.invoke(controller, "合欢宗");
        double genericThreshold = (double) candidateThreshold.invoke(controller, "宗门", lexicon);
        double properThreshold = (double) candidateThreshold.invoke(controller, "合欢宗", lexicon);

        assertTrue(plainGeneric, "“宗门”这类词应该被识别为泛称");
        assertFalse(properNoun, "“合欢宗”这种带专名的实体，不该再被当成纯泛称");
        assertTrue(genericThreshold > properThreshold, "泛称 alias 的 BM25 门槛应该高于具体专名");
    }

    @Test
    void customThresholdPropertiesActuallyAffectCandidateThreshold() throws Exception {
        TestCardStorage cardStorage = new TestCardStorage();
        BindingsController defaultController = new BindingsController(
                cardStorage,
                new TestDraftStorage(tempDir),
                new BindingsProperties(),
                resolver(cardStorage),
                stateStorage()
        );
        BindingsProperties properties = new BindingsProperties();
        properties.getThresholds().setBm25(0.70);
        properties.getThresholds().setBm25Generic(1.60);
        properties.getThresholds().setAliasMediumSpecificPenalty(0.08);
        properties.getThresholds().setAliasShortGenericPenalty(0.25);
        properties.getThresholds().setAliasGenericSingleTermPenalty(0.12);

        BindingsController controller = new BindingsController(
                cardStorage,
                new TestDraftStorage(tempDir),
                properties,
                resolver(cardStorage),
                stateStorage()
        );

        Method candidateThreshold = BindingsController.class.getDeclaredMethod("candidateThreshold", String.class, ProjectLexicon.class);
        candidateThreshold.setAccessible(true);
        ProjectLexicon lexicon = ProjectLexicon.empty();

        double defaultProperThreshold = (double) candidateThreshold.invoke(defaultController, "合欢宗", lexicon);
        double defaultGenericThreshold = (double) candidateThreshold.invoke(defaultController, "宗门", lexicon);
        double properThreshold = (double) candidateThreshold.invoke(controller, "合欢宗", lexicon);
        double genericThreshold = (double) candidateThreshold.invoke(controller, "宗门", lexicon);

        assertTrue(properThreshold < defaultProperThreshold, "降低具体实体 bm25 阈值后，专名门槛应同步下降");
        assertTrue(genericThreshold > defaultGenericThreshold, "抬高泛称 bm25 阈值后，泛称门槛应同步升高");
    }

    @Test
    void extractTermsCanSeeProjectLexiconWholePhrase() throws Exception {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.worldCards = Map.of(
                "白银审判骑士团", WorldCard.builder()
                        .name("白银审判骑士团")
                        .build()
        );
        ProjectLexiconResolver resolver = resolver(cardStorage);
        BindingsController controller = new BindingsController(
                cardStorage,
                new TestDraftStorage(tempDir),
                new BindingsProperties(),
                resolver,
                stateStorage()
        );

        Method extractTerms = BindingsController.class.getDeclaredMethod("extractTerms", String.class, ProjectLexicon.class);
        extractTerms.setAccessible(true);
        ProjectLexicon lexicon = resolver.resolve("p");

        @SuppressWarnings("unchecked")
        List<String> terms = (List<String>) extractTerms.invoke(controller, "白银审判骑士团", lexicon);

        assertTrue(terms.contains("白银审判骑士团"));
    }

    @Test
    void nonExactWorldMatchShouldNotBindBySingleGenericSuffixEvidence() {
        TestCardStorage cardStorage = new TestCardStorage();
        cardStorage.worldCards = Map.of(
                "杂务院", WorldCard.builder()
                        .name("杂务院")
                        .description("外门分派与报到地点。")
                        .build()
        );
        TestDraftStorage draftStorage = new TestDraftStorage(tempDir);
        draftStorage.drafts.put("V1C1", "院里已经乱作一团，院墙边全是人，谁也不知道前头出了什么事。");
        draftStorage.chapters = List.of("V1C1");

        BindingsController controller = new BindingsController(
                cardStorage,
                draftStorage,
                new BindingsProperties(),
                resolver(cardStorage),
                stateStorage()
        );

        Map<String, Object> binding = controller.rebuildBindingsForChapter("p", "V1C1");

        @SuppressWarnings("unchecked")
        List<String> worldEntities = (List<String>) binding.get("world_entities");
        assertFalse(worldEntities.contains("杂务院"), "不能仅凭“院里/院墙”这类后缀噪声把“杂务院”误绑进去");
    }

    private ProjectLexiconResolver resolver(CardStorage cardStorage) {
        ProjectLexiconResolver resolver = new ProjectLexiconResolver(cardStorage);
        ReflectionTestUtils.setField(resolver, "dataDir", tempDir.toString());
        return resolver;
    }

    private BindingsStateStorage stateStorage() {
        BindingsStateStorage storage = new BindingsStateStorage();
        ReflectionTestUtils.setField(storage, "dataDir", tempDir.toString());
        return storage;
    }

    private static class TestCardStorage extends CardStorage {
        Map<String, WorldCard> worldCards = new LinkedHashMap<>();

        @Override
        public List<String> listCharacterCards(String projectId) {
            return List.of();
        }

        @Override
        public List<String> listWorldCards(String projectId) {
            return worldCards.keySet().stream().toList();
        }

        @Override
        public Optional<WorldCard> getWorldCard(String projectId, String cardName) {
            return Optional.ofNullable(worldCards.get(cardName));
        }
    }

    private static class TestDraftStorage extends DraftStorage {
        private final Path root;
        private final Map<String, String> drafts = new LinkedHashMap<>();
        private List<String> chapters = List.of();

        private TestDraftStorage(Path root) {
            this.root = root;
        }

        @Override
        public Path getProjectPath(String projectId) {
            return root.resolve(projectId);
        }

        @Override
        public Optional<String> getFinalDraft(String projectId, String chapter) {
            return Optional.ofNullable(drafts.get(chapter));
        }

        @Override
        public List<String> listChapters(String projectId) {
            return chapters;
        }
    }
}
