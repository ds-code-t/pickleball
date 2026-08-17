package tools.dscode.studio.runtime;

record RuntimeBrowserScreenshotBridgeResult(
        String status,
        RuntimeBrowserScreenshotBridge screenshot,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
