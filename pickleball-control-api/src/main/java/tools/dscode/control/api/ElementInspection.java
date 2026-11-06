package tools.dscode.control.api;

import java.util.List;

/** Pickleball-native element-resolution result suitable for tooling and bridge transport. */
public record ElementInspection(
        String category,
        String text,
        String operation,
        String resolvedXPath,
        int matchCount,
        boolean evidenceTruncated,
        List<ElementEvidence> elements,
        String query
) {
    public ElementInspection(
            String category,
            String text,
            String operation,
            String resolvedXPath,
            int matchCount,
            boolean evidenceTruncated,
            List<ElementEvidence> elements
    ) {
        this(category, text, operation, resolvedXPath, matchCount, evidenceTruncated, elements, "");
    }
}
