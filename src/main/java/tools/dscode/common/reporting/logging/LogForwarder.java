package tools.dscode.common.reporting.logging;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
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

}
