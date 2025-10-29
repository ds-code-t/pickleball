package io.cucumber.core;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.CucumberObjects.createStepFromText;
import static tools.dscode.common.util.Reflect.getProperty;

public class ScenarioStep extends StepExtension {
    List<StepExtension> steps;
    public void createRootStep(TestCase testCase) {



    }

    public void createScenarioStep(TestCase testCase) {
        steps = (List<StepExtension>) getProperty(testCase, "stepExtensions");

        PickleStepTestStep scenarioStep = createStepFromText("a");

    }


    private ScenarioStep(TestCase testCase, PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);
    }

    private void initializeScenarioSteps() {
        List<StepExtension> steps = (List<StepExtension>) getProperty(testCase, "stepExtensions");
        int size = steps.size();
        Map<Integer, StepExtension> nestingMap = new HashMap<>();
        nestingMap.put(nestingLevel, this);
        int lastNestingLevel = 0;
        int startingNesting = nestingLevel + 1;
        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.nestingLevel = currentStep.nestingLevel + startingNesting;
            int currentNesting = currentStep.nestingLevel;
            StepExtension parentStep = nestingMap.get(currentNesting - 1);
            StepExtension previousSibling = currentNesting > lastNestingLevel ? null : nestingMap.get(currentNesting);
            if (previousSibling != null) {
                currentStep.previousSibling = previousSibling;
                previousSibling.nextSibling = currentStep;
            }
            if (parentStep != null) {
                parentStep.childSteps.add(currentStep);
                currentStep.parentStep = parentStep;
            }
            nestingMap.put(currentNesting, currentStep);
            lastNestingLevel = currentNesting;
        }
    }

}
