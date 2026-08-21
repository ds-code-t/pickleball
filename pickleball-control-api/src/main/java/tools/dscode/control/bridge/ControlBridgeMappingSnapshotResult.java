package tools.dscode.control.bridge;

/** @deprecated Wire controllers use {@code tools.dscode.control.protocol}. */
@Deprecated(forRemoval = false)
public record ControlBridgeMappingSnapshotResult(
        String status,
        ControlBridgeMappingSnapshot snapshot,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
