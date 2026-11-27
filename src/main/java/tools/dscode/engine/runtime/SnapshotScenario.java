package tools.dscode.engine.runtime;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import tools.dscode.Values;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.engine.adapter.cucumber.NativeScenarioRun;
import tools.dscode.engine.adapter.cucumber.SharedStepRun;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.parse.GherkinEngineParser;
import tools.dscode.store.StoreExecution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * One scenario inside the cold snapshot JVM. The first step opens it. Later
 * steps reuse the same glue instances and scenario objects. {@link #close()}
 * ends it. The parent still receives one JSON result per step.
 */
final class SnapshotScenario {
    private final Path project;
    private final StepRegistry registry;
    private final CurrentScenarioState state;
    private final ExecutionContext context;
    private final Set<String> scanned = new HashSet<>();
    private boolean closed;

    private SnapshotScenario(Path project, StepRegistry registry, CurrentScenarioState state, ExecutionContext context) {
        this.project = project;
        this.registry = registry;
        this.state = state;
        this.context = context;
    }

    static SnapshotScenario open(Path project, String className) throws Exception {
        Path root = project == null || project.toString().isBlank()
                ? Path.of("").toAbsolutePath().normalize()
                : project.toAbsolutePath().normalize();
        if (ExecutionContext.current() == null && CurrentScenarioState.currentScenarioState.get() == null) {
            MappingProcessor.resetCommonMaps();
        }
        Values.clearScenarioObjects();
        StepRegistry registry = new StepRegistry().logTo(root);
        registry.scanPackage("tools.dscode.coredefinitions");
        EngineScenario scenario = EngineScenario.of("cold", List.of());
        CurrentScenarioState state = new CurrentScenarioState("cold", "cold");
        CurrentScenarioState.currentScenarioState.set(state);
        GlobalState.attachCachingGlue(state);
        GlobalState.installNativeTestCase(state);
        ForeignStepHosts.begin(root);
        ExecutionContext context = null;
        try {
            context = ExecutionContext.open(scenario);
            context.phase(ExecutionContext.Phase.RUNNING);
            context.resource("registry", registry);
            context.resource("instances", new IdentityHashMap<Class<?>, Object>());
            context.resource("project", root);
            context.attachLegacy(state);
            state.scenarioLog = Entry.of("cold").tag("Scenario").start();
            context.resource("tags", scenario.tags());
            SnapshotScenario session = new SnapshotScenario(root, registry, state, context);
            session.include(className);
            registry.fire("before", scenario.tags(), SharedStepRun.instances(context));
            return session;
        } catch (Throwable thrown) {
            CurrentScenarioState.currentScenarioState.remove();
            Values.clearScenarioObjects();
            SharedStepRun.forget();
            if (context != null) context.close();
            ForeignStepHosts.end();
            if (thrown instanceof Exception exception) throw exception;
            if (thrown instanceof Error error) throw error;
            throw new IllegalStateException(thrown);
        }
    }

    void include(String className) throws ClassNotFoundException {
        if (className == null || className.isBlank() || !scanned.add(className)) return;
        Class<?> type = Class.forName(className);
        registry.scan(type);
        String packageName = type.getPackageName();
        if (packageName != null && !packageName.isBlank() && scanned.add("package:" + packageName)) {
            registry.scanPackage(packageName);
        }
    }

    Outcome step(String text, String gherkin) {
        EngineStep engineStep = engineStep(text, gherkin);
        StepExtension frame = NativeScenarioRun.frame(engineStep, registry, state);
        context.pushStep(engineStep);
        try {
            StoreExecution.beforeStep(project, "cold", "", 0, engineStep.text(), line(engineStep));
            VisualTarget.beforeRun(project, "", line(engineStep), engineStep.text(), registry);
            state.runStep(frame);
        } catch (Throwable thrown) {
            context.fail(thrown);
            if (frame.result == null) {
                frame.result = new Result(io.cucumber.plugin.event.Status.FAILED, Duration.ZERO, thrown);
            }
        } finally {
            try {
                StoreExecution.afterStep(statusName(frame));
            } catch (RuntimeException ignored) {
                // A tracking write must not change the step the parent is waiting for.
            }
            context.popStep();
        }
        return outcome(frame);
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            registry.fire("after", List.of(), SharedStepRun.instances(context));
        } catch (RuntimeException ignored) {
            context.fail(ignored);
        }
        try {
            StepExtension cleanupStep = state.getCurrentStep();
            tools.dscode.common.control.ControlExecutionScope.withStep(cleanupStep, () -> {
                state.scenarioRunCleanUp();
                return null;
            });
        } catch (Throwable thrown) {
            state.recordHardFailure(thrown);
            context.fail(thrown);
        }
        try {
            if (state.scenarioLog != null) state.scenarioLog.stop();
        } catch (RuntimeException ignored) {
            // The scenario result was already captured on each step.
        }
        context.phase(ExecutionContext.Phase.CLEANUP);
        Values.clearScenarioObjects();
        CurrentScenarioState.currentScenarioState.remove();
        SharedStepRun.forget();
        try {
            context.close();
        } finally {
            ForeignStepHosts.end();
        }
    }

    private static EngineStep engineStep(String text, String gherkin) {
        if (gherkin != null && !gherkin.isBlank()) {
            EngineFeature feature = GherkinEngineParser.expand(GherkinEngineParser.parse("memory:cold.feature", gherkin));
            if (!feature.scenarios().isEmpty() && !feature.scenarios().getFirst().steps().isEmpty()) {
                return feature.scenarios().getFirst().steps().getFirst();
            }
        }
        return EngineStep.of("* ", text == null ? "" : text);
    }

    private static int line(EngineStep step) {
        return step.location() == null ? 0 : step.location().line();
    }

    private static Outcome outcome(StepExtension frame) {
        String status = statusName(frame);
        boolean ok = "PASSED".equals(status) || "SKIPPED".equals(status);
        String returned = frame.nativeReturned == null ? "" : String.valueOf(frame.nativeReturned);
        String error = "";
        if (!ok) {
            Throwable thrown = frame.result == null ? null : frame.result.getError();
            if (thrown == null) {
                error = status;
            } else {
                error = thrown.getMessage() == null ? thrown.toString() : thrown.getMessage();
            }
        }
        return new Outcome(ok, status, returned, error);
    }

    private static String statusName(StepExtension frame) {
        if (frame.result == null || frame.result.getStatus() == null) return "SKIPPED";
        return frame.result.getStatus().name();
    }

    record Outcome(boolean ok, String status, String returned, String error) {
    }
}
