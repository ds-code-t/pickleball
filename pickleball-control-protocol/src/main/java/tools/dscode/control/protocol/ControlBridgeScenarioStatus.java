package tools.dscode.control.protocol;

/** One active Pickleball scenario observed by a consumer runtime bridge. */
public record ControlBridgeScenarioStatus(
        long threadId,
        String scenarioId,
        String scenarioName,
        String stepText,
        String phraseText,
        String lastHook,
        String lastSignature,
        boolean paused,
        boolean pauseRequested
) {
}
