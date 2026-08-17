package tools.dscode.studio.runtime;

/** Studio-owned, session-scoped handle for one captured live mapping state. */
public record RuntimeMappingSnapshot(
        String snapshotId,
        String capturedAt,
        String sessionId,
        String runtimeId,
        String scenarioId,
        RuntimeMappingState state
) {
    public RuntimeMappingSnapshot {
        if (state == null) {
            throw new IllegalArgumentException("Runtime mapping snapshot state must not be null.");
        }
    }

    public String mapReference() {
        return state.mapReference();
    }

    public String mapType() {
        return state.mapType();
    }

    public String mapClass() {
        return state.mapClass();
    }

    public boolean restorable() {
        return state.restorable();
    }
}
