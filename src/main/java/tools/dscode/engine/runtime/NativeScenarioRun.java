package tools.dscode.engine.runtime;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.common.GlobalConstants;
import tools.dscode.engine.model.StepResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/** Runs one neutral scenario on the shared step lifecycle. */
public final class NativeScenarioRun {
    private NativeScenarioRun() {
    }

    public static ScenarioResult run(EngineScenario scenario, StepRegistry registry, EngineOptions options) {
        if (ExecutionContext.current() == null && CurrentScenarioState.currentScenarioState.get() == null) {
            MappingProcessor.resetCommonMaps();
        }
        List<StepResult> results = new ArrayList<>();
        try (ExecutionContext context = ExecutionContext.open(scenario)) {
            context.phase(ExecutionContext.Phase.RUNNING);
            context.resource("registry", registry);
            context.resource("instances", new IdentityHashMap<Class<?>, Object>());
            CurrentScenarioState state = new CurrentScenarioState(scenario.name(), scenario.name());
            context.attachLegacy(state);
            installExamples(state, scenario);
            state.scenarioLog = Entry.of(scenario.name()).tag("Scenario").start();
            context.resource("tags", scenario.tags());
            ScenarioStep root = new ScenarioStep(scenario.name());
            context.rootStep(root);
            List<Prepared> queue = new ArrayList<>();
            linkTree(prepare(scenario, registry, state), queue);
            Throwable hookFailure = null;
            StepExtension previous = null;
            try {
                try {
                    if (registry != null) registry.fire("before", scenario.tags(), SharedStepRun.instances(context));
                } catch (Throwable thrown) {
                    hookFailure = thrown;
                    context.fail(thrown);
                }
                if (hookFailure == null) {
                for (int index = 0; index < queue.size(); index++) {
                    Prepared prepared = queue.get(index);
                    EngineStep engineStep = prepared.step();
                    StepExtension frame = prepared.frame();
                    frame.parentStep = root;
                    frame.nextSibling = null;
                    frame.previousSibling = previous;
                    previous = frame;
                    root.childSteps.clear();
                    root.childSteps.add(frame);
                    context.pushStep(engineStep);
                    try {
                        state.runStep(frame);
                        if (state.isScenarioComplete()
                                && frame.result != null
                                && frame.result.getStatus() == io.cucumber.plugin.event.Status.PASSED) {
                            Throwable failure = state.stepFailures.isEmpty()
                                    ? new RuntimeException("scenario failed")
                                    : state.stepFailures.getLast();
                            frame.result = new Result(
                                    io.cucumber.plugin.event.Status.FAILED,
                                    frame.result.getDuration() == null ? java.time.Duration.ZERO : frame.result.getDuration(),
                                    failure
                            );
                        }
                    } catch (Throwable thrown) {
                        context.fail(thrown);
                        if (frame.result == null) {
                            frame.result = new Result(
                                    io.cucumber.plugin.event.Status.FAILED,
                                    java.time.Duration.ZERO,
                                    thrown
                            );
                        }
                    } finally {
                        context.popStep();
                    }
                    StepResult result = toResult(engineStep, frame);
                    results.add(result);
                    if (result.returned() != null && !result.returned().isEmpty()) {
                        context.scope().put("returned", result.returned());
                    }
                    List<EngineStep> written = context.drainWritten();
                    if (!written.isEmpty()) {
                        List<Prepared> stamped = new ArrayList<>();
                        for (EngineStep extra : written) {
                            EngineStep marked = extra.metadata().containsKey("source")
                                    ? extra
                                    : extra.withMetadata("source", "runtime");
                            stamped.add(prepareStep(marked, registry, state));
                            EngineLog.log(options == null ? null : options.project(), "run", "runtime", marked.text());
                        }
                        queue.addAll(index + 1, stamped);
                    }
                    if (result.status() == Status.FAILED
                            || result.status() == Status.UNDEFINED
                            || result.status() == Status.AMBIGUOUS) {
                        if (options == null || !options.continueOnFailure()) {
                            for (int rest = index + 1; rest < queue.size(); rest++) {
                                results.add(StepResult.of(Status.SKIPPED, queue.get(rest).step().text(), "skipped"));
                            }
                            break;
                        }
                    }
                }
                }
            } finally {
                try {
                    if (registry != null) registry.fire("after", scenario.tags(), SharedStepRun.instances(context));
                } catch (Throwable thrown) {
                    if (hookFailure == null) hookFailure = thrown;
                    context.fail(thrown);
                }
                if (hookFailure != null && results.stream().noneMatch(step ->
                        step.status() == Status.FAILED || step.status() == Status.UNDEFINED || step.status() == Status.AMBIGUOUS)) {
                    results.add(new StepResult(
                            Status.FAILED,
                            "*",
                            "hook",
                            hookFailure.getMessage() == null ? hookFailure.toString() : hookFailure.getMessage(),
                            0,
                            ""
                    ));
                }
                if (state.scenarioLog != null) state.scenarioLog.stop();
                context.phase(ExecutionContext.Phase.CLEANUP);
                SharedStepRun.forget();
            }
        }
        return ScenarioResult.fromSteps(scenario.name(), results);
    }

