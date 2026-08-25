package tools.dscode.control.protocol;

/** Immutable bounded snapshot of one semantic Pickleball control hook. */
public record ControlBridgeEvent(
        long sequence,
        String timestamp,
        long threadId,
        String scenarioId,
        String scenarioName,
        String hook,
        String signature,
        String stepText,
        String phraseText
) {
}
