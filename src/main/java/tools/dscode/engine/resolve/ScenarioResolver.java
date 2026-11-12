package tools.dscode.engine.resolve;

import io.cucumber.tagexpressions.TagExpressionParser;
import tools.dscode.control.protocol.ExampleRowSelector;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.engine.source.ScenarioSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Scenario lookup with the same filter order as CucumberScanUtil and
 * ExampleRowFilter: tags, name, feature name, then example rows, then order
 * and limit. Step-marker text is an extra native filter.
 */
public final class ScenarioResolver {
    private ScenarioResolver() {
    }

    public record Query(
            String tags,
            String name,
            String featureName,
            String example,
            String stepMarker,
            String order,
            Integer limit
    ) {
        public static Query of(String tags, String name, String featureName, String example) {
            return new Query(tags, name, featureName, example, null, null, null);
        }
    }

    public record Hit(String featureName, EngineScenario scenario) {
    }

    public static List<Hit> resolve(ScenarioSource source, Query query) {
        Query asked = query == null ? new Query(null, null, null, null, null, null, null) : query;
        if (asked.example() != null && !asked.example().isBlank()) {
            ExampleRowSelector.parse(asked.example());
        }
        if (!hasFilter(asked)) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (EngineFeature feature : source.load()) {
            EngineFeature expanded = GherkinEngineParser.expand(feature);
            for (EngineScenario scenario : expanded.scenarios()) {
                if (!accepts(feature, scenario, asked)) continue;
                hits.add(new Hit(feature.name(), scenario));
            }
        }
        if (asked.example() != null && !asked.example().isBlank()) {
            ExampleRowSelector selector = ExampleRowSelector.parse(asked.example());
            hits.removeIf(hit -> !selector.matches(overall(hit.scenario()), table(hit.scenario()), row(hit.scenario())));
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("No scenarios matched the provided filters");
        }
        order(hits, asked.order());
        if (asked.limit() != null && asked.limit() >= 0 && hits.size() > asked.limit()) {
            return List.copyOf(hits.subList(0, asked.limit()));
        }
        return List.copyOf(hits);
    }

    private static boolean hasFilter(Query query) {
        return text(query.tags()) || text(query.name()) || text(query.featureName())
                || text(query.example()) || text(query.stepMarker());
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean accepts(EngineFeature feature, EngineScenario scenario, Query query) {
        if (text(query.featureName()) && !query.featureName().equals(feature.name())) return false;
        if (text(query.tags()) && !TagExpressionParser.parse(query.tags().trim()).evaluate(scenario.tags())) return false;
        if (text(query.name()) && !Pattern.compile(query.name().trim()).matcher(scenario.name()).find()) return false;
        if (text(query.stepMarker())) {
            boolean found = false;
            for (EngineStep step : scenario.steps()) {
                if (step.text().contains(query.stepMarker())) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static int overall(EngineScenario scenario) {
        return number(scenario, "exampleOverall", 1);
    }

    private static int table(EngineScenario scenario) {
        return number(scenario, "exampleTable", 1);
    }

    private static int row(EngineScenario scenario) {
        return number(scenario, "exampleRow", 1);
    }

    private static int number(EngineScenario scenario, String key, int fallback) {
        String raw = scenario.metadata().get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static void order(List<Hit> hits, String order) {
        if (order == null || order.isBlank()) return;
        String mode = order.trim();
        if (mode.toLowerCase().startsWith("random")) {
            int split = mode.indexOf(':');
            long seed = split < 0 ? 1L : Long.parseLong(mode.substring(split + 1).trim());
            Collections.shuffle(hits, new Random(seed));
            return;
        }
        hits.sort(Comparator
                .comparing((Hit hit) -> hit.scenario().location().uri())
                .thenComparingInt(hit -> hit.scenario().location().line())
                .thenComparing(hit -> hit.scenario().name()));
        if ("reverse".equalsIgnoreCase(mode)) Collections.reverse(hits);
    }
}
