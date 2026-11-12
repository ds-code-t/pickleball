package tools.dscode.common.assertions;

import io.cucumber.core.runner.StepData;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.context.RunBindings;

import java.util.function.Supplier;

public class ConditionRepeater {

    /**
     * Repeats execution of the logic until ALL conditions evaluate to true.
     * Returns the result of the last logic execution (or null).
     * Throws if conditions array is null or empty.
     */
    public static Object repeatUntil(ExecutionContext context, Supplier<Object> logic, String... conditions) {
        StepData runningStep = RunBindings.of(context).runningStep();
        Object result;
        while (true) {
            result = logic.get();

            boolean allTrue = true;
            for (String condition : conditions) {
                if (!runningStep.getStepParsingMap().resolveWholeText(condition).equalsIgnoreCase("true")) {
                    allTrue = false;
                    break; // Early exit for AND logic
                }
            }

            if (allTrue) {
                return result;
            }
            // Otherwise, repeat the loop
        }
    }

    public static Object repeatUntil(Supplier<Object> logic, String... conditions) {
        return repeatUntil(ExecutionContext.current(), logic, conditions);
    }

    /**
     * Alternative version: Repeats execution of the logic until the FIRST condition (in vararg order)
     * evaluates to true (short-circuit OR logic).
     * Returns the result of the last logic execution (or null).
     * Throws if conditions array is null or empty.
     */
    public static Object repeatUntilAny(ExecutionContext context, Supplier<Object> logic, String... conditions) {
        StepData runningStep = RunBindings.of(context).runningStep();
        Object result;
        while (true) {
            result = logic.get();

            boolean anyTrue = false;
            for (String condition : conditions) {
                if (runningStep.getStepParsingMap().resolveWholeText(condition).equalsIgnoreCase("true")) {
                    anyTrue = true;
                    break; // Break on the first true (as requested)
                }
            }

            if (anyTrue) {
                return result;
            }
            // Otherwise, repeat the loop
        }
    }

    public static Object repeatUntilAny(Supplier<Object> logic, String... conditions) {
        return repeatUntilAny(ExecutionContext.current(), logic, conditions);
    }
}