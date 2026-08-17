package tools.dscode.studio.runtime;

import java.util.List;

public record RuntimeBrowserPage(
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
