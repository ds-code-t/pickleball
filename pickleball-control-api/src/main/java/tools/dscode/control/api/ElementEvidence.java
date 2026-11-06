package tools.dscode.control.api;

import java.util.List;
import java.util.Map;

/** Bounded read-only evidence for one element matched through the shared query path. */
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
        boolean outerHtmlTruncated,
        String handle,
        Integer rank,
        Double score,
        List<String> humanCodes,
        String humanSentence,
        boolean visualOnly
) {
    public ElementEvidence(
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
        this(index, tagName, text, value, displayed, enabled, selected, x, y, width, height,
                attributes, outerHtml, outerHtmlTruncated, "", null, null, List.of(), "", false);
    }
}