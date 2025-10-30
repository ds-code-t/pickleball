package io.cucumber.core.runner;

import static io.cucumber.core.runner.CucumberObjects.createStepFromTextAndLocation;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class PredefinedSteps {

    public static final PickleStepTestStep rootStep = CucumberObjects.createStepFromText(ROOT_STEP, "tools.dscode.coredefinitions");

    static {
        invokeAnyMethod(rootStep, "setNoLogging", true);
    }


    public StepExtension createMessageStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, createStepFromTextAndLocation( INFO_STEP + message, modelStep.getStep().getLocation(), modelStep.getUri(), "tools.dscode.coredefinitions"));
    }

    public StepExtension createHardErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, createStepFromTextAndLocation( HARD_ERROR_STEP + message, modelStep.getStep().getLocation(), modelStep.getUri(), "tools.dscode.coredefinitions"));
    }

    public StepExtension createSoftErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, createStepFromTextAndLocation( SOFT_ERROR_STEP + message, modelStep.getStep().getLocation(), modelStep.getUri(), "tools.dscode.coredefinitions"));
    }
}
