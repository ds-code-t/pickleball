package tools.dscode.control.api;

import java.util.Map;

/** Bounded read-only evidence for one element matched through Pickleball's execution dictionary. */
public record ElementEvidence(
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
) {
}
