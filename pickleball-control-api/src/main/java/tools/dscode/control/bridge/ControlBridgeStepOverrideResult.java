package tools.dscode.control.bridge;

public record ControlBridgeStepOverrideResult(
        String status,
        ControlBridgeStepOverride override,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
