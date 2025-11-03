package io.cucumber.core.runner;

//import io.cucumber.messages.types.Pickle;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.plugin.event.TestCase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import static io.cucumber.core.runner.GlobalState.getGivenKeyword;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.createPickleStepTestStepsFromPickle;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

public class ScenarioStep extends StepExtension {
    public static ScenarioStep createRootScenarioStep(io.cucumber.core.runner.TestCase testCase) {
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep = getPickleStepTestStepFromStrings((Pickle) getProperty(testCase, "pickle"),getGivenKeyword() ,  SCENARIO_STEP + testCase.getName(), null);
        ScenarioStep scenarioStep = new ScenarioStep(testCase, scenarioPickleStepTestStep);
        setProperty(testCase, "rootScenarioStep", scenarioStep);
        scenarioStep.initializeScenarioSteps((List<StepExtension>) getProperty(testCase, "stepExtensions"));
        return scenarioStep;
    }

    public static ScenarioStep createScenarioStep(Pickle pickle) {
        io.cucumber.core.runner.TestCase topLevel  =   GlobalState.getTestCase();
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep = getPickleStepTestStepFromStrings(pickle, getGivenKeyword() ,  SCENARIO_STEP + pickle.getName(), null);
        ScenarioStep scenarioStep = new ScenarioStep(topLevel, scenarioPickleStepTestStep);
        scenarioStep.initializeScenarioSteps(createPickleStepTestStepsFromPickle(pickle).stream().map(step -> new StepExtension(topLevel, step)).toList());
        return scenarioStep;
    }


    private ScenarioStep(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);

    }

    private void initializeScenarioSteps(List<StepExtension> steps) {
        int size = steps.size();
        Map<Integer, StepExtension> nestingMap = new HashMap<>();
        nestingMap.put(nestingLevel, this);
        int lastNestingLevel = 0;
        int startingNesting = nestingLevel + 1;
        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.nestingLevel = currentStep.nestingLevel + startingNesting;
            int currentNesting = currentStep.nestingLevel;
            System.out.println("@@currentNesting: " + currentNesting + "");
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
