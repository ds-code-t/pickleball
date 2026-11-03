package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/** CLI {@code code} {@code not_found}. The named target is missing. */
public final class NotFoundException extends CompositeException {
    public NotFoundException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("not_found", message, exitCode, result, error);
    }
}
