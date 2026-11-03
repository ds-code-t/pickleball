package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/** CLI {@code code} {@code usage}. Exit 2: unknown command or a missing flag. */
public final class UsageException extends CompositeException {
    public UsageException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("usage", message, exitCode, result, error);
    }
}
