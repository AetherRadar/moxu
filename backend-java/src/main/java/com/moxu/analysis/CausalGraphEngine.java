package com.moxu.analysis;

import java.text.Normalizer;
import java.util.*;
import static com.moxu.analysis.CausalModel.*;

/** Deterministic event replay. Missing prerequisites are unknown, never automatically false. */
public final class CausalGraphEngine {
    public record Segment(Source source, int start, int end, Extraction extraction) {}
    private record State(Fact fact, String producer) {}

    public Graph build(List<Segment> segments) {
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        for (Segment segment : segments) for (Alias alias : segment.extraction.aliases()) {
            boolean subjective = segment.extraction.events().stream()
                    .anyMatch(e -> Set.of("dream", "hypothetical", "belief", "uncertain").contains(e.frame())
                            && (e.quote().contains(alias.quote()) || alias.quote().contains(e.quote())));
            if (subjective) continue;
            aliases.computeIfAbsent(normalize(alias.alias()), ignored -> new TreeSet<>()).add(normalize(alias.canonical()));
        }
        List<Node> nodes = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        Set<String> uncertainChapters = new LinkedHashSet<>();
        for (Segment segment : segments) {
            if (!"complete".equals(segment.extraction.coverage()) || !segment.extraction.uncertainties().isEmpty())
                uncertainChapters.add(segment.source.chapter());
            for (Event event : segment.extraction.events()) {
                Evidence citation = CausalExtractor.evidence(segment.source, segment.start, segment.end, event.quote());
                String identity = segment.source.chapter() + "|" + event.frame() + "|" + narrativeIdentity(event.narrative())
                        + "|" + claimIdentity(event.requires()) + "|" + claimIdentity(event.effects());
                String id = "event-" + AnalysisStorage.hash(identity + "|" + occurrences.merge(identity, 1, Integer::sum))
                        .substring(0, 24);
                List<Fact> required = facts(event.requires(), segment, aliases, uncertainChapters);
                List<Fact> effects = facts(event.effects(), segment, aliases, uncertainChapters);
                String frame = event.frame();
                if (ambiguousQuote(segment, event.quote())) {
                    uncertainChapters.add(segment.source.chapter());
                    frame = "uncertain";
                }
                nodes.add(new Node(id, segment.source.chapter(), event.label(), frame, citation, required, effects,
                        event.narrative()));
            }
        }
        nodes.sort(Comparator.comparing(Node::chapter, ContinuityService::compare).thenComparingInt(n -> n.evidence().start()));
        return replay(nodes, uncertainChapters);
    }

