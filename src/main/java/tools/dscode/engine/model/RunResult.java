package tools.dscode.engine.model;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one engine run. */
public record RunResult(Status status, List<ScenarioResult> scenarios, String message) {
    public RunResult {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        message = message == null ? "" : message;
        if (status == null) {
            status = scenarios.stream().anyMatch(scenario -> !scenario.status().isOk() && scenario.status() != Status.PENDING)
                    ? Status.FAILED
                    : Status.PASSED;
        }
    }

    public static RunResult of(List<ScenarioResult> scenarios) {
        List<ScenarioResult> copy = scenarios == null ? List.of() : new ArrayList<>(scenarios);
        Status status = Status.PASSED;
        String message = "";
        for (ScenarioResult scenario : copy) {
            if (scenario.status() != Status.PASSED && scenario.status() != Status.SKIPPED) {
                status = scenario.status() == Status.UNDEFINED ? Status.UNDEFINED : Status.FAILED;
                message = scenario.message();
                break;
            }
        }
        return new RunResult(status, copy, message);
    }
}
