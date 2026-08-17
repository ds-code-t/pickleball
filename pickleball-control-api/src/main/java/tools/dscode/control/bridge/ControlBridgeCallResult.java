
package tools.dscode.control.bridge;

public record ControlBridgeCallResult(
        String status,
        String valueType,
        String valueText,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
