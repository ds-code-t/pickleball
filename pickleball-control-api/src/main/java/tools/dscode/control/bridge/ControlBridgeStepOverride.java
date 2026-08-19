package tools.dscode.control.bridge;

public record ControlBridgeStepOverride(
        String id,
        String patternType,
        String pattern,
        String handlerClass
) {
}
