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
        boolean outerHtmlTruncated
) {
    public ControlBridgeElementEvidence {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
