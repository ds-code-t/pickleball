package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public record RuntimeBoundedJsonEvidence(
        JsonNode value,
        int utf8Bytes,
        boolean truncated
) { }
