package tools.dscode.studio.runtime;

public record RuntimeBrowserScreenshotResult(
        String status,
        RuntimeBrowserScreenshot screenshot,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
