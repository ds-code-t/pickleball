package tools.dscode.engine.model;

import java.util.List;

/** Outcome of one scenario. */
public record ScenarioResult(
        Status status,
        String name,
        List<StepResult> steps,
        String message
) {
    public ScenarioResult {
        status = status == null ? Status.UNDEFINED : status;
        name = name == null ? "" : name;
        steps = steps == null ? List.of() : List.copyOf(steps);
        message = message == null ? "" : message;
    }

    public static ScenarioResult fromSteps(String name, List<StepResult> steps) {
        Status status = Status.PASSED;
        String message = "";
        boolean skipped = false;
        if (steps != null) {
            for (StepResult step : steps) {
                if (step.status() == Status.SKIPPED) {
                    skipped = true;
                }
                if (step.status() == Status.FAILED || step.status() == Status.AMBIGUOUS || step.status() == Status.UNDEFINED) {
                    status = step.status() == Status.UNDEFINED ? Status.UNDEFINED : Status.FAILED;
                    message = step.message();
                    break;
                }
            }
        }
        if (status == Status.PASSED && skipped) {
            status = Status.SKIPPED;
        }
        return new ScenarioResult(status, name, steps == null ? List.of() : steps, message);
    }
}
