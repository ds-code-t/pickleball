package tools.dscode.control.bridge;

/** @deprecated Wire controllers use {@code tools.dscode.control.protocol}. */
@Deprecated(forRemoval = false)
public record ControlBridgeValueResult(
        String status,
        ControlBridgeValue value,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
