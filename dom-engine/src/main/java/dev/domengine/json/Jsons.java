package dev.domengine.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Jsons {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsons() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
        } catch (Exception failure) {
            return String.valueOf(node) + "\n";
        }
    }

    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception failure) {
            return String.valueOf(node);
        }
    }

    public static ObjectNode error(String code, String message) {
        ObjectNode node = object();
        node.put("ok", false);
        node.put("error", message == null ? "" : message);
        node.put("code", code == null ? "failed" : code);
        return node;
    }
}
