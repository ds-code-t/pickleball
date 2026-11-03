package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/** CLI {@code code} {@code ambiguous}. More than one node matched. */
public final class AmbiguousException extends CompositeException {
    public AmbiguousException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("ambiguous", message, exitCode, result, error);
    }
}
