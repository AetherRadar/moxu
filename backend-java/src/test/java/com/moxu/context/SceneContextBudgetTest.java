package com.moxu.context;

import com.moxu.model.entity.SceneBrief;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneContextBudgetTest {
    private final SceneBrief scene = SceneBrief.builder().chapter("V1C2").goal("Alice searches for a key")
            .characters(List.of(Map.of("name", "Alice"))).build();

    @Test void relevantLateMemorySurvivesWithoutPrefixBias() {
        String text = "Background about weather.\n".repeat(150) + "Alice keeps the key in her pocket.";
        var result = SceneContextBudget.select(text, scene, 65);
        assertTrue(result.text().contains("Alice keeps the key in her pocket."));
        assertTrue(result.text().length() <= 65);
    }

    @Test void negativeConstraintsWinAndAreNotCutInHalf() {
        var result = SceneContextBudget.select("Alice holds a key.\nThe key must not leave the room.\nOther history.",
                scene, 36);
        assertEquals("The key must not leave the room.", result.text());
        assertEquals(0, result.omittedConstraints());
    }

    @Test void oversizedConstraintsAreReportedInsteadOfSilentlyTruncated() {
        var result = SceneContextBudget.select("Alice must not " + "forget ".repeat(50), scene, 40);
        assertTrue(result.text().isEmpty());
        assertEquals(1, result.omittedConstraints());
    }

    @Test void keepsSourceOrderAndDoesNotSplitUnicodePairs() {
        String text = "Alice \uD83D\uDE00 finds key.\nLong unrelated background.".repeat(5);
        var result = SceneContextBudget.select(text, scene, 24);
        assertEquals("Alice \uD83D\uDE00 finds key.", result.text());
    }

    @Test void preservesConstraintSectionAndSourceLabelInsteadOfFlatteningIt() {
        var result = SceneContextBudget.select(
                "硬约束:\n- [V1C1] 每次开门消耗一枚钥匙。\n可用素材:\n- Alice looks at a key.\n- Weather is pleasant.",
                scene, 40);
        assertTrue(result.text().contains("硬约束:\n- [V1C1] 每次开门消耗一枚钥匙。"));
        assertEquals(0, result.omittedConstraints());
    }

    @Test void decimalAmountsRemainInOneSentence() {
        var result = SceneContextBudget.select("Alice has 13.04 coins.\n" + "Weather is pleasant.\n".repeat(10), scene, 25);
        assertEquals("Alice has 13.04 coins.", result.text());
    }

    @Test void oversizedSceneGoalIsNotSilentlyDroppedUnderItsHeading() {
        var result = SceneContextBudget.select("Scene Brief:\nGoal: " + "Very important goal ".repeat(100), scene, 40);
        assertEquals(1, result.omittedConstraints());
    }
}
