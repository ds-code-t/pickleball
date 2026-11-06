package tools.dscode.control.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded read-only evidence for one element resolved by the consumer worker. */
public record ControlBridgeElementEvidence(
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
        java.util.List<String> humanCodes,
        String humanSentence,
        boolean visualOnly
) {
    public ControlBridgeElementEvidence {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        humanCodes = humanCodes == null ? java.util.List.of() : java.util.List.copyOf(humanCodes);
        handle = handle == null ? "" : handle;
        humanSentence = humanSentence == null ? "" : humanSentence;
    }

    public ControlBridgeElementEvidence(
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
                attributes, outerHtml, outerHtmlTruncated, "", null, null, java.util.List.of(), "", false);
    }
}
