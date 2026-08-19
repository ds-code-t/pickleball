package io.cucumber.core.runner;

import tools.dscode.control.override.StepOverrideContext;
import tools.dscode.control.override.StepOverrideRegistry;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;

/**
 * Cucumber/Pickleball integration target used by both ordinary traversal and
 * detached {@code DynamicControl} execution.
 */
public final class StepOverrideDispatcher {
    public StepOverrideDispatcher() {
    }

    public static Object execute() throws Exception {
        StepExtension step = getRunningStep();
        if (step == null) {
            throw new IllegalStateException("Step Override execution requires an active Pickleball step.");
        }

        String resolvedStepText = step.getStepText();
        StepOverrideRegistry.Match match = StepOverrideRegistry.match(resolvedStepText)
                .orElseThrow(() -> new IllegalStateException(
                        "Step Override no longer exists for step '" + resolvedStepText + "'."
                ));

        String originalStepText = step.pickleStepTestStep.unresolvedText == null
                ? resolvedStepText
                : step.pickleStepTestStep.unresolvedText;

        StepOverrideContext context = new StepOverrideContext(
                originalStepText,
                resolvedStepText,
                step.pickleStepTestStep.getStep().getKeyword(),
                match.captures(),
                step.pickleStepTestStep.getStep().getArgument(),
                getCurrentScenarioState(),
                step,
                getRunningParsingMap()
        );
        return match.rule().handler().execute(context);
    }
}
