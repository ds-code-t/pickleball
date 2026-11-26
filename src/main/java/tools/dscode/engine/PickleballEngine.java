package tools.dscode.engine;

import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHosts;
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
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.engine.source.MemoryScenarioSource;
import tools.dscode.engine.source.ScenarioSource;
import tools.dscode.engine.source.StepListScenarioSource;
import tools.dscode.engine.source.StoreScenarioSource;
import tools.dscode.store.StoreConfig;
import tools.dscode.store.StorePaths;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.engine.adapter.cucumber.CucumberLane;
import tools.dscode.engine.adapter.playwright.PlaywrightScenarioRun;
import tools.dscode.engine.adapter.playwright.PlaywrightTestIngest;
import tools.dscode.testengine.PickleballRunner;

import java.nio.file.Path;
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
        if (settings.glue() != null) {
            registry.scan(settings.glue().toArray(Class<?>[]::new));
            for (Class<?> type : settings.glue()) {
                if (type != null && type.getPackageName() != null && !type.getPackageName().isBlank()) {
                    registry.scanPackage(type.getPackageName());
                }
            }
        }
        registry.scanPackage("tools.dscode.coredefinitions");
        List<EngineFeature> loaded = select(source, settings).load();
        NativeScenarioCatalog.bind(settings.project(), loaded);
        List<ScenarioResult> scenarios = new ArrayList<>();
        boolean topLevel = ExecutionContext.current() == null;
        if (topLevel) ForeignStepHosts.begin(settings.project());
        Object parked = CucumberLane.park(topLevel);
        // Block conditionals log at DEBUG. The adapter uses that level so a
        // surrounding suite that lowered the level still records the same lines.
        Level previousLevel = PickleballRunner.LOG_LEVEL;
        PickleballRunner.LOG_LEVEL = Level.DEBUG;
        tools.dscode.store.StoreExecution.RunLease runLease = null;
        try {
            runLease = tools.dscode.store.StoreExecution.RunLease.open(topLevel);
            for (EngineFeature feature : loaded) {
                EngineFeature expanded = playwright(feature) ? feature : GherkinEngineParser.expand(feature);
                for (EngineScenario scenario : expanded.scenarios()) {
                    if (!settings.scenarioName().isBlank() && !scenario.name().contains(settings.scenarioName())) continue;
                    scenarios.add(runScenario(scenario, registry, settings));
                }
            }
        } finally {
            if (runLease != null) runLease.close();
            PickleballRunner.LOG_LEVEL = previousLevel;
            NativeScenarioCatalog.clear();
            CucumberLane.restore(topLevel, parked);
            if (topLevel) ForeignStepHosts.end();
        }
        RunResult result = RunResult.of(scenarios);
        EngineLog.log(settings.project(), "run", source.id(), result.status() + " scenarios=" + scenarios.size());
        return result;
    }

    public static StepResult runStep(String text, String argument, ExecutionContext context) {
        boolean ownHosts = !ForeignStepHosts.started();
        if (ownHosts) ForeignStepHosts.begin(projectOf(context));
        try {
            EngineStep step = EngineStep.of("* ", text == null ? "" : text);
            if (argument != null && !argument.isBlank()) {
                step = new EngineStep(step.keyword(), step.text(), StepArgument.doc(DocText.of(argument)), step.location(), step.metadata(), false);
            }
            StepRegistry registry = registry(context);
            ScenarioResult scenario = tools.dscode.engine.adapter.cucumber.NativeScenarioRun.run(
                    EngineScenario.of("step", List.of(step)),
                    registry,
                    EngineOptions.of()
            );
            if (scenario.steps().isEmpty()) return finish(Status.UNDEFINED, step, "undefined", "", System.nanoTime());
            return scenario.steps().getFirst();
        } finally {
            if (ownHosts) ForeignStepHosts.end();
        }
    }

    private static Path projectOf(ExecutionContext context) {
        if (context != null && context.resources().get("project") instanceof Path path) {
            return path.toAbsolutePath().normalize();
        }
        Path current = ForeignStepHosts.project();
        if (current != null) return current;
        return Path.of("").toAbsolutePath().normalize();
    }

    private static StepRegistry registry(ExecutionContext context) {
        if (context != null && context.resources().get("registry") instanceof StepRegistry existing) return existing;
        StepRegistry created = new StepRegistry().scanPackage("tools.dscode.coredefinitions");
        if (context != null && context.resources().get("project") instanceof Path path) created.logTo(path);
        if (context != null && context.resources().get("definition") instanceof String id && !id.isBlank()) {
            created.prefer(id);
        }
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

    /**
     * Store on reads {@link StoreScenarioSource}. An in-memory source stays in
     * memory. {@code pkb_store=false} keeps {@link FileScenarioSource}.
     */
    private static ScenarioSource select(ScenarioSource source, EngineOptions settings) {
        if (!(source instanceof FileScenarioSource file)) return source;
        Path project = settings.project();
        if (project == null || !StoreConfig.enabled(project)) return source;
        if (file.path() == null) return source;
        return new StoreScenarioSource(project, StorePaths.display(project, file.path()));
    }

    private static boolean playwright(EngineFeature feature) {
        for (EngineScenario scenario : feature.scenarios()) {
            if (PlaywrightTestIngest.KEYWORD.equals(scenario.keyword())) return true;
        }
        return false;
    }

    private static ScenarioResult runScenario(EngineScenario scenario, StepRegistry registry, EngineOptions options) {
        if (PlaywrightTestIngest.KEYWORD.equals(scenario.keyword())) {
            return PlaywrightScenarioRun.run(scenario);
        }
        return tools.dscode.engine.adapter.cucumber.NativeScenarioRun.run(scenario, registry, options);
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
