package tools.dscode.control.protocol;

/** Logical result of reading current browser page evidence. */
public record ControlBridgeBrowserPageResult(
        String status,
        ControlBridgeBrowserPage page,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
