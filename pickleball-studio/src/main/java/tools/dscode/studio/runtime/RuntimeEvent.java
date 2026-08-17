package tools.dscode.studio.runtime;

/** Immutable semantic hook snapshot retained by a live consumer runtime. */
public record RuntimeEvent(
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
