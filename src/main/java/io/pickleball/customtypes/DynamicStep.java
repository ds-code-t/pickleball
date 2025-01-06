package io.pickleball.customtypes;

import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import io.pickleball.cacheandstate.ScenarioContext;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenario;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

public class DynamicStep {
    private final String stepText;

    public String getAdditionalData() {
        return additionalData;
    }

    private String additionalData;

    public DynamicStep(String stepText) {
        this.stepText = stepText;
    }

    public DynamicStep(String stepText, String additionalData) {
        this.stepText = stepText;
        this.additionalData = additionalData;
    }


    public String getStepText() {
        return stepText;
    }

    public Object runStep() {
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(stepText);
        ScenarioContext scenarioContext = getCurrentScenario();
        return pickleStepTestStep.run((TestCase) scenarioContext, scenarioContext.getRunner().bus, scenarioContext.getTestCaseState(), ExecutionMode.RUN);
    }

    public static Object runStep(String inputText) {
        System.out.println("@@runStep: " + inputText);
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(inputText);
        ScenarioContext scenarioContext = getCurrentScenario();
        pickleStepTestStep.run((TestCase) scenarioContext, scenarioContext.getRunner().bus, scenarioContext.getTestCaseState(), ExecutionMode.RUN);
        return pickleStepTestStep.getLastExecutionReturnValue();
    }


    @Override
    public String toString() {
        return "MetaStep{stepText=" + stepText + '}';
    }
}
