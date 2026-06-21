package tools.dscode.common.reporting.logging;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.testengine.PickleballRunner;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioLogRoot;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningPhrase;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.pickleballLog;

public class LogForwarder {

    public static final Entry defaultEntry = Entry.of("default");

    private static final ThreadLocal<Entry> threadLocalEntry = ThreadLocal.withInitial(() -> defaultEntry);

    public static Entry getDefaultEntry() {
        Entry entry = threadLocalEntry.get();

        if (entry == null) {
            entry = closestEntryToScenario();
            threadLocalEntry.set(entry);
        }

        return entry;
    }

    private static Entry getExistingEntryOnly() {
        Entry entry = threadLocalEntry.get();
        return entry == null ? defaultEntry : entry;
    }

    public static Level getDefaultLoggingLevel() {
        Level level = getDefaultEntry().level;
        return level == null ? Level.INFO : level;
    }

    public static void setDefaultEntry(Entry entry) {
        threadLocalEntry.set(entry);
    }


    public static void logToDefaultLevel(String message) {
        switch (getDefaultLoggingLevel()) {
            case TRACE -> logTrace(message);
            case DEBUG -> logDebug(message);
            case INFO -> logInfo(message);
            case WARN -> logWarn(message);
            case ERROR -> logError(message);
        }
    }

    public static boolean globalStateInitialized = false;

    public static Level getGlobalLogLevel() {
        Level level = PickleballRunner.LOG_LEVEL;
        return level == null ? Level.INFO : level;
    }

    private static Boolean debugLoggingEnabled;


    public static boolean shouldLog(Level messageLevel) {
        return messageLevel.ordinal() >= getGlobalLogLevel().ordinal();
    }

    public static Entry logTrace(String message) {
        if (!shouldLog(Level.TRACE)) return getExistingEntryOnly();
        return getDefaultEntry().trace(message);
    }

    public static Entry logDebug(String message) {
        if (!shouldLog(Level.DEBUG)) return getExistingEntryOnly();
        return getDefaultEntry().debug(message);
    }

    public static Entry logInfo(String message) {
        if (!shouldLog(Level.INFO)) return getExistingEntryOnly();
        return getDefaultEntry().info(message);
    }

    public static Entry logWarn(String message) {
        if (!shouldLog(Level.WARN)) return getExistingEntryOnly();
        return getDefaultEntry().warn(message);
    }

    public static Entry logError(String message) {
        if (!shouldLog(Level.ERROR)) return getExistingEntryOnly();
        return getDefaultEntry().error(message);
    }

    public static Entry logFail(String message) {
        if (!shouldLog(Level.ERROR)) return getExistingEntryOnly();
        return getDefaultEntry().fail(message);
    }

    public static Entry logSkip(String message) {
        if (!shouldLog(Level.WARN)) return getExistingEntryOnly();
        return getDefaultEntry().logSkipped(message);
    }


    public static Entry closestEntryToScenario() {
        if (!globalStateInitialized) return defaultEntry;

        CurrentScenarioState currentScenarioState = getCurrentScenarioState();

        if (currentScenarioState == null || currentScenarioState.scenarioLog == null)
            return pickleballLog;

        return currentScenarioState.scenarioLog;
    }


    public static Entry closestEntryToStep() {
        if (!globalStateInitialized) return defaultEntry;

        CurrentScenarioState currentScenarioState = getCurrentScenarioState();

        if (currentScenarioState == null || currentScenarioState.scenarioLog == null)
            return pickleballLog;

        StepExtension currentStep = getRunningStep();

        if (currentStep == null || currentStep.stepEntry == null)
            return currentScenarioState.scenarioLog;

        return currentStep.stepEntry;
    }

    public static Entry closestEntryToPhrase() {
        if (!globalStateInitialized) return defaultEntry;

        Phrase currentPhrase = getRunningPhrase();

        if (currentPhrase == null || currentPhrase.phraseEntry == null)
            return closestEntryToStep();

        return currentPhrase.phraseEntry;
    }

    public static Entry getParentEntryForStep(StepExtension stepExtension)
    {
        System.out.println("@@getParentEntryForStep: " + stepExtension);
        Level stepLogLevel = stepExtension.stepLogLevel;

        if(stepLogLevel == Level.DEBUG)
            return (stepExtension.parentStep == null || ((StepExtension) stepExtension.parentStep).stepEntry == null) ?  getScenarioLogRoot()  : ((StepExtension) stepExtension.parentStep).stepEntry;

        StepExtension currentStep = stepExtension;

        while ((currentStep = (StepExtension) currentStep.parentStep) != null) {
            if(currentStep.stepEntry  == null)
                return pickleballLog;
            if(currentStep.stepLogLevel == Level.INFO) {
                return currentStep.stepEntry;
            }
            if (currentStep.parentStep == null || ((StepExtension) currentStep.parentStep).stepEntry == null) {
                return getScenarioLogRoot();
            }
        }
        throw new RuntimeException("Could not fine parent Entry for step: " + stepExtension);
    }


}
