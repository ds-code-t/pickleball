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
        boolean pageSourceTruncated
) {
    public ControlBridgeBrowserPage {
        windowHandles = windowHandles == null ? List.of() : List.copyOf(windowHandles);
    }
}
