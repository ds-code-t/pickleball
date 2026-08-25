package tools.dscode.control.protocol;

/** Request DTOs for the local versioned controller/worker transport. */
public final class ControlBridgeRequests {
    private ControlBridgeRequests() {
    }

    public record PauseRequest(String scenarioId, Integer waitSeconds, Integer leaseSeconds) { }
    public record ResumeRequest(String scenarioId) { }
    public record ExecuteStepRequest(
            String scenarioId, String text, String argument, Integer timeoutSeconds
    ) { }
    public record MappingGetRequest(
            String scenarioId, String mapReference, String key, Integer timeoutSeconds
    ) { }
    public record MappingPutRequest(
            String scenarioId, String mapReference, String key, Object value, Integer timeoutSeconds
    ) { }
    public record MappingResolveRequest(
            String scenarioId, String input, Integer timeoutSeconds
    ) { }
    public record MappingSnapshotRequest(
            String scenarioId, String mapReference, Integer timeoutSeconds
    ) { }
    public record MappingRestoreRequest(
            String scenarioId, ControlBridgeMappingSnapshot snapshot, Integer timeoutSeconds
    ) { }
    public record BrowserEvidenceRequest(String scenarioId, Integer timeoutSeconds) { }
    public record ElementInspectionRequest(
            String scenarioId,
            String category,
            String text,
            String operation,
            Integer maxElements,
            Integer timeoutSeconds
    ) { }
    public record ServiceCallRequest(String scenarioId, String selector, Integer timeoutSeconds) { }
    public record BreakpointAddRequest(
            String scenarioId,
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            Boolean oneShot,
            Integer leaseSeconds
    ) { }
    public record BreakpointIdRequest(String breakpointId) { }
    public record StepOverrideCompileRequest(
            String scenarioId, String id, String patternType, String pattern, String source
    ) { }
    public record StepOverrideIdRequest(String scenarioId, String id) { }
    public record StepOverrideScenarioRequest(String scenarioId) { }
}
