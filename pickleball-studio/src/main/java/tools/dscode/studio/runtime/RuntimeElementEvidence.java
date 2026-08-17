package tools.dscode.studio.runtime;

import java.util.Map;

public record RuntimeElementEvidence(
        int index,
        String tagName,
        String text,
        String value,
        boolean displayed,
        boolean enabled,
        boolean selected,
        int x,
        int y,
        int width,
        int height,
        Map<String, String> attributes,
        String outerHtml,
        boolean outerHtmlTruncated
) { }
