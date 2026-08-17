package tools.dscode.studio.runtime;

public record RuntimeServiceCallResult(
        String status,
        RuntimeServiceCallEvidence evidence,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) { }
