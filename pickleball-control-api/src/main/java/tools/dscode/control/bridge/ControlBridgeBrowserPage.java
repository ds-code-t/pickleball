package tools.dscode.control.bridge;

import java.util.List;

/** @deprecated Wire controllers use {@code tools.dscode.control.protocol}. */
@Deprecated(forRemoval = false)
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
}
