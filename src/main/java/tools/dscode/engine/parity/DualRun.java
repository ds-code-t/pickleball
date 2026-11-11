package tools.dscode.engine.parity;

import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.adapter.CucumberAdapterRun;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.StepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs one feature on the Cucumber adapter and the native engine and lists differences. */
public final class DualRun {
    public record Divergence(String scenario, String field, String adapter, String nativeEngine) {
    }

    private DualRun() {
    }

    public static List<Divergence> compare(String gherkin, Class<?> glue) throws Exception {
        Path dir = Files.createTempDirectory("pkb-dual-run");
        Path feature = dir.resolve("dual.feature");
        try {
            Files.writeString(feature, gherkin);
            RunResult adapter = CucumberAdapterRun.run(feature, glue.getPackageName());
            RunResult nativeResult = PickleballEngine.run(gherkin, EngineOptions.of(glue));
            return diff(adapter, nativeResult);
        } finally {
            Files.deleteIfExists(feature);
            Files.deleteIfExists(dir);
        }
    }

    public static List<Divergence> diff(RunResult adapter, RunResult nativeResult) {
        List<Divergence> found = new ArrayList<>();
        int count = Math.max(adapter.scenarios().size(), nativeResult.scenarios().size());
        for (int index = 0; index < count; index++) {
            ScenarioResult left = index < adapter.scenarios().size() ? adapter.scenarios().get(index) : null;
            ScenarioResult right = index < nativeResult.scenarios().size() ? nativeResult.scenarios().get(index) : null;
            String name = right == null ? (left == null ? "" : left.name()) : right.name();
            if (left == null || right == null) {
                found.add(new Divergence(name, "scenario", left == null ? "missing" : left.status().name(), right == null ? "missing" : right.status().name()));
                continue;
            }
            if (!left.name().equals(right.name())) {
                found.add(new Divergence(name, "name", left.name(), right.name()));
            }
            if (left.status() != right.status()) {
                found.add(new Divergence(name, "status", left.status().name(), right.status().name()));
            }
            int steps = Math.max(left.steps().size(), right.steps().size());
            for (int step = 0; step < steps; step++) {
                StepResult a = step < left.steps().size() ? left.steps().get(step) : null;
                StepResult b = step < right.steps().size() ? right.steps().get(step) : null;
                if (a == null || b == null) {
                    found.add(new Divergence(name, "step", a == null ? "missing" : a.text(), b == null ? "missing" : b.text()));
                    continue;
                }
                if (!a.text().equals(b.text())) {
                    found.add(new Divergence(name, "step-text", a.text(), b.text()));
                }
                if (a.status() != b.status()) {
                    found.add(new Divergence(name, "step-status", a.status().name(), b.status().name()));
                }
            }
        }
        return List.copyOf(found);
    }
}
