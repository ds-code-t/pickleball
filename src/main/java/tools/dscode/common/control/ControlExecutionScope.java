package tools.dscode.common.control;

import io.cucumber.core.runner.StepExtension;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

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
        stack.push(step);
        try {
            return action.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                STEP_OVERRIDES.remove();
            }
        }
    }
}
