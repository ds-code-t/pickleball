package tools.dscode.studio.runtime;

public record RuntimeElementInspectionResult(
        String status,
        RuntimeElementInspection inspection,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) { }
