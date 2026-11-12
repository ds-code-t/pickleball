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
import java.util.function.Consumer;

/** Runs one neutral scenario on the shared step lifecycle. */
public final class NativeScenarioRun {
    private NativeScenarioRun() {
    }

    public static ScenarioResult run(EngineScenario scenario, StepRegistry registry, EngineOptions options) {
        if (ExecutionContext.current() == null && CurrentScenarioState.currentScenarioState.get() == null) {
            MappingProcessor.resetCommonMaps();
        }
        List<Seen> seen = new ArrayList<>();
        List<StepResult> trailing = new ArrayList<>();
        boolean scenarioFailed = false;
        String scenarioFailure = "";
        java.util.Set<StepExtension> recorded = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Consumer<StepExtension> outerTrace = NativeStepTrace.current();
        NativeStepTrace.listen(frame -> {
            if (frame == null || !recorded.add(frame)) return;
            seen.add(new Seen(SharedStepRun.bound(frame), frame));
            if (outerTrace != null) outerTrace.accept(frame);
        });
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
            List<StepExtension> frames = new ArrayList<>();
            for (EngineStep step : scenario.steps()) {
                frames.add(prepareStep(step, registry, state).frame());
            }
            if (state.getParsingMap() != null && root.getDefaultStepNodeMap() != null) {
                state.getParsingMap().addMapsToStart(root.getDefaultStepNodeMap());
            }
            for (StepExtension frame : frames) {
                frame.setStepParsingMap(state.getParsingMap());
            }
            root.installNativeSteps(frames, state.getParsingMap(), null);
            List<StepExtension> roots = new ArrayList<>();
            for (io.cucumber.core.runner.StepBase child : new ArrayList<>(root.childSteps)) {
                if (child instanceof StepExtension extension) roots.add(extension);
            }
            Throwable hookFailure = null;
            try {
                try {
                    if (registry != null) registry.fire("before", scenario.tags(), SharedStepRun.instances(context));
                } catch (Throwable thrown) {
                    hookFailure = thrown;
                    context.fail(thrown);
                }
                if (hookFailure == null) {
                for (int index = 0; index < roots.size(); index++) {
                    StepExtension frame = roots.get(index);
                    EngineStep engineStep = SharedStepRun.bound(frame);
                    io.cucumber.core.runner.StepBase savedNext = frame.nextSibling;
                    frame.nextSibling = null;
                    context.pushStep(engineStep == null ? tools.dscode.engine.model.EngineStep.of("* ", frame.getStepText()) : engineStep);
                    try {
                        state.runStep(frame);
                    } catch (Throwable thrown) {
                        context.fail(thrown);
                        if (frame.result == null) {
                            frame.result = new Result(
                                    io.cucumber.plugin.event.Status.FAILED,
                                    java.time.Duration.ZERO,
                                    thrown
                            );
                        }
                        NativeStepTrace.record(frame);
                        state.recordHardFailure(thrown);
                    } finally {
                        frame.nextSibling = savedNext;
                        context.popStep();
                    }
                    StepResult result = toResult(engineStep, frame);
                    if (result.returned() != null && !result.returned().isEmpty()) {
                        context.scope().put("returned", result.returned());
                    }
                    List<EngineStep> written = context.drainWritten();
                    if (!written.isEmpty()) {
                        StepExtension cursor = frame;
                        for (EngineStep writtenStep : written) {
                            EngineStep marked = writtenStep.metadata().containsKey("source")
                                    ? writtenStep
                                    : writtenStep.withMetadata("source", "runtime");
                            StepExtension extra = prepareStep(marked, registry, state).frame();
                            extra.parentStep = root;
                            extra.previousSibling = cursor;
                            cursor.nextSibling = extra;
                            cursor = extra;
                            EngineLog.log(options == null ? null : options.project(), "run", "runtime", marked.text());
                        }
                        cursor.nextSibling = savedNext;
                        if (savedNext != null) savedNext.previousSibling = cursor;
                        List<StepExtension> extras = new ArrayList<>();
                        StepExtension walk = (StepExtension) frame.nextSibling;
                        while (walk != null && walk != savedNext) {
                            extras.add(walk);
                            walk = (StepExtension) walk.nextSibling;
                        }
                        roots.addAll(index + 1, extras);
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
                if (hookFailure != null && seen.stream().noneMatch(item -> {
                    StepResult step = toResult(item.step(), item.frame());
                    return step.status() == Status.FAILED || step.status() == Status.UNDEFINED || step.status() == Status.AMBIGUOUS;
                })) {
                    trailing.add(new StepResult(
                            Status.FAILED,
                            "*",
                            "hook",
                            hookFailure.getMessage() == null ? hookFailure.toString() : hookFailure.getMessage(),
                            0,
                            ""
                    ));
                }
                scenarioFailed = state.isScenarioFailed();
                if (scenarioFailed && !state.stepFailures.isEmpty() && state.stepFailures.getLast() != null) {
                    String text = state.stepFailures.getLast().getMessage();
                    scenarioFailure = text == null ? "" : text;
                }
                if (state.scenarioLog != null) state.scenarioLog.stop();
                context.phase(ExecutionContext.Phase.CLEANUP);
                SharedStepRun.forget();
            }
        } finally {
            NativeStepTrace.listen(outerTrace);
        }
        List<StepResult> results = new ArrayList<>();
        for (Seen item : seen) {
            results.add(toResult(item.step(), item.frame()));
        }
        results.addAll(trailing);
        ScenarioResult outcome = ScenarioResult.fromSteps(scenario.name(), results);
        if (scenarioFailed && outcome.status() != Status.FAILED && outcome.status() != Status.UNDEFINED) {
            outcome = new ScenarioResult(Status.FAILED, outcome.name(), outcome.steps(), scenarioFailure);
        }
        return outcome;
    }

    private record Seen(EngineStep step, StepExtension frame) {
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

    public static StepExtension preparedFrame(EngineStep step, StepRegistry registry, CurrentScenarioState state) {
        return prepareStep(step, registry, state).frame();
    }

    public static StepExtension frame(EngineStep engineStep, StepRegistry registry, CurrentScenarioState state) {
        StepExtension frame = new StepExtension(engineStep.keyword(), engineStep.text());
        SharedStepRun.remember(frame, engineStep);
        String inlineType = engineStep.metadata().get("inlineArgumentType");
        String inlineText = engineStep.metadata().get("inlineArgumentText");
        if (inlineType != null) frame.nativeInlineType = inlineType;
        if (inlineText != null) frame.nativeInlineText = inlineText;
        frame.setStepParsingMap(state.getParsingMap());
        if (registry != null) {
            StepMatch match = registry.match(engineStep.text());
            if (match.step() != null && match.step().method() != null) {
                frame.attachNativeMethod(match.step().method());
                SharedStepRun.prepareData(frame, match);
                if (frame.isStepMarker && match.captures() != null && !match.captures().isEmpty()
                        && match.captures().getFirst() != null) {
                    frame.stepMarkerText = match.captures().getFirst().trim();
                }
            }
        }
        return frame;
    }

    private static void appendChildren(StepExtension frame, List<StepResult> results) {
        if (frame == null || frame.childSteps == null) return;
        for (Object child : frame.childSteps) {
            if (!(child instanceof StepExtension extension)) continue;
            results.add(toResult(null, extension));
            appendChildren(extension, results);
        }
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
        String source = step == null ? null : step.metadata().get("source");
        if (source != null && !source.isBlank()) {
            String mark = "source=" + source;
            noted = noted.isBlank() ? mark : noted + " " + mark;
        }
        long millis = frame.result == null || frame.result.getDuration() == null
                ? 0
                : frame.result.getDuration().toMillis();
        return new StepResult(status, keyword(frame, step), displayText(frame, step), noted, millis, returned);
    }

    private static String keyword(StepExtension frame, EngineStep step) {
        if (step != null && step.keyword() != null) return step.keyword();
        return frame.nativeKeyword == null ? "" : frame.nativeKeyword;
    }

    private static String displayText(StepExtension frame, EngineStep step) {
        if (frame.substituteLoggingText != null && !frame.substituteLoggingText.isBlank()) {
            return frame.substituteLoggingText;
        }
        boolean skipped = frame.skipped
                || frame.logAndIgnore
                || (frame.result != null && frame.result.getStatus() == io.cucumber.plugin.event.Status.SKIPPED);
        String base = step == null || step.text() == null ? "" : step.text();
        if (!skipped && frame.isDynamicStep && frame.lineData != null
                && frame.lineData.runningText != null && !frame.lineData.runningText.isBlank()) {
            base = frame.lineData.runningText;
        } else if (frame.getStepText() != null && !frame.getStepText().isBlank()) {
            base = frame.getStepText();
        }
        int nesting = Math.max(0, frame.getNestingLevel());
        // initializeScenarioSteps adds one level. Cucumber prints " : ".repeat(level - 1).
        String prefix = " : ".repeat(Math.max(0, nesting - 1));
        if (prefix.isEmpty() || base.startsWith(prefix)) return base;
        return prefix + base;
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
