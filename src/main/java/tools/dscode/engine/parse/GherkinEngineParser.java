package tools.dscode.engine.parse;

import tools.dscode.common.GlobalConstants;
import tools.dscode.engine.adapter.cucumber.CucumberGherkin;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ExampleTable;
import tools.dscode.engine.model.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Gherkin through the Cucumber adapter and converts to the neutral model.
 * Placeholders stay in the step text, matching ForceNullValuesRowAspect.
 * Outline rows, background steps, and Scenario Tags are applied by {@link #expand}.
 */
public final class GherkinEngineParser {
    private GherkinEngineParser() {
    }

    public static EngineFeature parse(String uri, String text) {
        return CucumberGherkin.parse(uri, text);
    }

    /** One scenario per Examples row. Step text is not interpolated. */
    public static EngineFeature expand(EngineFeature feature) {
        if (feature == null) return EngineFeature.of("", List.of());
        List<EngineScenario> expanded = new ArrayList<>();
        for (EngineScenario scenario : feature.scenarios()) {
            if (!hasRows(scenario)) {
                expanded.add(withBackground(feature, scenario, Map.of()));
                continue;
            }
            int tableIndex = 0;
            int overall = 0;
            for (ExampleTable table : scenario.examples()) {
                tableIndex++;
                int rowIndex = 0;
                for (List<String> row : table.rows()) {
                    rowIndex++;
                    overall++;
                    expanded.add(outline(feature, scenario, table, tableIndex, row, rowIndex, overall));
                }
            }
        }
        return new EngineFeature(
                feature.name(),
                feature.description(),
                feature.language(),
                feature.tags(),
                feature.background(),
                expanded,
                feature.location()
        );
    }

    private static boolean hasRows(EngineScenario scenario) {
        for (ExampleTable table : scenario.examples()) {
            if (!table.rows().isEmpty()) return true;
        }
        return false;
    }

    private static EngineScenario withBackground(EngineFeature feature, EngineScenario scenario, Map<String, String> extra) {
        List<EngineStep> steps = new ArrayList<>();
        for (EngineStep background : feature.background()) {
            steps.add(official(copyBackground(background)));
        }
        for (EngineStep step : scenario.steps()) {
            steps.add(official(step));
        }
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(scenario.metadata());
        metadata.putAll(extra);
        return new EngineScenario(
                scenario.keyword(),
                scenario.name(),
                scenario.description(),
                mergeTags(mergeTags(feature.tags(), ruleTags(scenario)), scenario.tags()),
                steps,
                List.of(),
                scenario.location(),
                metadata
        );
    }

    private static EngineScenario outline(
            EngineFeature feature,
            EngineScenario scenario,
            ExampleTable table,
            int tableIndex,
            List<String> row,
            int rowIndex,
            int overall
    ) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(scenario.metadata());
        metadata.put("exampleHeaders", joinCells(table.headers()));
        metadata.put("exampleValues", joinCells(row));
        metadata.put("exampleTable", Integer.toString(tableIndex));
        metadata.put("exampleRow", Integer.toString(rowIndex));
        metadata.put("exampleOverall", Integer.toString(overall));
        List<String> extraTags = new ArrayList<>(table.tags());
        extraTags.addAll(scenarioTags(table.headers(), row));
        EngineScenario base = withBackground(feature, scenario, metadata);
        return new EngineScenario(
                base.keyword(),
                interpolateName(base.name(), table.headers(), row),
                base.description(),
                mergeTags(base.tags(), extraTags),
                base.steps(),
                List.of(),
                new SourceLocation(base.location().uri(), table.location().line(), table.location().column()),
                metadata
        );
    }

    private static String joinCells(List<String> cells) {
        if (cells == null || cells.isEmpty()) return "";
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) joined.append('\t');
            String cell = cells.get(index);
            if (cell != null) joined.append(cell);
        }
        return joined.toString();
    }

    private static List<String> scenarioTags(List<String> headers, List<String> row) {
        int index = headers.indexOf("Scenario Tags");
        if (index < 0 || index >= row.size() || row.get(index) == null || row.get(index).isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String token : row.get(index).trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (token.startsWith("@")) tags.add(token);
            else if (token.startsWith(GlobalConstants.COMPONENT_TAG_META_CHAR)) tags.add("@" + token);
        }
        return tags;
    }

    private static EngineStep copyBackground(EngineStep step) {
        return new EngineStep(step.keyword(), step.text(), step.argument(), step.location(), step.metadata(), true);
    }

    /** Same substitution PickleCompiler uses for the pickle name. Step text stays unsubstituted. */
    private static String interpolateName(String name, List<String> headers, List<String> row) {
        String text = name == null ? "" : name;
        if (headers == null || row == null) return text;
        int count = Math.min(headers.size(), row.size());
        for (int index = 0; index < count; index++) {
            String header = headers.get(index);
            if (header == null) continue;
            String value = row.get(index);
            text = text.replace("<" + header + ">", value == null ? "" : value);
        }
        return text;
    }

    /**
     * Pickle step text after {@code PickleStepMetaTextAspect}: flag suffix becomes
     * meta text, an inline argument is split off, and escaped line breaks return.
     */
    private static EngineStep official(EngineStep step) {
        return CucumberGherkin.official(step);
    }

    private static List<String> ruleTags(EngineScenario scenario) {
        String raw = scenario.metadata().get("ruleTags");
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split(" "));
    }

    private static List<String> mergeTags(List<String> first, List<String> second) {
        List<String> tags = new ArrayList<>();
        addTags(tags, first);
        addTags(tags, second);
        return List.copyOf(tags);
    }

    private static void addTags(List<String> tags, List<String> more) {
        if (more == null) return;
        for (String tag : more) {
            if (tag != null && !tag.isBlank()) tags.add(tag);
        }
    }
}
