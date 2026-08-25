package tools.dscode.control.bridge;

/** @deprecated Wire controllers use {@code tools.dscode.control.protocol}. */
@Deprecated(forRemoval = false)
public record ControlBridgeBreakpoint(
        String breakpointId,
        String scenarioId,
        String hook,
        String signatureContains,
        String stepContains,
        String phraseContains,
        boolean oneShot,
        int leaseSeconds,
        long hitCount,
        String lastHitAt,
        String lastScenarioId
) {
}
