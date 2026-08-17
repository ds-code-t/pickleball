package tools.dscode.studio.runtime;

import java.util.List;

public record RuntimeElementInspection(
        String category,
        String text,
        String operation,
        String resolvedXPath,
        int matchCount,
        boolean evidenceTruncated,
        List<RuntimeElementEvidence> elements
) { }
