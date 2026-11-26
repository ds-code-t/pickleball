package tools.dscode.engine.adapter.cucumber;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Status;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.runtime.StepFlags;
import tools.dscode.engine.runtime.StepRows;
import tools.dscode.store.StoreConfig;

import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FINISHED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;

/**
 * Writes parsed flag phrases onto the running step.
 * {@link StepFlags#applyFromPhrase} is the glue entry. Retry runs here so
 * {@link tools.dscode.engine.runtime.StepFlags} stays free of Cucumber types.
 */
public final class StepFlagBridge {
    static {
        StepFlags.install(StepFlagBridge::apply);
    }

    private StepFlagBridge() {
    }

    /** Loads the applier. Call before a step body so glue is not a no-op. */
    public static void load() {
        // Class initialization installs the applier.
    }

    public static void apply(StepFlags.Parsed parsed) {
        StepExtension step = GlobalState.getRunningStep();
        if (step == null || parsed == null) return;
        applyToFrame(step, parsed);
        if (parsed.retry() && !step.retryHandled) {
            step.retryHandled = true;
            runRetry(step, parsed);
        }
    }

    /** Flags and ceilings only. Does not execute a {@code RETRY:} remainder. */
    public static void applyToFrame(StepExtension step, StepFlags.Parsed parsed) {
        if (step == null || parsed == null) return;
        if (parsed.alwaysRun()) step.addStepFlags(ALWAYS_RUN);
        if (parsed.runIfPassing()) step.addStepFlags(RUN_IF_SCENARIO_PASSING);
        if (parsed.runIfFailed()) step.addStepFlags(RUN_IF_SCENARIO_FAILED);
        if (parsed.runIfHardFailed()) step.addStepFlags(RUN_IF_SCENARIO_HARD_FAILED);
        if (parsed.runIfSoftFailed()) step.addStepFlags(RUN_IF_SCENARIO_SOFT_FAILED);
        if (parsed.runIfFinished()) step.addStepFlags(RUN_IF_SCENARIO_FINISHED);
        if (parsed.ignoreFailures()) step.ignoreStepFailure = true;
        if (parsed.logFailuresContinue()) step.logFailuresContinue = true;
        if (parsed.untilMode() != null) step.untilMode = parsed.untilMode();
        step.assignCeilings(parsed.maxIterations(), parsed.timeoutSeconds());
        if (parsed.ignoreOnNext() && step.nextSibling instanceof StepExtension next) {
            next.ignoreStepFailure = true;
        }
        if (parsed.logOnNext() && step.nextSibling instanceof StepExtension next) {
            next.logFailuresContinue = true;
        }
    }

    /** Text flags, then a stored row when the line matches. Does not run retry. */
    public static void applyStored(StepExtension step) {
        load();
        if (step == null) return;
        String text = step.getUnmodifiedText();
        if (text == null || text.isBlank()) text = step.getStepText();
        int line = 0;
        String path = "";
        EngineStep bound = SharedStepRun.bound(step);
        if (bound != null && bound.location() != null) {
            line = bound.location().line();
            path = bound.location().uri() == null ? "" : bound.location().uri();
        }
        if (line <= 0) {
            line = step.gherkinLine();
        }
        applyToFrame(step, StepRows.lookup(StoreConfig.project(), path, line, text));
    }

    private static void runRetry(StepExtension step, StepFlags.Parsed parsed) {
        String remainder = parsed.remainder() == null ? "" : parsed.remainder().trim();
        if (remainder.isEmpty()) {
            throw new RuntimeException("RETRY: has no step to run");
        }
        int limit = parsed.maxIterations() != null
                ? parsed.maxIterations()
                : step.stepMaxIterations != null
                ? step.stepMaxIterations
                : StepData.effectiveMaxIterations();
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            throw new IllegalStateException("RETRY requires a running scenario");
        }
        Throwable last = new RuntimeException("RETRY made no attempts");
        int attempts = 0;
        while (limit < 0 || attempts < limit) {
            attempts++;
            StepExtension child = step.createNewStepExtension(remainder);
            child.captureFailure = true;
            child.nextSibling = null;
            state.runStep(child);
            if (child.capturedError == null
                    && child.result != null
                    && child.result.getStatus() == Status.PASSED) {
                return;
            }
            last = child.capturedError == null
                    ? new RuntimeException("RETRY attempt failed")
                    : child.capturedError;
        }
        if (last instanceof RuntimeException runtime) throw runtime;
        throw new RuntimeException(last);
    }
}
