package tools.dscode.common.gherkinoperations;

import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.exceptions.StepCreationException;

import java.util.List;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GeneralGherkinUtils.getKeyWord;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getGivenKeyword;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.PredefinedSteps.getTempStep;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.coredefinitions.ModularScenarios.populateRunScenariosStep;

public class DynamicExecution {

    public static Object tryToRunDynamicStep(String stepText) {
        return tryToRunDynamicStep(stepText, "");
    }

    public static Object tryToRunDynamicStep(String stepText, String argumentText) {
        argumentText = argumentText == null || argumentText.isBlank() ? "" : argumentText;
        StepExtension currentStep = getRunningStep();
        try {
            return runCustomStep(stepText, argumentText);
        } catch (Exception e) {
            return e;
        }
    }

    public static Object runCustomStep(String stepText) {
        return runCustomStep(stepText, "");
    }

    public static Object runCustomStep(String stepText, String argumentText) {
        argumentText = argumentText == null || argumentText.isBlank() ? "" : argumentText;
        try {
            return getCustomStep(stepText, argumentText).runAndGetReturnValue();
        } catch (Throwable t) {
            throw new StepCreationException("Failed to create Step '" + stepText + "'" + (argumentText.isBlank() ? " with argument '" + argumentText + "'" : "") + t.getMessage(), t);
        }
    }

    public static StepExtension getCustomStep(String stepText) {
        return getCustomStep(stepText, "");
    }

    public static StepExtension getCustomStep(String stepText, String argumentText) {
        argumentText = argumentText == null || argumentText.isBlank() ? "" : argumentText;
        StepExtension modifiedStep = new StepExtension(getTestCase(), getPickleStepTestStepFromStrings(getGivenKeyword(), stepText, argumentText));
        modifiedStep.setStepParsingMap(getRunningParsingMap());
        return modifiedStep;
    }

    public static ScenarioStep getScenarioFromTag(String tags) {
        return getScenariosFromTags(tags).getFirst();
    }

    public static List<ScenarioStep> getScenariosFromTags(String tags) {
        StepExtension topStep = getTempStep();
        topStep.setStepParsingMap(getRunningParsingMap());
        populateRunScenariosStep(topStep, tags, null);
        setStepAndDescendantsToNoLog(topStep);
        return topStep.childSteps.stream().map(s -> (ScenarioStep) s).collect(java.util.stream.Collectors.toList());
    }


    public static Object runScenarioFromTag(String tags) {
        ScenarioStep scenarioStep = getScenarioFromTag(tags);
        getCurrentScenarioState().runStep(scenarioStep);
        return scenarioStep;
    }

    public static List<Object> runScenariosFromTag(String tags) {
        return getScenariosFromTags(tags).stream().map(StepExtension::runAndGetReturnValue).collect(Collectors.toList());
    }

    private static void setStepAndDescendantsToNoLog(StepExtension step) {
        step.addDefinitionFlag(DefinitionFlag.NO_LOGGING);
        step.childSteps.forEach(childStep -> setStepAndDescendantsToNoLog((StepExtension) childStep));
    }

}
