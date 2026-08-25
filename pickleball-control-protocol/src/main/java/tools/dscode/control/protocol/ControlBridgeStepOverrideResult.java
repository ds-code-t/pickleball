package tools.dscode.control.protocol;

public record ControlBridgeStepOverrideResult(
        String status,
        ControlBridgeStepOverride override,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
