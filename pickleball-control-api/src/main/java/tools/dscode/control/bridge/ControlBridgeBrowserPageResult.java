package tools.dscode.control.bridge;

/** @deprecated Wire controllers use {@code tools.dscode.control.protocol}. */
@Deprecated(forRemoval = false)
public record ControlBridgeBrowserPageResult(
        String status,
        ControlBridgeBrowserPage page,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
