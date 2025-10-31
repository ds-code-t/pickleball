package io.cucumber.core.runner;

import java.util.HashMap;
import java.util.Map;

import static io.cucumber.core.runner.CucumberObjects.createStepFromText;
import static io.cucumber.core.runner.StepBuilder.buildPickleStepTestStep;
import static io.cucumber.core.runner.StepCloner.clonePickleStepTestStep;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class PredefinedSteps {

    private static  PickleStepTestStep rootStep;

    public static PickleStepTestStep getRootStep() {
//        rootStep =   buildPickleStepTestStep(ROOT_STEP, "tools.dscode.coredefinitions");
        rootStep =   createStepFromText( ROOT_STEP,  "tools.dscode.coredefinitions");;
        invokeAnyMethod(rootStep, "setNoLogging", true);
        return rootStep;
    }



    public StepExtension createMessageStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        Map<String, Object> map = new HashMap<>();
        return new StepExtension(stepExtension.testCase, clonePickleStepTestStep( modelStep, INFO_STEP + message, "tools.dscode.coredefinitions"));
    }

    public StepExtension createHardErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, clonePickleStepTestStep(modelStep,   HARD_ERROR_STEP + message,"tools.dscode.coredefinitions"));
    }

    public StepExtension createSoftErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, clonePickleStepTestStep( modelStep, SOFT_ERROR_STEP + message,  "tools.dscode.coredefinitions"));
    }
}
