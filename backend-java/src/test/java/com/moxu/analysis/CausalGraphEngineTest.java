package com.moxu.analysis;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.moxu.analysis.CausalModel.*;
import static org.junit.jupiter.api.Assertions.*;

class CausalGraphEngineTest {
    private final CausalGraphEngine engine = new CausalGraphEngine();
    private Claim claim(String property, String value, String quote) {
        return new Claim("Alice", property, value, quote, 0.95, false);
    }
    private CausalGraphEngine.Segment segment(String chapter, String text, Event... events) {
        return new CausalGraphEngine.Segment(new Source(chapter, text, AnalysisStorage.hash(text)), 0, text.length(),
                new Extraction("complete", List.of(), List.of(events), List.of()));
    }
    private Event event(String quote, List<Claim> requires, List<Claim> effects) {
        return new Event(quote, "main", quote, requires, effects);
    }
    @Test void travelAndTransfersAreTransitionsNotContradictions() {
        var graph = engine.build(List.of(
                segment("V1C1", "At home", event("At home", List.of(), List.of(claim("location", "home", "At home")))),
                segment("V1C2", "Walked to school", event("Walked to school",
                        List.of(claim("location", "home", "Walked to school")),
                        List.of(claim("location", "school", "Walked to school"))))));
        assertTrue(graph.candidates().isEmpty());
        assertEquals(2, graph.edges().size());
    }
    @Test void incompatibleSupportedPrerequisiteCreatesEvidenceBackedCandidate() {
        var graph = engine.build(List.of(
                segment("V1C1", "Alice died", event("Alice died", List.of(), List.of(claim("alive", "false", "Alice died")))),
                segment("V1C100", "Alice lives", event("Alice lives", List.of(claim("alive", "true", "Alice lives")), List.of()))));
        var finding = graph.candidates().getFirst();
        assertEquals("state_mismatch", finding.kind());
        assertEquals("V1C1", finding.prior().chapter());
        assertEquals("V1C100", finding.current().chapter());
    }
    @Test void resurrectionIsOnlyASuspicionUntilVerified() {
        var graph = engine.build(List.of(
                segment("V1C1", "Alice died", event("Alice died", List.of(), List.of(claim("alive", "false", "Alice died")))),
                segment("V1C2", "Alice revived", event("Alice revived", List.of(claim("alive", "false", "Alice revived")),
                        List.of(claim("alive", "true", "Alice revived"))))));
        assertEquals("resurrection_or_conflict", graph.candidates().getFirst().kind());
    }
    @Test void missingKnowledgeIsUnknownNotProofOfConflict() {
        var graph = engine.build(List.of(segment("V1C4", "Used password",
                event("Used password", List.of(claim("knows:password", "true", "Used password")), List.of()))));
        assertEquals("missing_precondition", graph.candidates().getFirst().kind());
        assertNull(graph.candidates().getFirst().prior());
    }
    @Test void flashbacksDoNotOverwritePresentState() {
        var graph = engine.build(List.of(
                segment("V1C1", "Alice died", event("Alice died", List.of(), List.of(claim("alive", "false", "Alice died")))),
                segment("V1C2", "Years ago alive", new Event("memory", "flashback", "Years ago alive", List.of(),
                        List.of(claim("alive", "true", "Years ago alive")))),
                segment("V1C3", "Alice lives", event("Alice lives", List.of(claim("alive", "true", "Alice lives")), List.of()))));
        assertEquals(List.of("V1C2"), graph.uncertainChapters());
        assertEquals("V1C1", graph.candidates().getFirst().prior().chapter());
    }
    @Test void uncertainAssignmentsTaintLaterDependenciesInsteadOfReusingOldState() {
        var graph = engine.build(List.of(
                segment("V1C1", "Home", event("Home", List.of(), List.of(claim("location", "home", "Home")))),
                segment("V1C2", "Maybe away", event("Maybe away", List.of(),
                        List.of(new Claim("Alice", "location", "away", "Maybe away", 0.5, false)))),
                segment("V1C3", "At home", event("At home", List.of(claim("location", "home", "At home")),
                        List.of(claim("knows:secret", "true", "At home")))),
                segment("V1C4", "Secret used", event("Secret used", List.of(claim("knows:secret", "true", "Secret used")), List.of()))));
        assertEquals(List.of("uncertain_effect", "uncertain_dependency", "uncertain_dependency"),
                graph.candidates().stream().map(Candidate::kind).toList());
    }
    @Test void removedProducerPropagatesThroughOldGraphAcrossMultipleHops() {
        var a = segment("V1C1", "Learned", event("Learned", List.of(), List.of(claim("knows:secret", "true", "Learned"))));
        var b = segment("V1C2", "Opened", event("Opened", List.of(claim("knows:secret", "true", "Opened")),
                List.of(claim("resource:key", "1", "Opened"))));
        var c = segment("V1C200", "Unlocked", event("Unlocked", List.of(claim("resource:key", "1", "Unlocked")), List.of()));
        Graph old = engine.build(List.of(a, b, c)), next = engine.build(List.of(b, c));
        assertEquals(3, engine.impacted(old, next).size());
        assertEquals("missing_precondition", next.candidates().getFirst().kind());
        assertEquals("uncertain_dependency", next.candidates().getLast().kind());
    }
    @Test void aliasChangesInvalidateUnchangedNodeSemanticsAndAmbiguityIsIsolated() {
        var scene = segment("V1C2", "Alice home", event("Alice home", List.of(), List.of(claim("location", "home", "Alice home"))));
        var source = new Source("V1C1", "Identity", "hash");
        var one = new CausalGraphEngine.Segment(source, 0, 8,
                new Extraction("complete", List.of(new Alias("Alice", "A", "Identity")), List.of(), List.of()));
        var two = new CausalGraphEngine.Segment(source, 0, 8,
                new Extraction("complete", List.of(new Alias("Alice", "B", "Identity")), List.of(), List.of()));
        Graph old = engine.build(List.of(one, scene)), next = engine.build(List.of(two, scene));
        assertEquals(old.nodes().getFirst().id(), next.nodes().getFirst().id());
        assertEquals(1, engine.impacted(old, next).size());
        Graph ambiguous = engine.build(List.of(one, two, scene));
        assertTrue(ambiguous.uncertainChapters().contains("V1C2"));
        assertTrue(ambiguous.nodes().getFirst().effects().getFirst().inferred());
    }
    @Test void repeatedQuotesAreNotConfidentlyOrdered() {
        var graph = engine.build(List.of(segment("V1C1", "Home Home",
                event("Home", List.of(), List.of(claim("location", "home", "Home"))))));
        assertEquals("uncertain", graph.nodes().getFirst().frame());
        assertEquals(List.of("V1C1"), graph.uncertainChapters());
    }
    @Test void earlyEditPropagatesThroughTwoHundredChaptersWithoutTouchingUnrelatedBranches() {
        List<CausalGraphEngine.Segment> segments = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            String quote = "Step " + i;
            segments.add(segment("V1C" + i, quote, event(quote,
                    i == 1 ? List.of() : List.of(claim("resource:chain", Integer.toString(i - 1), quote)),
                    List.of(claim("resource:chain", Integer.toString(i), quote)))));
        }
        segments.add(segment("V2C1", "Unrelated home", event("Unrelated home", List.of(),
                List.of(claim("location", "home", "Unrelated home")))));
        Graph before = engine.build(segments);
        segments.set(3, segment("V1C4", "Edited step 4", event("Edited step 4",
                List.of(claim("resource:chain", "3", "Edited step 4")),
                List.of(claim("resource:chain", "400", "Edited step 4")))));
        Graph after = engine.build(segments);
        Set<String> impacted = engine.impacted(before, after);
        assertTrue(impacted.contains(after.nodes().get(199).id()));
        assertFalse(impacted.contains(after.nodes().get(200).id()));
        assertFalse(impacted.contains(after.nodes().getFirst().id()));
        assertEquals(198, impacted.size(), "197 current descendants plus the removed old V1C4 node");
    }

    @Test void proseOnlyEditsRelocateEvidenceWithoutInvalidatingEvents() {
        Event event = event("At home", List.of(), List.of(claim("location", "home", "At home")));
        Graph old = engine.build(List.of(segment("V1C1", "At home", event)));
        Graph next = engine.build(List.of(segment("V1C1", "New introductory prose. At home", event)));
        assertEquals(old.nodes().getFirst().id(), next.nodes().getFirst().id());
        assertNotEquals(old.nodes().getFirst().evidence(), next.nodes().getFirst().evidence());
        assertTrue(engine.impacted(old, next).isEmpty());
    }

    @Test void cleanIndependentOverwriteStopsPropagationButDependentEffectsDoNot() {
        var a = segment("V1C1", "Home", event("Home", List.of(), List.of(claim("location", "home", "Home"))));
        var b = segment("V1C2", "School", event("School", List.of(), List.of(claim("location", "school", "School"))));
        var c = segment("V1C200", "Studies", event("Studies", List.of(claim("location", "school", "Studies")), List.of()));
        Graph old = engine.build(List.of(a, b, c));
        Graph next = engine.build(List.of(
                segment("V1C1", "Away", event("Away", List.of(), List.of(claim("location", "away", "Away")))), b, c));
        assertEquals(2, engine.impacted(old, next).size());
        assertFalse(engine.impacted(old, next).contains(next.nodes().getLast().id()));
    }

    @Test void semanticParaphraseKeepsIdentityAndNoopDoesNotPropagate() {
        Graph old = engine.build(List.of(segment("V1C1", "Home", event("Home", List.of(),
                List.of(claim("location", "home", "Home"))))));
        Graph next = engine.build(List.of(segment("V1C1", "Still in the house", event("Still in the house", List.of(),
                List.of(claim("location", "home", "Still in the house"))))));
        assertTrue(engine.impacted(old, next).isEmpty());
    }

    @Test void explicitHistoricalDateReplaysBeforePresentWithoutResurrectingAnyone() {
        var present = new Event("dead", "main", "2001-01-02 died", List.of(),
                List.of(claim("alive", "false", "2001-01-02 died")),
                new Narrative("2001-01-02", "2001-01-02", "", ""));
        var past = new Event("alive", "flashback", "2001-01-01 alive", List.of(),
                List.of(claim("alive", "true", "2001-01-01 alive")),
                new Narrative("2001-01-01", "2001-01-01", "", ""));
        Graph graph = engine.build(List.of(segment("V1C1", present.quote(), present), segment("V1C2", past.quote(), past)));
        assertTrue(graph.candidates().isEmpty());
        assertTrue(graph.uncertainChapters().isEmpty());
        assertEquals("V1C2", graph.nodes().getFirst().chapter());
    }

    @Test void sameDayFlashbackCannotInventWithinDayOrder() {
        var first = new Event("dead", "main", "2001-01-01 died", List.of(),
                List.of(claim("alive", "false", "2001-01-01 died")),
                new Narrative("2001-01-01", "2001-01-01", "", ""));
        var past = new Event("alive", "flashback", "2001-01-01 alive", List.of(),
                List.of(claim("alive", "true", "2001-01-01 alive")),
                new Narrative("2001-01-01", "2001-01-01", "", ""));
        Graph graph = engine.build(List.of(segment("V1C1", first.quote(), first), segment("V1C2", past.quote(), past)));
        assertEquals(2, graph.uncertainChapters().size());
        assertTrue(graph.candidates().isEmpty());
    }

    @Test void dreamAndBeliefScopesCannotChangeObjectiveStateOrEachOther() {
        var dream = new Event("dream", "dream", "Dream home", List.of(),
                List.of(claim("location", "home", "Dream home")), new Narrative("", "", "Alice", "Dream"));
        var belief = new Event("belief", "belief", "Bob thinks away", List.of(),
                List.of(claim("location", "away", "Bob thinks away")), new Narrative("", "", "Bob", "Bob thinks"));
        Graph graph = engine.build(List.of(
                segment("V1C1", "At school", event("At school", List.of(), List.of(claim("location", "school", "At school")))),
                segment("V1C2", dream.quote(), dream), segment("V1C3", belief.quote(), belief),
                segment("V1C4", "Studies", event("Studies", List.of(claim("location", "school", "Studies")), List.of()))));
        assertTrue(graph.candidates().isEmpty());
        assertTrue(graph.uncertainChapters().isEmpty());
        assertEquals(graph.nodes().getFirst().id(), graph.edges().getLast().from());
    }
}
