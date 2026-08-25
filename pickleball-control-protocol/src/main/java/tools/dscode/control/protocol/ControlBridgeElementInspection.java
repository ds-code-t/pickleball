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
        List<ControlBridgeElementEvidence> elements
) {
    public ControlBridgeElementInspection {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }
}
