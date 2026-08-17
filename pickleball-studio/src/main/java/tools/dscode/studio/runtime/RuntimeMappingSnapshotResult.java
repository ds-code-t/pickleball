package tools.dscode.studio.runtime;

/** Logical result of capturing and retaining one live mapping snapshot in Studio. */
public record RuntimeMappingSnapshotResult(
        String status,
        RuntimeMappingSnapshot snapshot,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