    public Graph replay(List<Node> input, Collection<String> uncertain) {
        List<Node> nodes = new ArrayList<>(input);
        Set<String> uncertainChapters = new LinkedHashSet<>(uncertain);
        List<Node> objective = nodes.stream().filter(n -> Set.of("main", "flashback").contains(n.frame())
                && narrative(n).perspective().isBlank()).toList();
        boolean dated = !objective.isEmpty() && objective.stream().allMatch(n -> !narrative(n).time().isBlank());
        Set<String> ambiguousDays = new HashSet<>();
        if (dated) {
            Map<String, Integer> dateCounts = new HashMap<>();
            objective.forEach(n -> dateCounts.merge(narrative(n).time(), 1, Integer::sum));
            for (Node node : objective) if ("flashback".equals(node.frame())
                    && dateCounts.getOrDefault(narrative(node).time(), 0) > 1)
                ambiguousDays.add(narrative(node).time());
            // Keep narrative order within each day; equal-day flashbacks remain explicitly uncertain.
            nodes.sort(Comparator.comparing(n -> narrative(n).time().isBlank() ? "9999-12-31" : narrative(n).time()));
        }
        Map<String, State> state = new LinkedHashMap<>();
        Set<String> tainted = new HashSet<>();
        List<Link> edges = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Node node : nodes) {
            Narrative narrative = narrative(node);
            boolean subjective = Set.of("dream", "hypothetical", "belief").contains(node.frame())
                    || !narrative.perspective().isBlank();
            if ("uncertain".equals(node.frame()) || ("flashback".equals(node.frame()) && !dated)
                    || (dated && ambiguousDays.contains(narrative.time()))) {
                uncertainChapters.add(node.chapter());
                continue;
            }
            if (subjective && (narrative.scope().isBlank() || narrative.perspective().isBlank())) {
                uncertainChapters.add(node.chapter());
                continue;
            }
            String scope = subjective ? node.chapter() + "|" + node.frame() + "|" + narrative.scope()
                    + "|" + normalize(narrative.perspective()) + "|" : "";
            for (Fact fact : node.requires()) {
                String key = scope + key(fact);
                State prior = state.get(key);
                if (prior != null) edges.add(new Link(prior.producer, node.id(), "requires", key));
                if (prior == null || !prior.fact.value().equals(fact.value()) || fact.inferred()
                        || fact.confidence() < 0.8 || prior.fact.confidence() < 0.8 || tainted.contains(prior.producer)) {
                    String kind = prior == null ? "missing_precondition"
                            : fact.inferred() || fact.confidence() < 0.8 || prior.fact.inferred()
                            || prior.fact.confidence() < 0.8 || tainted.contains(prior.producer)
                            ? "uncertain_dependency" : "state_mismatch";
                    candidates.add(candidate(node, fact, prior, kind));
                    tainted.add(node.id());
                }
            }
            for (Fact fact : node.effects()) {
                String key = scope + key(fact);
                State prior = state.get(key);
                if (prior != null) edges.add(new Link(prior.producer, node.id(), "state_transition", key));
                if (fact.inferred() || fact.confidence() < 0.8) {
                    candidates.add(candidate(node, fact, prior, "uncertain_effect"));
                    uncertainChapters.add(node.chapter());
                    // Keep the uncertain producer so later dependencies cannot reuse a stale clean state.
                    tainted.add(node.id());
                    state.put(key, new State(fact, node.id()));
                    continue;
                }
                if (prior != null && fact.property().equals("alive") && prior.fact.value().equals("false")
                        && fact.value().equals("true")) {
                    candidates.add(candidate(node, fact, prior, tainted.contains(prior.producer)
                            ? "uncertain_dependency" : "resurrection_or_conflict"));
                    tainted.add(node.id());
                }
                state.put(key, new State(fact, node.id()));
            }
        }
        Map<String, Candidate> unique = new LinkedHashMap<>();
        candidates.forEach(c -> unique.put(c.id(), c));
        return new Graph(nodes, edges.stream().distinct().toList(), new ArrayList<>(unique.values()), new ArrayList<>(uncertainChapters));
    }

    private static Narrative narrative(Node node) {
        return node.narrative() == null ? Narrative.forward() : node.narrative();
    }

    private static Object narrativeIdentity(Narrative narrative) {
        return narrative == null ? List.of("", "", "")
                : List.of(narrative.time(), narrative.perspective(), narrative.scope());
    }

    private static List<String> claimIdentity(List<Claim> claims) {
        return claims.stream().map(c -> normalize(c.entity()) + "\u001f" + normalize(c.property())
                + "\u001f" + normalize(c.value())).sorted().toList();
    }

    private static List<String> semantics(List<Fact> facts) {
        return facts.stream().map(f -> key(f) + "\u001f" + f.value() + "|" + f.inferred()
                + "|" + (f.confidence() >= 0.8)).sorted().toList();
    }

    private static Object semantics(Node node) {
        return List.of(node.frame(), narrativeIdentity(narrative(node)), semantics(node.requires()), semantics(node.effects()));
    }

    private static List<Fact> facts(List<Claim> claims, Segment segment, Map<String, Set<String>> aliases, Set<String> uncertain) {
        List<Fact> result = new ArrayList<>();
        for (Claim claim : claims) {
            String entity = resolve(claim.entity(), aliases);
            boolean ambiguous = entity == null;
            if (entity == null) {
                uncertain.add(segment.source.chapter());
                // Ambiguous identities are isolated to this chapter; never merge them across chapters.
                entity = segment.source.chapter() + ":ambiguous:" + normalize(claim.entity());
            }
            String property = normalize(claim.property());
            String value = normalize(claim.value());
            if (Set.of("owner", "location").contains(property)) {
                String resolved = resolve(value, aliases);
                if (resolved != null) value = resolved;
                else {
                    uncertain.add(segment.source.chapter());
                    ambiguous = true;
                }
            }
            result.add(new Fact(entity, property, value, CausalExtractor.evidence(segment.source, segment.start, segment.end, claim.quote()),
                    claim.confidence(), claim.inferred() || ambiguous || ambiguousQuote(segment, claim.quote())));
        }
        return result;
    }
    private static String resolve(String input, Map<String, Set<String>> aliases) {
        String name = normalize(input);
        Set<String> visited = new HashSet<>();
        while (aliases.containsKey(name)) {
            Set<String> targets = aliases.get(name);
            if (targets.size() != 1) return null;
            String next = targets.iterator().next();
            if (next.equals(name)) return name;
            if (!visited.add(name)) return null;
            name = next;
        }
        return name;
    }
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static String key(Fact fact) { return fact.entity() + "\u001f" + fact.property(); }
    private static boolean ambiguousQuote(Segment segment, String quote) {
        String text = segment.source.text().substring(segment.start, segment.end);
        int first = text.indexOf(quote);
        return text.indexOf(quote, first + 1) >= 0;
    }
    private static Candidate candidate(Node node, Fact fact, State previous, String kind) {
        String priorId = previous == null ? "" : previous.producer;
        return new Candidate("finding-" + AnalysisStorage.hash(node.id() + "|" + priorId + "|" + key(fact)
                + "|" + fact.value() + "|" + kind).substring(0, 24), kind, node.id(), priorId, key(fact),
                fact.value(), previous == null ? "" : previous.fact.value(),
                previous == null ? null : previous.fact.evidence(), fact.evidence());
    }

    /** Compare replayed inputs, not evidence offsets. Equal clean state stops propagation. */
    public Set<String> impacted(Graph previous, Graph current) {
        Set<String> oldIds = new LinkedHashSet<>(), newIds = new LinkedHashSet<>();
        Map<String, Node> oldNodes = new HashMap<>();
        if (previous != null) previous.nodes().forEach(n -> { oldIds.add(n.id()); oldNodes.put(n.id(), n); });
        current.nodes().forEach(n -> newIds.add(n.id()));
        Set<String> changed = new LinkedHashSet<>(oldIds);
        changed.removeAll(newIds);
        newIds.stream().filter(id -> !oldIds.contains(id)).forEach(changed::add);
        Map<String, Object> oldInputs = inputs(previous), newInputs = inputs(current);
        for (Node node : current.nodes()) {
            Node old = oldNodes.get(node.id());
            if (old == null || !semantics(node).equals(semantics(old))
                    || !Objects.equals(oldInputs.get(node.id()), newInputs.get(node.id())))
                changed.add(node.id());
        }
        return changed;
    }

    private static Map<String, Object> inputs(Graph graph) {
        if (graph == null) return Map.of();
        Map<String, Node> nodes = new HashMap<>();
        graph.nodes().forEach(n -> nodes.put(n.id(), n));
        Map<String, List<String>> signatures = new HashMap<>();
        Set<String> tainted = new HashSet<>();
        graph.candidates().forEach(c -> {
            tainted.add(c.nodeId());
            signatures.computeIfAbsent(c.nodeId(), ignored -> new ArrayList<>())
                    .add("finding:" + c.kind() + "|" + c.key() + "|" + c.expected() + "|" + c.actual());
        });
        for (Link edge : graph.edges()) {
            if (!"requires".equals(edge.kind())) continue;
            Node producer = nodes.get(edge.from());
            if (producer == null) continue;
            List<Fact> values = producer.effects().stream()
                    .filter(f -> edge.key().equals(key(f)) || edge.key().endsWith("|" + key(f))).toList();
            signatures.computeIfAbsent(edge.to(), ignored -> new ArrayList<>())
                    .add(edge.key() + "|" + semantics(values) + "|" + tainted.contains(producer.id()));
        }
        Map<String, Object> result = new HashMap<>();
        for (Node node : graph.nodes()) {
            List<String> values = signatures.getOrDefault(node.id(), List.of()).stream().sorted().toList();
            result.put(node.id(), List.of(values, graph.uncertainChapters().contains(node.chapter())));
        }
        return result;
    }
}
