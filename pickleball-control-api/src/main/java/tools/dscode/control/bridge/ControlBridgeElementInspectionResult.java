package tools.dscode.control.bridge;

import tools.dscode.control.api.ElementInspection;

public record ControlBridgeElementInspectionResult(
        String status,
        ElementInspection inspection,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
