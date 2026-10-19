package tools.dscode.control.protocol;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

/**
 * Read-only parity between {@link WorkbenchFeatureIndex} and {@link CucumberScanUtil#listPickles}.
 * {@code CucumberScanUtil} is not modified.
 */
class WorkbenchFeatureIndexParityTest {
    @Test
    void everyConsumerFeatureMatchesCucumberScan() throws Exception {
        Path project = Path.of("maven-consumer-project").toAbsolutePath().normalize();
        assertEquals(true, Files.isDirectory(project.resolve("src/test/resources")), "consumer project missing");
        WorkbenchFeatureIndex.Selection selection = WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project,
                List.of(),
                "not @__no_such_tag__",
                null,
                null,
                List.of(),
                true
        ));
        assertFalse(selection.fullScanFallback(), selection.fallbackReason());
        Map<String, List<WorkbenchFeatureIndex.Hit>> byFile = new HashMap<>();
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            byFile.computeIfAbsent(hit.relativePath(), ignored -> new ArrayList<>()).add(hit);
        }
        List<Path> features = featureFiles(project);
        assertFalse(features.isEmpty());
        List<String> mismatches = new ArrayList<>();
        for (Path feature : features) {
            String relative = project.relativize(feature).toString().replace('\\', '/');
            List<Pickle> pickles = scan(feature);
            List<String> expected = new ArrayList<>();
            for (Pickle pickle : pickles) expected.add(canonical(pickle));
            expected.sort(Comparator.naturalOrder());
            List<String> actual = new ArrayList<>();
            for (WorkbenchFeatureIndex.Hit hit : byFile.getOrDefault(relative, List.of())) actual.add(canonical(hit));
            actual.sort(Comparator.naturalOrder());
            if (!expected.equals(actual)) {
                mismatches.add(relative + "\n  cucumber=" + expected + "\n  index=" + actual);
            }
        }
        if (!mismatches.isEmpty()) {
            Path report = Path.of("build/parity-mismatches.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, String.join("\n\n", mismatches));
        }
        assertEquals(0, mismatches.size(), mismatches.isEmpty()
                ? ""
                : mismatches.size() + " files differ; first is " + mismatches.getFirst().lines().findFirst().orElse(""));
    }

    private static List<Pickle> scan(Path feature) {
        CucumberScanUtil.clearCache();
        try {
            return CucumberScanUtil.listPickles(Map.of(
                    PKB_FEATURES, feature.toAbsolutePath().normalize().toString(),
                    PKB_TAGS, "not @__no_such_tag__"
            ));
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("No scenarios matched")) {
                return List.of();
            }
            throw failure;
        }
    }

    private static String canonical(Pickle pickle) {
        List<String> tags = new ArrayList<>();
        for (String tag : pickle.getTags()) {
            String value = tag;
            while (value.startsWith("@")) value = value.substring(1);
            if (!value.isEmpty()) tags.add(value);
        }
        return pickle.getScenarioLocation().getLine()
                + "|" + pickle.getLocation().getLine()
                + "|" + pickle.getName()
                + "|" + String.join(",", tags);
    }

    private static String canonical(WorkbenchFeatureIndex.Hit hit) {
        String name = hit.scenarioName();
        for (int i = 0; i < hit.headers().size() && i < hit.values().size(); i++) {
            name = name.replace("<" + hit.headers().get(i) + ">", hit.values().get(i));
        }
        return hit.startLine()
                + "|" + hit.line()
                + "|" + name
                + "|" + String.join(",", hit.tags());
    }

    private static List<Path> featureFiles(Path project) throws IOException {
        try (var walk = Files.walk(project.resolve("src/test/resources"))) {
            return walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".feature"))
                    .sorted()
                    .toList();
        }
    }
}
