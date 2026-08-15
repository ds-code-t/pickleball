package tools.dscode.common.control;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.reporting.logging.Entry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToScenario;
import static tools.dscode.common.reporting.logging.LogForwarder.getDefaultEntry;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;

/** Thread-local running-step override used for detached exploratory execution. */
public final class ControlExecutionScope {
    private static final ThreadLocal<Deque<StepExtension>> STEP_OVERRIDES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ControlExecutionScope() {
    }

    public static StepExtension currentStepOverride() {
        Deque<StepExtension> stack = STEP_OVERRIDES.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public static <T> T withStep(StepExtension step, Supplier<T> action) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(action, "action");

        Deque<StepExtension> stack = STEP_OVERRIDES.get();
        Entry previousStepEntry = step.stepEntry;
        Entry previousDefaultEntry = getDefaultEntry();
        boolean detachedEntryCreated = previousStepEntry == null;
        if (detachedEntryCreated) {
            step.stepEntry = closestEntryToScenario()
                    .child("Detached control step: " + step.getStepText())
                    .tag("Control")
                    .tag("Detached")
                    .start();
        }

        stack.push(step);
        setDefaultEntry(step.stepEntry);
        try {
            return action.get();
        } finally {
            if (detachedEntryCreated) {
                step.stepEntry.stop();
            }
            step.stepEntry = previousStepEntry;
            setDefaultEntry(previousDefaultEntry);
            stack.pop();
            if (stack.isEmpty()) {
                STEP_OVERRIDES.remove();
            }
        }
    }
}
