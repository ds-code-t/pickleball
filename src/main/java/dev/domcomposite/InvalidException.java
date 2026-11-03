package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** CLI {@code code} {@code invalid}. The arguments disagree. */
public final class InvalidException extends CompositeException {
    public InvalidException(String message, int exitCode, JsonNode result, JsonNode error) {
        super("invalid", message, exitCode, result, error);
    }

    static InvalidException local(String message) {
        ObjectNode err = Json.MAPPER.createObjectNode();
        err.put("ok", false);
        err.put("error", message);
        err.put("code", "invalid");
        return new InvalidException(message, 1, NullNode.getInstance(), err);
    }
}
