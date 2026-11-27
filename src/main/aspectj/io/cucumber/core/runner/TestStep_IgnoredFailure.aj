package io.cucumber.core.runner;

import io.cucumber.plugin.event.Status;
import tools.dscode.common.reporting.logging.LogForwarder;

/**
 * Cucumber records a thrown step body before the scenario state can ignore it.
 * When that step is already marked ignore or log-and-continue, the body is a
 * passed step so the adapter result matches the native engine.
 */
public privileged aspect TestStep_IgnoredFailure {

    pointcut executeStepCall(
            TestStep step,
            TestCaseState state,
            ExecutionMode mode
    ) :
            execution(Status io.cucumber.core.runner.TestStep.executeStep(TestCaseState, ExecutionMode))
                    && this(step)
                    && args(state, mode);

    Status around(TestStep step, TestCaseState state, ExecutionMode mode) throws Throwable
            : executeStepCall(step, state, mode) {
        try {
            Status status = proceed(step, state, mode);
            StepExtension attempt = captureAttempt(step);
            if (attempt != null && !status.is(Status.PASSED)) {
                if (attempt.capturedError == null) {
                    attempt.capturedError = new RuntimeException("RETRY attempt failed");
                }
                return Status.PASSED;
            }
            return status;
        } catch (Throwable thrown) {
            StepExtension attempt = captureAttempt(step);
            if (attempt != null) {
                attempt.capturedError = thrown;
                return Status.PASSED;
            }
            StepExtension armed = armed(step);
            if (armed == null) {
                throw thrown;
            }
            armed.capturedError = thrown;
            if (armed.logFailuresContinue && !armed.ignoreStepFailure) {
                String message = thrown.getMessage();
                LogForwarder.logError(message == null || message.isBlank() ? "step failed" : message);
            }
            return Status.PASSED;
        }
    }

    /** A RETRY attempt is not a result step. Ignore and log-and-continue stay on their own path. */
    private static StepExtension captureAttempt(TestStep step) {
        StepExtension running = match(GlobalState.getRunningStep(), step);
        if (captureOnly(running)) return running;
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null || state.stepExtensions == null) return null;
        for (StepExtension candidate : state.stepExtensions) {
            StepExtension found = match(candidate, step);
            if (captureOnly(found)) return found;
        }
        return null;
    }

    private static boolean captureOnly(StepExtension step) {
        return step != null
                && step.captureFailure
                && !step.ignoreStepFailure
                && !step.logFailuresContinue;
    }

    private static StepExtension armed(TestStep step) {
        StepExtension running = match(GlobalState.getRunningStep(), step);
        if (ignored(running)) return running;
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null || state.stepExtensions == null) return null;
        for (StepExtension candidate : state.stepExtensions) {
            StepExtension found = match(candidate, step);
            if (ignored(found)) return found;
        }
        return null;
    }

    private static boolean ignored(StepExtension step) {
        return step != null && (step.ignoreStepFailure || step.logFailuresContinue);
    }

    private static StepExtension match(StepExtension candidate, TestStep step) {
        if (candidate == null || step == null) return null;
        if (candidate.executingPickleStepTestStep == step || candidate.pickleStepTestStep == step) {
            return candidate;
        }
        for (StepBase child : candidate.childSteps) {
            if (child instanceof StepExtension extension) {
                StepExtension nested = match(extension, step);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
