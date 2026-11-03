package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/** CLI {@code code} {@code timeout}. A wait expired. */
public final class TimeoutException extends CompositeException {
    public TimeoutException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("timeout", message, exitCode, result, error);
    }
}
