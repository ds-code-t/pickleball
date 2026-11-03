package tools.dscode.control.protocol;

import java.util.List;

/** Bounded read-only evidence from the browser already owned by one scenario. */
public record ControlBridgeBrowserPage(
        String url,
        String title,
        String windowHandle,
        List<String> windowHandles,
        int windowWidth,
        int windowHeight,
        String pageSource,
        boolean pageSourceTruncated,
        String cdpDebuggerAddress,
        String browserType
) {
    public ControlBridgeBrowserPage {
        windowHandles = windowHandles == null ? List.of() : List.copyOf(windowHandles);
        cdpDebuggerAddress = blank(cdpDebuggerAddress);
        browserType = blank(browserType);
    }

    private static String blank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
