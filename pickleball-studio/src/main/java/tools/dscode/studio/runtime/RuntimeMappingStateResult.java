package tools.dscode.studio.runtime;

record RuntimeMappingStateResult(
        String status,
        RuntimeMappingState snapshot,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
