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
        if (executionStep == null) {
            String text = step.getStepText();
            return new DiagnosticStepMetadata(
                    step.method,
                    step.codeLocation == null ? "" : step.codeLocation,
                    0,
                    step.nativeKeyword == null ? "" : step.nativeKeyword,
                    text == null ? "" : text
            );
        }

        var cucumberStep = executionStep.getStep();
        String text = executionStep.getStepText();
        if (text == null || text.isBlank()) text = step.getStepText();
        if (text == null) text = "";
        return new DiagnosticStepMetadata(
                executionStep.getMethod(),
                executionStep.getCodeLocation(),
                executionStep.getStepLine(),
                cucumberStep == null ? "" : cucumberStep.getKeyword(),
                text
        );
    }
}
