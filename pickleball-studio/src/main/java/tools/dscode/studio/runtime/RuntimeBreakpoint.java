package tools.dscode.studio.runtime;

public record RuntimeBreakpoint(
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
) { }
