package tools.dscode.engine.parity;

import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.adapter.cucumber.CucumberAdapterRun;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.source.StoreScenarioSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compares the Cucumber adapter and the native engine.
 * Status, step text, and a returned value when both sides recorded one.
 */
public final class ParityHarness {
    private ParityHarness() {
    }

    public static List<DualRun.Divergence> compare(String gherkin, Class<?> glue) throws Exception {
        List<DualRun.Divergence> found = new ArrayList<>(DualRun.compare(gherkin, glue));
        return List.copyOf(found);
    }

    public static List<DualRun.Divergence> compareFeature(Path feature, Class<?> glue) throws Exception {
        String text = Files.readString(feature);
        Path dir = Files.createTempDirectory("pkb-parity");
        Path copy = dir.resolve(feature.getFileName().toString());
        try {
            Files.writeString(copy, text);
            DualRun.begin(glue);
            RunResult adapter = CucumberAdapterRun.run(copy, glue.getPackageName());
            DualRun.reset();
            RunResult nativeResult = PickleballEngine.run(text, EngineOptions.of(glue).project(dir));
            List<DualRun.Divergence> found = new ArrayList<>(DualRun.diff(adapter, nativeResult));
            found.addAll(returned(adapter, nativeResult));
            return List.copyOf(found);
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Runs one hydrated feature from store rows on both engines.
     * The file may already be gone. Neither side scans a feature directory.
     */
    public static List<DualRun.Divergence> compareStored(Path project, String featurePath, Class<?> glue) throws Exception {
        StoreScenarioSource source = new StoreScenarioSource(project, featurePath);
        List<EngineFeature> features = source.load();
        DualRun.begin(glue);
        try {
            RunResult adapter = CucumberAdapterRun.run(features, glue == null ? null : glue.getPackageName());
            DualRun.reset();
            RunResult nativeResult = PickleballEngine.run(source, EngineOptions.of(glue).project(project));
            List<DualRun.Divergence> found = new ArrayList<>(DualRun.diff(adapter, nativeResult));
            found.addAll(returned(adapter, nativeResult));
            return List.copyOf(found);
        } finally {
            DualRun.reset();
        }
    }

    /** Lists feature files. It does not execute them. */
    public static List<Path> featuresUnder(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".feature")).sorted().toList();
        }
    }

    private static List<DualRun.Divergence> returned(RunResult adapter, RunResult nativeResult) {
        List<DualRun.Divergence> found = new ArrayList<>();
        int count = Math.min(adapter.scenarios().size(), nativeResult.scenarios().size());
        for (int index = 0; index < count; index++) {
            var left = adapter.scenarios().get(index);
            var right = nativeResult.scenarios().get(index);
            int steps = Math.min(left.steps().size(), right.steps().size());
            for (int step = 0; step < steps; step++) {
                String a = left.steps().get(step).returned();
                String b = right.steps().get(step).returned();
                if (a != null && !a.isBlank() && b != null && !b.isBlank() && !a.equals(b)) {
                    found.add(new DualRun.Divergence(right.name(), "returned", a, b));
                }
            }
        }
        return found;
    }
}
