package tools.dscode.control.bridge;

/** Logical result of reading current browser page evidence. */
public record ControlBridgeBrowserPageResult(
        String status,
        ControlBridgeBrowserPage page,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
