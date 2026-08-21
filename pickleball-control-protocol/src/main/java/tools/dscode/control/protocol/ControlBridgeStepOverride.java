package tools.dscode.control.protocol;

public record ControlBridgeStepOverride(
        String id,
        String patternType,
        String pattern,
        String handlerClass
) {
}
