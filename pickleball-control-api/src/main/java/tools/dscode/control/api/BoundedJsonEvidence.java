package tools.dscode.control.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded JSON evidence transported without retaining arbitrary consumer object graphs. */
public record BoundedJsonEvidence(
        JsonNode value,
        int utf8Bytes,
        boolean truncated
) {
}