    private static void installExamples(CurrentScenarioState state, EngineScenario scenario) {
        if (state == null || scenario == null || state.getParsingMap() == null) return;
        String headers = scenario.metadata().get("exampleHeaders");
        String values = scenario.metadata().get("exampleValues");
        if (headers == null || values == null) return;
        NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
        examples.merge(Arrays.asList(headers.split("\t", -1)), Arrays.asList(values.split("\t", -1)));
        state.getParsingMap().addMaps(examples);
        EngineLog.log(null, "run", "examples", headers);
    }

    private record Prepared(EngineStep step, StepExtension frame, int nesting) {
    }

    private static List<Prepared> prepare(EngineScenario scenario, StepRegistry registry, CurrentScenarioState state) {
        List<Prepared> prepared = new ArrayList<>();
        for (EngineStep step : scenario.steps()) {
            prepared.add(prepareStep(step, registry, state));
        }
        return prepared;
    }

    private static Prepared prepareStep(EngineStep step, StepRegistry registry, CurrentScenarioState state) {
        String raw = step.text() == null ? "" : step.text();
        String meta = step.metadata().get("metaText");
        int flag = raw.indexOf(GlobalConstants.PARSER_FLAG);
        String text = raw;
        if (flag >= 0) {
            meta = raw.substring(flag + GlobalConstants.PARSER_FLAG.length());
            text = raw.substring(0, flag).strip();
        }
        if (meta == null) meta = "";
        EngineStep cleaned = text.equals(raw)
                ? step
                : new EngineStep(step.keyword(), text, step.argument(), step.location(), step.metadata(), step.fromBackground());
        StepExtension frame = frame(cleaned, registry, state);
        int nesting = 0;
        for (int index = 0; index < meta.length(); index++) {
            if (meta.charAt(index) == ':') nesting++;
        }
        frame.setNestingLevel(nesting);
        return new Prepared(cleaned, frame, nesting);
    }

    /** Colon children belong to the previous shallower step, not the root queue. */
    private static void linkTree(List<Prepared> prepared, List<Prepared> roots) {
        Prepared[] stack = new Prepared[64];
        for (Prepared item : prepared) {
            int level = Math.max(0, Math.min(item.nesting, stack.length - 1));
            if (level == 0) {
                roots.add(item);
                stack[0] = item;
                continue;
            }
            Prepared parent = stack[level - 1];
            if (parent == null) {
                roots.add(item);
                stack[level] = item;
                continue;
            }
            StepExtension child = item.frame();
            StepExtension parentFrame = parent.frame();
            StepExtension previous = parentFrame.childSteps.isEmpty()
                    ? null
                    : (StepExtension) parentFrame.childSteps.getLast();
            child.parentStep = parentFrame;
            child.previousSibling = previous;
            if (previous != null) previous.nextSibling = child;
            parentFrame.childSteps.add(child);
            stack[level] = item;
        }
    }

    public static StepExtension frame(EngineStep engineStep, StepRegistry registry, CurrentScenarioState state) {
        StepExtension frame = new StepExtension(engineStep.keyword(), engineStep.text());
        SharedStepRun.remember(frame, engineStep);
        frame.setStepParsingMap(state.getParsingMap());
        if (registry != null) {
            StepMatch match = registry.match(engineStep.text());
            if (match.step() != null && match.step().method() != null) {
                frame.attachNativeMethod(match.step().method());
                SharedStepRun.prepareData(frame, match);
            }
        }
        return frame;
    }

    private static StepResult toResult(EngineStep step, StepExtension frame) {
        Status status = Status.PASSED;
        String message = "";
        if (frame.skipped && (frame.result == null || frame.result.getStatus() == io.cucumber.plugin.event.Status.SKIPPED
                || frame.result.getStatus() == io.cucumber.plugin.event.Status.PASSED)) {
            status = Status.SKIPPED;
        } else if (frame.result != null) {
            status = map(frame.result.getStatus());
            if (frame.result.getError() != null) {
                message = frame.result.getError().getMessage() == null
                        ? frame.result.getError().toString()
                        : frame.result.getError().getMessage();
            }
        } else if (frame.skipped) {
            status = Status.SKIPPED;
        }
        String returned = frame.nativeReturned == null ? "" : String.valueOf(frame.nativeReturned);
        String noted = message == null ? "" : message;
        String source = step.metadata().get("source");
        if (source != null && !source.isBlank()) {
            String mark = "source=" + source;
            noted = noted.isBlank() ? mark : noted + " " + mark;
        }
        long millis = frame.result == null || frame.result.getDuration() == null
                ? 0
                : Math.max(0, frame.result.getDuration().toMillis());
        return new StepResult(status, step.keyword(), step.text(), noted, millis, returned);
    }

    private static Status map(io.cucumber.plugin.event.Status status) {
        if (status == null) return Status.FAILED;
        return switch (status) {
            case PASSED -> Status.PASSED;
            case SKIPPED -> Status.SKIPPED;
            case PENDING -> Status.PENDING;
            case UNDEFINED -> Status.UNDEFINED;
            case AMBIGUOUS -> Status.AMBIGUOUS;
            default -> Status.FAILED;
        };
    }
}
