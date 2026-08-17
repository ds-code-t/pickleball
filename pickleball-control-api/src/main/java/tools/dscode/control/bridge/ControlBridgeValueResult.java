package tools.dscode.control.bridge;

/** Structured value result for live mapping inspection and mutation. */
public record ControlBridgeValueResult(
        String status,
        ControlBridgeValue value,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
