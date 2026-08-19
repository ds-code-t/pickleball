package tools.dscode.control.override;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.ParsingMap;

import java.util.List;

/**
 * In-process context supplied to one Step Override handler invocation.
 *
 * <p>The bridge/controller should transport only bounded authoring inputs and results.
 * This object intentionally remains worker-side and may expose live Pickleball objects.
 */
public record StepOverrideContext(
        String originalStepText,
        String resolvedStepText,
        String keyword,
        List<String> captures,
        Object argument,
        CurrentScenarioState scenario,
        StepExtension step,
        ParsingMap parsingMap
) {
    public StepOverrideContext {
        captures = captures == null ? List.of() : List.copyOf(captures);
    }
}
