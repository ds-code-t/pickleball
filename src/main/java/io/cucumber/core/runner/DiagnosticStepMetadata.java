package io.cucumber.core.runner;

import java.lang.reflect.Method;

/**
 * Public diagnostic view of the package-private Cucumber step definition type.
 */
public record DiagnosticStepMetadata(
        Method method,
        String codeLocation,
        int stepLine,
        String keyword,
        String stepText
) {
    public static DiagnosticStepMetadata from(StepExtension step) {
        if (step == null) return null;

        PickleStepTestStep executionStep = step.executingPickleStepTestStep;
        if (executionStep == null) executionStep = step.pickleStepTestStep;
        if (executionStep == null) return null;

        var cucumberStep = executionStep.getStep();
        return new DiagnosticStepMetadata(
                executionStep.getMethod(),
                executionStep.getCodeLocation(),
                executionStep.getStepLine(),
                cucumberStep == null ? "" : cucumberStep.getKeyword(),
                executionStep.getStepText()
        );
    }
}
