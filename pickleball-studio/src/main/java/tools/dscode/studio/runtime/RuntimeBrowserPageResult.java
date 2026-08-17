package tools.dscode.studio.runtime;

public record RuntimeBrowserPageResult(
        String status,
        RuntimeBrowserPage page,
        RuntimeBridgeError error,
        RuntimeBridgeStatus runtime
) {
}
