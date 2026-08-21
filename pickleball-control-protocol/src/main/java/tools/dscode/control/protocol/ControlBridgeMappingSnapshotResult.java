package tools.dscode.control.protocol;

/** Logical result of capturing one live NodeMap snapshot. */
public record ControlBridgeMappingSnapshotResult(
        String status,
        ControlBridgeMappingSnapshot snapshot,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
