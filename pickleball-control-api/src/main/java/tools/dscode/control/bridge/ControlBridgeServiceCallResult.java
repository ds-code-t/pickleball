package tools.dscode.control.bridge;

import tools.dscode.control.api.ServiceCallEvidence;

public record ControlBridgeServiceCallResult(
        String status,
        ServiceCallEvidence evidence,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
