package tools.dscode.engine.resolve;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.source.FileScenarioSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.testengine.PKB_props.PKB_EXAMPLE;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_LIMIT;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_ORDER;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

/**
 * Native selection versus CucumberScanUtil and ExampleRowFilter on the real feature trees.
 */
class ResolutionParityTest {
    @Test
    void componentServiceAndModularFeaturesSelectTheSameScenarios() {
        Path consumer = Path.of("maven-consumer-project/src/test/resources");
        Path repo = Path.of("src/test/resources");
        assertTrue(consumer.toFile().isDirectory());
        assertTrue(repo.toFile().isDirectory());
        List<String> problems = new ArrayList<>();
        for (Path root : List.of(consumer, repo)) {
            problems.addAll(compare(root, "@all", null, null, null, null, null, null));
            problems.addAll(compare(root, "@all", "save", null, null, null, "lexical", 4));
            problems.addAll(compare(root, null, "Scenario", null, null, null, "reverse", 3));
            problems.addAll(compare(root, "@all", null, null, "1", null, null, null));
            problems.addAll(compare(root, "@all", null, null, "2", null, "lexical", null));
            problems.addAll(compare(root, null, ".", null, null, "save", "lexical", null));
            problems.addAll(compare(root, "@all", null, null, null, null, "random:7", 6));
        }
        problems.addAll(compare(consumer, null, null, "Area B control flow", null, null, "lexical", null));
        assertTrue(problems.isEmpty(), String.join("\n", problems.subList(0, Math.min(problems.size(), 20))));
        assertThrows(IllegalArgumentException.class, () -> CucumberScanUtil.listPickles(props(consumer, "@no-such-tag-parity", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> ScenarioResolver.resolve(
                new FileScenarioSource(consumer),
                new ScenarioResolver.Query("@no-such-tag-parity", null, null, null, null, null, null)
        ));
        assertTrue(CucumberScanUtil.listPickles(props(consumer, null, null, null, null, null, null)).isEmpty());
        assertTrue(ScenarioResolver.resolve(new FileScenarioSource(consumer), new ScenarioResolver.Query(null, null, null, null, null, null, null)).isEmpty());
        assertFalse(problems.size() > 0);
    }

    private static List<String> compare(
            Path root,
            String tags,
            String name,
            String featureName,
            String example,
            String stepMarker,
            String order,
            Integer limit
    ) {
        String label = root.getFileName() + " tags=" + tags + " name=" + name + " feature=" + featureName
                + " example=" + example + " step=" + stepMarker + " order=" + order + " limit=" + limit;
        List<String> cucumber;
        List<String> nativeHits;
        try {
            cucumber = cucumber(root, tags, name, featureName, example, stepMarker, order, limit);
        } catch (RuntimeException failure) {
            try {
                ScenarioResolver.resolve(new FileScenarioSource(root), query(tags, name, featureName, example, stepMarker, order, limit));
                return List.of(label + " cucumber threw " + failure.getClass().getSimpleName() + " native did not");
            } catch (RuntimeException ignored) {
                return List.of();
            }
        }
        try {
            nativeHits = nativeSignatures(ScenarioResolver.resolve(
                    new FileScenarioSource(root),
                    query(tags, name, featureName, example, stepMarker, order, limit)
            ));
        } catch (RuntimeException failure) {
            return List.of(label + " native threw " + failure.getClass().getSimpleName() + " cucumber=" + cucumber.size());
        }
        if (!cucumber.equals(nativeHits)) {
            return List.of(label + " cucumber=" + cucumber.size() + " native=" + nativeHits.size()
                    + "\n cucumber " + head(cucumber) + "\n native " + head(nativeHits));
        }
        return List.of();
    }

    private static ScenarioResolver.Query query(
            String tags, String name, String featureName, String example, String stepMarker, String order, Integer limit
    ) {
        return new ScenarioResolver.Query(tags, name, featureName, example, stepMarker, order, limit);
    }

    private static List<String> cucumber(
            Path root, String tags, String name, String featureName, String example, String stepMarker, String order, Integer limit
    ) {
        boolean step = stepMarker != null && !stepMarker.isBlank();
        List<Pickle> pickles = CucumberScanUtil.listPickles(props(
                root, tags, name, featureName, example, order, step ? null : limit
        ));
        if (step) {
            pickles = new ArrayList<>(pickles);
            pickles.removeIf(pickle -> pickle.getSteps().stream().noneMatch(stepItem -> stepItem.getText().contains(stepMarker)));
            if (limit != null && limit >= 0 && pickles.size() > limit) {
                pickles = pickles.subList(0, limit);
            }
        }
        return cucumberSignatures(pickles);
    }

    private static Map<String, String> props(
            Path root, String tags, String name, String featureName, String example, String order, Integer limit
    ) {
        Map<String, String> props = new HashMap<>();
        props.put(FEATURES_PROPERTY_NAME, root.toAbsolutePath().toString());
        if (tags != null) props.put(PKB_TAGS, tags);
        if (name != null) props.put(PKB_NAME, name);
        if (featureName != null) props.put(PKB_FEATURE_NAME, featureName);
        if (example != null) props.put(PKB_EXAMPLE, example);
        if (order != null) props.put(PKB_ORDER, order);
        if (limit != null) props.put(PKB_LIMIT, Integer.toString(limit));
        return props;
    }

    private static List<String> cucumberSignatures(List<Pickle> pickles) {
        List<String> signatures = new ArrayList<>();
        for (Pickle pickle : pickles) {
            StringBuilder steps = new StringBuilder();
            pickle.getSteps().forEach(step -> steps.append('\n').append(step.getText()));
            signatures.add(pickle.getName() + "\t" + String.join(" ", pickle.getTags()) + steps);
        }
        return signatures;
    }

    private static List<String> nativeSignatures(List<ScenarioResolver.Hit> hits) {
        List<String> signatures = new ArrayList<>();
        for (ScenarioResolver.Hit hit : hits) {
            StringBuilder steps = new StringBuilder();
            hit.scenario().steps().forEach(step -> steps.append('\n').append(step.text()));
            signatures.add(hit.scenario().name() + "\t" + String.join(" ", hit.scenario().tags()) + steps);
        }
        return signatures;
    }

    private static String head(List<String> values) {
        if (values.isEmpty()) return "[]";
        String first = values.getFirst();
        int cut = Math.min(first.length(), 180);
        return values.size() + " first=[" + first.substring(0, cut) + "]";
    }
}
