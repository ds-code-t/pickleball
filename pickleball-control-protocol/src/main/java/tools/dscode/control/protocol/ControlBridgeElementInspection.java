package tools.dscode.control.protocol;

import java.util.List;

/** Pickleball-native element-resolution evidence represented only as wire data. */
public record ControlBridgeElementInspection(
        String category,
        String text,
        String operation,
        String resolvedXPath,
        int matchCount,
        boolean evidenceTruncated,
        List<ControlBridgeElementEvidence> elements,
        String query
) {
    public ControlBridgeElementInspection {
        elements = elements == null ? List.of() : List.copyOf(elements);
        query = query == null ? "" : query;
    }

    public ControlBridgeElementInspection(
            String category,
            String text,
            String operation,
            String resolvedXPath,
            int matchCount,
            boolean evidenceTruncated,
            List<ControlBridgeElementEvidence> elements
    ) {
        this(category, text, operation, resolvedXPath, matchCount, evidenceTruncated, elements, "");
    }
}
