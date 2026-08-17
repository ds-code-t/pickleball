package tools.dscode.control.bridge;

/** Bounded PNG evidence captured from the browser already owned by one scenario. */
public record ControlBridgeBrowserScreenshot(
        String mimeType,
        int byteSize,
        String base64
) {
}
