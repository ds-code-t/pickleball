package tools.dscode.studio.runtime;

/** One active Pickleball scenario exposed through a Studio runtime bridge. */
public record RuntimeScenarioStatus(
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
