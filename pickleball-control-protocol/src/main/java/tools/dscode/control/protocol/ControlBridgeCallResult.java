
package tools.dscode.control.protocol;

public record ControlBridgeCallResult(
        String status,
        String valueType,
        String valueText,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
