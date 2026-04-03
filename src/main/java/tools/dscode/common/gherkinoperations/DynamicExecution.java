package tools.dscode.common.gherkinoperations;

import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.exceptions.StepCreationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GeneralGherkinUtils.getKeyWord;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;

public class DynamicExecution {

    public static Object tryToRunDynamicStep(String stepText) {
        return tryToRunDynamicStep(stepText, "");
    }

    public static Object tryToRunDynamicStep(String stepText, String argumentText) {
        argumentText = argumentText == null || argumentText.isBlank() ? "" : argumentText;
        StepExtension currentStep = getRunningStep();
        try {
            return runDynamicStep(stepText, argumentText);
        } catch (Exception e) {
            return e;
        }
    }

    public static Object runDynamicStep(String stepText) {
        return runDynamicStep(stepText, "");
    }

    public static Object runDynamicStep(String stepText, String argumentText) {
        argumentText = argumentText == null || argumentText.isBlank() ? "" : argumentText;
        StepExtension currentStep = getRunningStep();
        try {
            StepExtension modifiedStep = new StepExtension(currentStep.testCase, getPickleStepTestStepFromStrings(getKeyWord(currentStep), stepText, argumentText));
            modifiedStep.setStepParsingMap(getRunningParsingMap());
            return modifiedStep.runAndGetReturnValue();
        } catch (Throwable t) {
            throw new StepCreationException("Failed to create Step '" + stepText + "'" + (argumentText.isBlank() ? " with argument '" + argumentText + "'" : "") + t.getMessage(), t);
        }
    }

    public static ScenarioStep getScenarioFromTag(String tags) {
        return getScenariosFromTag(tags).getFirst();
    }

    public static List<ScenarioStep> getScenariosFromTag(String tags) {
        StepExtension currentStep = getRunningStep();
        try {
            StepExtension modifiedStep = new StepExtension(currentStep.testCase, getPickleStepTestStepFromStrings(getKeyWord(currentStep), "RUN SCENARIOS" + tags, ""));
            modifiedStep.setStepParsingMap(getRunningParsingMap());
            modifiedStep.initializeChildSteps();
//            setStepAndDescendantsToNoLog(modifiedStep);
            return modifiedStep.childSteps.stream().filter(childStep -> childStep instanceof ScenarioStep).map(childStep -> (ScenarioStep) childStep).collect(Collectors.toList());
        } catch (Throwable t) {
            throw new StepCreationException("Failed to create scenario for tags '" + tags + "'");
        }
    }

    public static Object runScenarioFromTag(String tags) {
        return getScenarioFromTag(tags).runAndGetReturnValue();
    }

    public static List<Object> runScenariosFromTag(String tags) {
        return getScenariosFromTag(tags).stream().map(StepExtension::runAndGetReturnValue).collect(Collectors.toList());
    }

    private static void setStepAndDescendantsToNoLog(StepExtension step) {
        step.addDefinitionFlag(DefinitionFlag.NO_LOGGING);
        step.childSteps.forEach(childStep -> setStepAndDescendantsToNoLog((StepExtension) childStep));
    }

}
