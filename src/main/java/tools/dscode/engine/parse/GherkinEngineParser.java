package tools.dscode.engine.parse;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.ParseError;
import tools.dscode.common.GlobalConstants;
import tools.dscode.engine.convert.CucumberModelConverter;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ExampleTable;
import tools.dscode.engine.model.SourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Gherkin with {@link GherkinParser} and converts to the neutral model.
 * Placeholders stay in the step text, matching ForceNullValuesRowAspect.
 * Outline rows, background steps, and Scenario Tags are applied by {@link #expand}.
 */
public final class GherkinEngineParser {
    private GherkinEngineParser() {
    }

    public static EngineFeature parse(String uri, String text) {
        String sourceUri = uri == null || uri.isBlank() ? "memory:" : uri;
        String rewritten = EncodingRewrite.apply(text);
        GherkinParser parser = GherkinParser.builder()
                .includeSource(false)
                .includePickles(false)
                .includeGherkinDocument(true)
                .build();
        List<String> errors = new ArrayList<>();
        EngineFeature feature = null;
        for (Envelope envelope : parser.parse(sourceUri, rewritten.getBytes(StandardCharsets.UTF_8)).toList()) {
            envelope.getParseError().map(ParseError::getMessage).ifPresent(errors::add);
            if (envelope.getGherkinDocument().isPresent()) {
                feature = CucumberModelConverter.fromDocument(envelope.getGherkinDocument().get());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return feature == null ? EngineFeature.of("", List.of()) : feature;
    }

    /** One scenario per Examples row. Step text is not interpolated. */
    public static EngineFeature expand(EngineFeature feature) {
        if (feature == null) return EngineFeature.of("", List.of());
        List<EngineScenario> expanded = new ArrayList<>();
        int overall = 0;
        for (EngineScenario scenario : feature.scenarios()) {
            if (!hasRows(scenario)) {
                expanded.add(withBackground(feature, scenario, Map.of()));
                continue;
            }
            int tableIndex = 0;
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
            steps.add(copyBackground(background));
        }
        steps.addAll(scenario.steps());
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(scenario.metadata());
        metadata.putAll(extra);
        return new EngineScenario(
                scenario.keyword(),
                scenario.name(),
                scenario.description(),
                mergeTags(feature.tags(), scenario.tags(), List.of()),
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
        metadata.put("exampleHeaders", String.join("\t", table.headers()));
        metadata.put("exampleValues", String.join("\t", row));
        metadata.put("exampleTable", Integer.toString(tableIndex));
        metadata.put("exampleRow", Integer.toString(rowIndex));
        metadata.put("exampleOverall", Integer.toString(overall));
        List<String> extraTags = new ArrayList<>(table.tags());
        extraTags.addAll(scenarioTags(table.headers(), row));
        EngineScenario base = withBackground(feature, scenario, metadata);
        return new EngineScenario(
                base.keyword(),
                base.name(),
                base.description(),
                mergeTags(base.tags(), extraTags, List.of()),
                base.steps(),
                List.of(),
                new SourceLocation(base.location().uri(), table.location().line(), table.location().column()),
                metadata
        );
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

    private static List<String> mergeTags(List<String> first, List<String> second, List<String> third) {
        List<String> tags = new ArrayList<>();
        addTags(tags, first);
        addTags(tags, second);
        addTags(tags, third);
        return List.copyOf(tags);
    }

    private static void addTags(List<String> tags, List<String> more) {
        if (more == null) return;
        for (String tag : more) {
            if (tag != null && !tag.isBlank() && !tags.contains(tag)) tags.add(tag);
        }
    }
}
