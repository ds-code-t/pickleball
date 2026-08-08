package tools.dscode.common.variables;

import tools.dscode.common.reporting.logging.Entry;

public aspect PlatformLogAspect {
    String around():
            execution(public String tools.dscode.common.variables.PlatformSnapshot.InitiatorSnapshot.toString()) {
        return PlatformLogFormatter.format(proceed());
    }

    Entry around(String message):
            call(public static tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.LogForwarder.logDebug(String))
            && within(tools.dscode.common.variables.PlatformSnapshot)
            && args(message) {
        if (PlatformLogFormatter.isDisabled()) return null;
        return proceed(PlatformLogFormatter.formatPlatformData(message));
    }

    Entry around(Entry entry, String message):
            call(public tools.dscode.common.reporting.logging.Entry tools.dscode.common.reporting.logging.Entry.info(String))
            && target(entry) && args(message)
            && (within(io.cucumber.core.runner.GlobalState)
                || withincode(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())) {
        if (PlatformLogFormatter.isDisabledMarker(message)) return entry;
        return proceed(entry, message);
    }
}
