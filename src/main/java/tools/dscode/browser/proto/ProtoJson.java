package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared JSON helpers for the protocol host. */
public final class ProtoJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtoJson() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) if (pairs[i + 1] != null) out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return out;
    }

    public static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception failure) {
            throw new IllegalArgumentException("error: not JSON: " + failure.getMessage(), failure);
        }
    }

    /** A flag value that is inline JSON or {@code @file}. */
    public static JsonNode inlineOrFile(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.startsWith("@")) {
            try {
                text = java.nio.file.Files.readString(java.nio.file.Path.of(text.substring(1)));
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("error: cannot read " + text.substring(1) + ": " + failure.getMessage());
            }
        }
        return read(text);
    }
}
