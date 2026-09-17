package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Derived execution list for the live player. The editor document stays the
 * feature file; Play sends these substituted step texts through executeStep.
 */
public final class GherkinPlayPlan {
    public record Step(long sourceLineId, String sourceText, String executeText) {
        public Step {
            sourceText = sourceText == null ? "" : sourceText;
            executeText = executeText == null ? "" : executeText;
        }
    }

    private final List<Step> steps;

    public GherkinPlayPlan(List<Step> steps) {
        this.steps = List.copyOf(steps == null ? List.of() : steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public static GherkinPlayPlan from(LiveScenarioPlayer player, ScenarioOrigin origin) {
        Objects.requireNonNull(player, "player");
        ScenarioOrigin target = origin == null ? ScenarioOrigin.none() : origin;
        List<LiveScenarioPlayer.Line> lines = player.lines();
        if (target.file() == null || target.scenarioName().isBlank()) {
            return allExecutable(lines);
        }
        Region feature = findFeature(lines);
        Region scenario = findScenario(lines, target.scenarioName(), target.startLine());
        if (scenario == null) {
            return allExecutable(lines);
        }
        List<LiveScenarioPlayer.Line> background = backgroundSteps(lines, feature, scenario);
        List<LiveScenarioPlayer.Line> body = scenarioSteps(lines, scenario);
        Map<String, String> substitutions = exampleSubstitutions(lines, scenario, target.exampleRow());
        List<Step> planned = new ArrayList<>();
        for (LiveScenarioPlayer.Line line : background) {
            planned.add(new Step(line.id(), line.text(), substitute(line.text(), substitutions)));
        }
        for (LiveScenarioPlayer.Line line : body) {
            planned.add(new Step(line.id(), line.text(), substitute(line.text(), substitutions)));
        }
        return new GherkinPlayPlan(planned);
    }

    static GherkinPlayPlan allExecutable(List<LiveScenarioPlayer.Line> lines) {
        List<Step> planned = new ArrayList<>();
        for (LiveScenarioPlayer.Line line : lines) {
            if (line.executable()) {
                planned.add(new Step(line.id(), line.text(), line.text()));
            }
        }
        return new GherkinPlayPlan(planned);
    }

    private static List<LiveScenarioPlayer.Line> backgroundSteps(
            List<LiveScenarioPlayer.Line> lines,
            Region feature,
            Region scenario
    ) {
        int from = feature == null ? 0 : feature.start;
        int to = scenario.start;
        int backgroundStart = -1;
        for (int i = from; i < to && i < lines.size(); i++) {
            String trimmed = lines.get(i).text().strip();
            if (startsWithKeyword(trimmed, "Rule:")) {
                backgroundStart = -1;
            } else if (startsWithKeyword(trimmed, "Background:")) {
                backgroundStart = i + 1;
            }
        }
        if (backgroundStart < 0) return List.of();
        return collectSteps(lines, backgroundStart, to);
    }

    private static List<LiveScenarioPlayer.Line> scenarioSteps(
            List<LiveScenarioPlayer.Line> lines,
            Region scenario
    ) {
        int bodyStart = scenario.start + 1;
        int bodyEnd = scenario.end;
        for (int i = scenario.start + 1; i < scenario.end && i < lines.size(); i++) {
            String trimmed = lines.get(i).text().strip();
            if (startsWithKeyword(trimmed, "Examples:") || startsWithKeyword(trimmed, "Example:")) {
                bodyEnd = i;
                break;
            }
        }
        return collectSteps(lines, bodyStart, bodyEnd);
    }

    private static List<LiveScenarioPlayer.Line> collectSteps(
            List<LiveScenarioPlayer.Line> lines,
            int start,
            int end
    ) {
        List<LiveScenarioPlayer.Line> steps = new ArrayList<>();
        for (int i = start; i < end && i < lines.size(); i++) {
            if (lines.get(i).executable()) steps.add(lines.get(i));
        }
        return steps;
    }

    private static Map<String, String> exampleSubstitutions(
            List<LiveScenarioPlayer.Line> lines,
            Region scenario,
            int exampleRow
    ) {
        if (exampleRow <= 0) return Map.of();
        List<String> header = null;
        int dataIndex = 0;
        for (int i = scenario.start; i < scenario.end && i < lines.size(); i++) {
            String trimmed = lines.get(i).text().strip();
            if (!trimmed.startsWith("|")) continue;
            List<String> cells = tableCells(trimmed);
            if (header == null) {
                header = cells;
                continue;
            }
            dataIndex++;
            if (dataIndex == exampleRow) {
                LinkedHashMap<String, String> map = new LinkedHashMap<>();
                int n = Math.min(header.size(), cells.size());
                for (int c = 0; c < n; c++) {
                    map.put(header.get(c), cells.get(c));
                }
                return map;
            }
        }
        return Map.of();
    }

    static String substitute(String text, Map<String, String> substitutions) {
        if (text == null || substitutions == null || substitutions.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return result;
    }

    static List<String> tableCells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static Region findFeature(List<LiveScenarioPlayer.Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (startsWithKeyword(lines.get(i).text().strip(), "Feature:")) {
                return new Region(i, lines.size());
            }
        }
        return null;
    }

    private static Region findScenario(List<LiveScenarioPlayer.Line> lines, String name, int startLineHint) {
        int hinted = startLineHint > 0 ? startLineHint - 1 : -1;
        if (hinted >= 0 && hinted < lines.size() && isScenarioHeader(lines.get(hinted).text().strip())) {
            return regionFrom(lines, hinted);
        }
        String wanted = name.strip();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).text().strip();
            if (!isScenarioHeader(trimmed)) continue;
            if (scenarioTitle(trimmed).equalsIgnoreCase(wanted)) {
                return regionFrom(lines, i);
            }
        }
        return null;
    }

    private static Region regionFrom(List<LiveScenarioPlayer.Line> lines, int start) {
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).text().strip();
            if (isScenarioHeader(trimmed) || startsWithKeyword(trimmed, "Rule:")) {
                end = i;
                break;
            }
        }
        return new Region(start, end);
    }

    static boolean isScenarioHeader(String trimmed) {
        return startsWithKeyword(trimmed, "Scenario:")
                || startsWithKeyword(trimmed, "Scenario Outline:")
                || startsWithKeyword(trimmed, "Scenario Template:");
    }

    static String scenarioTitle(String trimmed) {
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(colon + 1).strip();
    }

    static boolean startsWithKeyword(String trimmed, String keyword) {
        return trimmed.startsWith(keyword);
    }

    private record Region(int start, int end) { }
}