package tools.dscode.engine.parity;

import tools.dscode.coredefinitions.GeneralSteps;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.adapter.CucumberAdapterRun;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.StepResult;
import tools.dscode.internal.ValuesSupport;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Runs one feature on the Cucumber adapter and the native engine and lists differences. */
public final class DualRun {
    public record Divergence(String scenario, String field, String adapter, String nativeEngine) {
    }

    private static Map<String, BiFunction<tools.dscode.Values.Context, String, tools.dscode.PrefixValue>> BASELINE;

    private DualRun() {
    }

    /**
     * Loads glue so static prefix registrations exist, then remembers those
     * prefixes. Each engine starts from that baseline. A prefix registered
     * while one engine runs is removed before the next engine.
     */
    public static void begin(Class<?> glue) {
        Map<String, BiFunction<tools.dscode.Values.Context, String, tools.dscode.PrefixValue>> before =
                ValuesSupport.prefixSnapshot();
        warm(glue);
        Map<String, BiFunction<tools.dscode.Values.Context, String, tools.dscode.PrefixValue>> after =
                ValuesSupport.prefixSnapshot();
        if (BASELINE == null) {
            BASELINE = new HashMap<>(after);
        } else {
            after.forEach((key, value) -> {
                if (!before.containsKey(key)) BASELINE.put(key, value);
            });
        }
        reset();
    }

    public static void reset() {
        GeneralSteps.resetRegisteredThrowables();
        if (BASELINE != null) ValuesSupport.restorePrefixes(BASELINE);
    }

    private static void warm(Class<?> glue) {
        if (glue == null) return;
        ClassLoader loader = glue.getClassLoader();
        String resource = glue.getPackageName().replace('.', '/');
        try {
            Enumeration<URL> urls = loader.getResources(resource);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (!"file".equalsIgnoreCase(url.getProtocol())) continue;
                Path dir = Path.of(url.toURI());
                if (!Files.isDirectory(dir)) continue;
                try (var files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".class") || name.contains("$")) continue;
                        try {
                            Class.forName(
                                    glue.getPackageName() + "." + name.substring(0, name.length() - 6),
                                    true,
                                    loader
                            );
                        } catch (Throwable ignored) {
                            // One glue class failing to initialize must not skip the rest.
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // A glue class that fails to initialize is reported by the engine.
        }
    }

    public static List<Divergence> compare(String gherkin, Class<?> glue) throws Exception {
        Path dir = Files.createTempDirectory("pkb-dual-run");
        Path feature = dir.resolve("dual.feature");
        try {
            Files.writeString(feature, gherkin);
            begin(glue);
            RunResult adapter = CucumberAdapterRun.run(feature, glue.getPackageName());
            reset();
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
                    found.add(new Divergence(name, "step-status", a.status().name() + note(a), b.status().name() + note(b)));
                }
            }
        }
        return List.copyOf(found);
    }

    private static String note(StepResult step) {
        if (step == null || step.message() == null || step.message().isBlank()) return "";
        String text = step.message().replace('\n', ' ').replace('\r', ' ');
        if (text.length() > 400) text = text.substring(0, 400);
        return " " + text;
    }
}
