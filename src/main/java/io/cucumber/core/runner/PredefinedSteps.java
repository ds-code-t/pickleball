package io.cucumber.core.runner;

import java.util.HashMap;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getGivenKeyword;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class PredefinedSteps {

    private static PickleStepTestStep rootStep;

    public static synchronized PickleStepTestStep getRootStep() {
        if (rootStep == null) {
            rootStep = getPickleStepTestStepFromStrings(getGivenKeyword(), ROOT_STEP, null);
            invokeAnyMethod(rootStep, "setNoLogging", true);
        }
        return rootStep;
    }



    public StepExtension createMessageStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        Map<String, Object> map = new HashMap<>();
        return new StepExtension(stepExtension.testCase, getPickleStepTestStepFromStrings( modelStep,getGivenKeyword(),   INFO_STEP + message, null));
    }

    public StepExtension createHardErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, getPickleStepTestStepFromStrings( modelStep,getGivenKeyword(),    HARD_ERROR_STEP + message, null));
    }

    public StepExtension createSoftErrorStep(StepExtension stepExtension, String message) {
        PickleStepTestStep modelStep = stepExtension.pickleStepTestStep;
        return new StepExtension(stepExtension.testCase, getPickleStepTestStepFromStrings( modelStep,getGivenKeyword(),   SOFT_ERROR_STEP + message, null));
    }
}
