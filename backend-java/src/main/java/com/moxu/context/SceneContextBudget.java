package com.moxu.context;

import com.moxu.model.entity.SceneBrief;
import com.moxu.search.TextSearchUtils;
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Pattern;

/** Select complete source units by scene relevance; never summarize or remove negation. */
public final class SceneContextBudget {
    private SceneContextBudget() {}
    private static final Pattern CONSTRAINT = Pattern.compile(
            "硬约束|禁忌|不得|不能|禁止|尚未|未解决|待确认|不再|已删除|未接|死亡|受伤|hard constraints|world constraints|forbidden|must not|unresolved|deleted|missed|(?:^|\\n)Goal:",
            Pattern.CASE_INSENSITIVE);
    private record Unit(int index, String text, double score, boolean protectedUnit) {}
    public record Selection(String text, int omittedUnits, int omittedConstraints) {}

    public static Selection select(String text, SceneBrief scene, int budget) {
        if (text == null || text.isBlank() || budget <= 0) return new Selection("", 0, 0);
        if (text.length() <= budget) return new Selection(text, 0, 0);
        String query = Objects.toString(scene.getGoal(), "") + " " + Objects.toString(scene.getTitle(), "")
                + " " + Objects.toString(scene.getTimelineContext(), "");
        List<String> names = scene.getCharacters() == null ? List.of() : scene.getCharacters().stream()
                .map(c -> c.get("name")).filter(Objects::nonNull).filter(n -> !n.isBlank()).toList();
        query += " " + String.join(" ", names);
        var scorer = TextSearchUtils.scorer(query, null);
        // Keep a paragraph intact where possible; split only at explicit sentence boundaries.
        List<String> chunks = units(text);
        var idf = scorer.buildIdfTable(chunks);
        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            boolean critical = CONSTRAINT.matcher(chunk).find();
            double score = scorer.score(chunk, idf) + (names.stream().anyMatch(chunk::contains) ? 3 : 0);
            units.add(new Unit(i, chunk, score, critical));
        }
        units.sort(Comparator.comparing(Unit::protectedUnit).reversed()
                .thenComparing(Comparator.comparingDouble(Unit::score).reversed()).thenComparingInt(Unit::index));
        List<Unit> chosen = new ArrayList<>();
        int used = 0;
        for (Unit unit : units) {
            if (used + unit.text.length() + (chosen.isEmpty() ? 0 : 1) > budget) continue;
            chosen.add(unit);
            used += unit.text.length() + (chosen.size() == 1 ? 0 : 1);
        }
        chosen.sort(Comparator.comparingInt(Unit::index));
        Set<Integer> selected = new HashSet<>();
        chosen.forEach(u -> selected.add(u.index));
        int omittedConstraints = (int) units.stream().filter(u -> u.protectedUnit && !selected.contains(u.index)).count();
        return new Selection(String.join("\n", chosen.stream().map(Unit::text).toList()),
                units.size() - chosen.size(), omittedConstraints);
    }

    private static List<String> units(String text) {
        Set<String> result = new LinkedHashSet<>();
        String heading = "";
        for (String raw : text.split("\\R+")) {
            String line = raw.strip();
            if (line.isBlank()) continue;
            if (!line.startsWith("-") && (line.endsWith(":") || line.endsWith("："))) {
                heading = line;
                continue;
            }
            String prefix = heading.isEmpty() ? "" : heading + "\n";
            // Bullet records keep their source labels and conditions together.
            if (line.startsWith("-")) {
                result.add(prefix + line);
                continue;
            }
            BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
            iterator.setText(line);
            for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
                 start = end, end = iterator.next())
                result.add(prefix + line.substring(start, end).strip());
        }
        return new ArrayList<>(result);
    }
}
