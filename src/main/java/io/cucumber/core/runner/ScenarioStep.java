package io.cucumber.core.runner;

//import io.cucumber.messages.types.Pickle;

import io.cucumber.core.gherkin.Pickle;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.ParsingMap;

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
        return createScenarioStep(pickle, null);
    }

    public static ScenarioStep createScenarioStep(Pickle pickle, ParsingMap parsingMap) {
        io.cucumber.core.runner.TestCase topLevel  =   GlobalState.getTestCase();
        String scenarioName =  SCENARIO_STEP + (parsingMap == null ? pickle.getName() : parsingMap.resolveWholeText(pickle.getName()));
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep = getPickleStepTestStepFromStrings(pickle, getGivenKeyword() ,   scenarioName, null);
        ScenarioStep scenarioStep = new ScenarioStep(topLevel, scenarioPickleStepTestStep);
        if(parsingMap!= null) {
            scenarioStep.stepParsingMap.clear();
            scenarioStep.stepParsingMap.getMaps().putAll(parsingMap.getMaps());
        }
        scenarioStep.initializeScenarioSteps(createPickleStepTestStepsFromPickle(pickle).stream().map(step -> new StepExtension(getTestCase(), step)).toList());
        return scenarioStep;
    }


    private ScenarioStep(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);
//        Pickle gherkinMessagesPickle = (Pickle) getProperty(testCase, "pickle");
//        io.cucumber.messages.types.Pickle pickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
//        getStepNodeMap().merge(pickle.getHeaderRow(), pickle.getValueRow());
    }

    private void initializeScenarioSteps(List<StepExtension> steps) {
        int size = steps.size();
        Map<Integer, StepExtension> nestingMap = new HashMap<>();
        nestingMap.put(getNestingLevel(), this);
        int lastNestingLevel = 0;
        int startingNesting = getNestingLevel() + 1;
        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.setNestingLevel(currentStep.getNestingLevel() + startingNesting);
            int currentNesting = currentStep.getNestingLevel();
            StepExtension parentStep = nestingMap.get(currentNesting - 1);
            StepExtension previousSibling = currentNesting > lastNestingLevel ? null : nestingMap.get(currentNesting);
            if(currentStep.dataArgumentStep) {
                if(previousSibling != null) {
                    previousSibling.dataTable = currentStep.dataTable;
                    previousSibling.docString = currentStep.docString;
                    previousSibling.dataContextStepNodeMap = currentStep.dataContextStepNodeMap;
//                    previousSibling.getStepParsingMap().addMaps(currentStep.dataContextStepNodeMap);
                }
                continue;
            }
            if (previousSibling != null) {
                currentStep.previousSibling = previousSibling;
                previousSibling.nextSibling = currentStep;

                if(previousSibling.nextSiblingDefinitionFlags != null)
                {
                    currentStep.addDefinitionFlag(previousSibling.nextSiblingDefinitionFlags.toArray(new DefinitionFlag[0]));
                }
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
