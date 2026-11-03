package dev.domcomposite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * A CLI or HTTP failure. {@code code} is the stable name from the error JSON:
 * {@code not_found}, {@code ambiguous}, {@code stale}, {@code timeout},
 * {@code invalid}, {@code failed}, {@code usage}, or any other code the CLI
 * returns.
 *
 * <p>Exit 0 is not an exception (HTTP 200, the JSON body). Exit 2 is
 * {@code usage} (HTTP 400). Exit 1 is a command failure (HTTP 422), except a
 * dead stdio worker, which is {@code failed} (HTTP 500). An unknown HTTP route
 * is {@code not_found} (HTTP 404). Unauthorized HTTP is {@code invalid} (401).
 */
public class CompositeException extends RuntimeException {
    private final String code;
    private final int exitCode;
    private final JsonNode result;
    private final JsonNode error;

    public CompositeException(String code, String message, int exitCode, JsonNode result, JsonNode error) {
        super(message == null || message.isEmpty() ? "command failed" : message);
        this.code = code == null || code.isEmpty() ? "failed" : code;
        this.exitCode = exitCode;
        this.result = result == null ? NullNode.getInstance() : result;
        this.error = error == null ? NullNode.getInstance() : error;
    }

    public String code() {
        return code;
    }

    public int exitCode() {
        return exitCode;
    }

    /** Stdout JSON when the CLI printed a report and still failed. */
    public JsonNode result() {
        return result;
    }

    /** The stderr object ({@code ok}, {@code error}, {@code code}). */
    public JsonNode error() {
        return error;
    }

    public static CompositeException from(int exit, String stdout, String stderr) {
        JsonNode err = parseError(stderr);
        String code = err.path("code").asText("");
        if (code.isEmpty()) code = exit == 2 ? "usage" : "failed";
        String message = err.path("error").asText("");
        if (message.isEmpty()) {
            String text = stderr == null ? "" : stderr.trim();
            message = text.isEmpty() ? "command failed" : text;
        }
        JsonNode result = parseMaybe(stdout);
        return switch (code) {
            case "not_found" -> new NotFoundException(message, exit, result, err);
            case "ambiguous" -> new AmbiguousException(message, exit, result, err);
            case "stale" -> new StaleException(message, exit, result, err);
            case "timeout" -> new TimeoutException(message, exit, result, err);
            case "invalid" -> new InvalidException(message, exit, result, err);
            case "failed" -> new FailedException(message, exit, result, err);
            case "usage" -> new UsageException(message, exit, result, err);
            default -> new CompositeException(code, message, exit, result, err);
        };
    }

    static JsonNode parseError(String stderr) {
        if (stderr == null) return NullNode.getInstance();
        String text = stderr.trim();
        if (text.isEmpty()) return NullNode.getInstance();
        JsonNode whole = tryParse(text);
        if (whole != null && whole.isObject() && whole.has("code")) return whole;
        int nl = text.lastIndexOf("\n{");
        if (nl >= 0) {
            JsonNode tail = tryParse(text.substring(nl + 1));
            if (tail != null && tail.isObject()) return tail;
        }
        ObjectNode fallback = Json.MAPPER.createObjectNode();
        fallback.put("ok", false);
        fallback.put("error", text);
        fallback.put("code", "failed");
        return fallback;
    }

    static JsonNode parseMaybe(String stdout) {
        if (stdout == null) return NullNode.getInstance();
        String trimmed = stdout.trim();
        if (trimmed.isEmpty()) return NullNode.getInstance();
        JsonNode parsed = tryParse(trimmed);
        return parsed == null ? TextNode.valueOf(stdout) : parsed;
    }

    private static JsonNode tryParse(String text) {
        try {
            return Json.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
