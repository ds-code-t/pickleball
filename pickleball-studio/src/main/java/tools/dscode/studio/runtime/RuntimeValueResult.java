package tools.dscode.studio.runtime;

public record RuntimeValueResult(
        String status,
        RuntimeValue value,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
