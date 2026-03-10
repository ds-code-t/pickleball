package tools.dscode.common.reporting.logging;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningPhrase;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.pickleballLog;

public class LogForwarder {


    public static Entry closestEntryToScenario() {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if (currentScenarioState == null || currentScenarioState.scenarioLog == null)
            return pickleballLog;
        return currentScenarioState.scenarioLog;
    }


    public static Entry closestEntryToStep() {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if (currentScenarioState == null || currentScenarioState.scenarioLog == null)
            return pickleballLog;
        StepExtension currentStep = getRunningStep();
        if (currentStep == null || currentStep.stepEntry == null)
            return currentScenarioState.scenarioLog;
        return currentStep.stepEntry;
    }

    public static Entry closestEntryToPhrase() {
        Phrase currentPhrase = getRunningPhrase();
        if (currentPhrase == null || currentPhrase.phraseEntry == null)
            return closestEntryToStep();
        return currentPhrase.phraseEntry;
    }

    // phrase

    public static Entry phraseFail(String message) {
        return closestEntryToPhrase().fail(message);
    }

    public static Entry phraseInfo(String message) {
        return closestEntryToPhrase().info(message);
    }

    public static Entry phraseError(String message) {
        return closestEntryToPhrase().error(message);
    }

    public static Entry phraseWarn(String message) {
        return closestEntryToPhrase().warn(message);
    }

    public static Entry phraseTrace(String message) {
        return closestEntryToPhrase().trace(message);
    }

    public static Entry phraseDebug(String message) {
        return closestEntryToPhrase().debug(message);
    }

    //step

    public static Entry stepFail(String message) {
        return closestEntryToStep().fail(message);
    }

    public static Entry stepInfo(String message) {
        return closestEntryToStep().info(message);
    }

    public static Entry stepError(String message) {
        return closestEntryToStep().error(message);
    }

    public static Entry stepWarn(String message) {
        return closestEntryToStep().warn(message);
    }

    public static Entry stepTrace(String message) {
        return closestEntryToStep().trace(message);
    }

    public static Entry stepDebug(String message) {
        return closestEntryToStep().debug(message);
    }


    // Scenario

    public static Entry scenarioFail(String message) {
        return closestEntryToScenario().fail(message);
    }

    public static Entry scenarioInfo(String message) {
        return closestEntryToScenario().info(message);
    }

    public static Entry scenarioError(String message) {
        return closestEntryToScenario().error(message);
    }

    public static Entry scenarioWarn(String message) {
        return closestEntryToScenario().warn(message);
    }

    public static Entry scenarioTrace(String message) {
        return closestEntryToScenario().trace(message);
    }

    public static Entry scenarioDebug(String message) {
        return closestEntryToScenario().debug(message);
    }


    //  pickleball root logging

    public static Entry runFail(String message) {
        return pickleballLog.fail(message);
    }


    public static Entry runInfo(String message) {
        return pickleballLog.info(message);
    }

    public static Entry runError(String message) {
        return pickleballLog.error(message);
    }

    public static Entry runWarn(String message) {
        return pickleballLog.warn(message);
    }

    public static Entry runTrace(String message) {
        return pickleballLog.trace(message);
    }

    public static Entry runDebug(String message) {
        return pickleballLog.debug(message);
    }

}
