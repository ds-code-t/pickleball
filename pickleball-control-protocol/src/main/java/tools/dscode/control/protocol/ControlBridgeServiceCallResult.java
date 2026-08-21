package tools.dscode.control.protocol;

public record ControlBridgeServiceCallResult(
        String status,
        ControlBridgeServiceCallEvidence evidence,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
