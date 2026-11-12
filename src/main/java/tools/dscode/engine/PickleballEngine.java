package tools.dscode.engine;

import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.StepKind;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.engine.runtime.NativeScenarioCatalog;
import tools.dscode.engine.source.MemoryScenarioSource;
import tools.dscode.engine.source.ScenarioSource;
import tools.dscode.engine.source.StepListScenarioSource;

import java.util.ArrayList;
import java.util.List;

/** Runs scenarios and steps without a Cucumber scenario. */
public final class PickleballEngine {
    private PickleballEngine() {
    }

    public static RunResult run(String text, EngineOptions options) {
        return run(new MemoryScenarioSource(text), options);
    }

    public static RunResult run(List<String> steps, EngineOptions options) {
        String name = options == null || options.scenarioName().isBlank() ? "runtime" : options.scenarioName();
        return run(new StepListScenarioSource(name, steps), options);
    }

    public static RunResult run(ScenarioSource source, EngineOptions options) {
        EngineOptions settings = options == null ? EngineOptions.of() : options;
        StepRegistry registry = new StepRegistry().logTo(settings.project());
        if (settings.glue() != null) registry.scan(settings.glue().toArray(Class<?>[]::new));
        registry.scanPackage("tools.dscode.coredefinitions");
        List<EngineFeature> loaded = source.load();
        NativeScenarioCatalog.bind(settings.project(), loaded);
        List<ScenarioResult> scenarios = new ArrayList<>();
        try {
            for (EngineFeature feature : loaded) {
                EngineFeature expanded = GherkinEngineParser.expand(feature);
                for (EngineScenario scenario : expanded.scenarios()) {
                    if (!settings.scenarioName().isBlank() && !scenario.name().contains(settings.scenarioName())) continue;
                    scenarios.add(runScenario(scenario, registry, settings));
                }
            }
        } finally {
            NativeScenarioCatalog.clear();
        }
        RunResult result = RunResult.of(scenarios);
        EngineLog.log(settings.project(), "run", source.id(), result.status() + " scenarios=" + scenarios.size());
        return result;
    }

    public static StepResult runStep(String text, String argument, ExecutionContext context) {
        EngineStep step = EngineStep.of("* ", text == null ? "" : text);
        if (argument != null && !argument.isBlank()) {
            step = new EngineStep(step.keyword(), step.text(), StepArgument.doc(DocText.of(argument)), step.location(), step.metadata(), false);
        }
        StepRegistry registry = registry(context);
        ScenarioResult scenario = tools.dscode.engine.runtime.NativeScenarioRun.run(
                EngineScenario.of("step", List.of(step)),
                registry,
                EngineOptions.of()
        );
        if (scenario.steps().isEmpty()) return finish(Status.UNDEFINED, step, "undefined", "", System.nanoTime());
        return scenario.steps().getFirst();
    }

    private static StepRegistry registry(ExecutionContext context) {
        if (context != null && context.resources().get("registry") instanceof StepRegistry existing) return existing;
        StepRegistry created = new StepRegistry().scanPackage("tools.dscode.coredefinitions");
        Object glue = context == null ? null : context.resources().get("glue");
        if (glue instanceof List<?> list) {
            List<Class<?>> types = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Class<?> type) types.add(type);
            }
            created.scan(types.toArray(Class<?>[]::new));
        }
        return created;
    }

    private static ScenarioResult runScenario(EngineScenario scenario, StepRegistry registry, EngineOptions options) {
        return tools.dscode.engine.runtime.NativeScenarioRun.run(scenario, registry, options);
    }

    private static StepResult finish(Status status, EngineStep step, String message, String returned, long started) {
        long millis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        String noted = message == null ? "" : message;
        String source = step.metadata().get("source");
        if (source != null && !source.isBlank()) {
            String mark = "source=" + source;
            noted = noted.isBlank() ? mark : noted + " " + mark;
        }
        return new StepResult(status, step.keyword(), step.text(), noted, millis, returned);
    }

    static String describe(StepKind kind) {
        return kind == null ? "" : kind.name();
    }
}
