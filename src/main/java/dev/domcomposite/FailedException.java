package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** CLI {@code code} {@code failed}. The command or the stdio worker failed. */
public final class FailedException extends CompositeException {
    public FailedException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("failed", message, exitCode, result, error);
    }

    static FailedException worker(String message) {
        ObjectNode err = Json.MAPPER.createObjectNode();
        err.put("ok", false);
        err.put("error", message);
        err.put("code", "failed");
        return new FailedException(message, 1, NullNode.getInstance(), err);
    }
}
