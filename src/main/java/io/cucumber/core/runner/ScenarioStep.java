package io.cucumber.core.runner;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.CucumberObjects.createStepFromText;
import static io.cucumber.core.runner.CucumberObjects.createStepFromTextAndLocation;
import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

public class ScenarioStep extends StepExtension {


    public static ScenarioStep createScenarioStep(TestCase testCase) {
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep = createStepFromTextAndLocation( "SCENARIO: " + testCase.getName(), testCase.getLocation(), testCase.getUri(), "tools.dscode.coredefinitions");
        System.out.println("@@scenarioPickleStepTestStep1: " + scenarioPickleStepTestStep);
        System.out.println("@@scenarioPickleStepTestStep2: " + scenarioPickleStepTestStep.getStep().getText());
        System.out.println("@@scenarioPickleStepTestStep3: " + getProperty(scenarioPickleStepTestStep, "definitionMatch"));
        ScenarioStep scenarioStep = new ScenarioStep(testCase, scenarioPickleStepTestStep);
        setProperty(testCase, "rootScenarioStep", scenarioStep);
        return scenarioStep;
    }


    private ScenarioStep(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);
        initializeScenarioSteps();
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
