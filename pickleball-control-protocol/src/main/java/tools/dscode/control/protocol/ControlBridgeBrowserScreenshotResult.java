package tools.dscode.control.protocol;

/** Logical result of capturing current browser screenshot evidence. */
public record ControlBridgeBrowserScreenshotResult(
        String status,
        ControlBridgeBrowserScreenshot screenshot,
        ControlBridgeError error,
        ControlBridgeStatus runtime
) {
}
