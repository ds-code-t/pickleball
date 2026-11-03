package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/** CLI {@code code} {@code stale}. A stored node no longer maps onto the live page. */
public final class StaleException extends CompositeException {
    public StaleException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("stale", message, exitCode, result, error);
    }
}
