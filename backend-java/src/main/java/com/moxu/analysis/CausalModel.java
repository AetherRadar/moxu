package com.moxu.analysis;

import java.util.List;

/** Evidence offsets are UTF-16 positions in the exact, hashed manuscript revision. */
public final class CausalModel {
    private CausalModel() {}
    public record Source(String chapter, String text, String hash) {}
    public record Evidence(String chapter, String sourceHash, int start, int end, String quote) {}
    public record Claim(String entity, String property, String value, String quote, double confidence, boolean inferred) {}
    public record Alias(String alias, String canonical, String quote) {}
    public record Narrative(String time, String timeQuote, String perspective, String scope) {
        public static Narrative forward() { return new Narrative("", "", "", ""); }
    }
    public record Event(String label, String frame, String quote, List<Claim> requires, List<Claim> effects,
                        Narrative narrative) {
        public Event(String label, String frame, String quote, List<Claim> requires, List<Claim> effects) {
            this(label, frame, quote, requires, effects, Narrative.forward());
        }
    }
    public record Extraction(String coverage, List<Alias> aliases, List<Event> events, List<String> uncertainties) {}
    public record Fact(String entity, String property, String value, Evidence evidence, double confidence, boolean inferred) {}
    public record Node(String id, String chapter, String label, String frame, Evidence evidence,
                       List<Fact> requires, List<Fact> effects, Narrative narrative) {}
    public record Link(String from, String to, String kind, String key) {}
    public record Candidate(String id, String kind, String nodeId, String priorNodeId, String key,
                            String expected, String actual, Evidence prior, Evidence current) {}
    public record Graph(List<Node> nodes, List<Link> edges, List<Candidate> candidates, List<String> uncertainChapters) {}
    public record Verdict(String verdict, String explanation, String priorQuote, String currentQuote, double confidence) {}
}
