package tools.dscode.control.protocol;

public record ControlBridgeElementInspectionResult(
        String status,
        ControlBridgeElementInspection inspection,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
