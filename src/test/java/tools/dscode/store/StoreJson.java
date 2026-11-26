package tools.dscode.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Store {@code --json} is an agent envelope. The previous document is {@code items[0]}.
 */
final class StoreJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private StoreJson() {
    }

    static JsonNode read(String text) throws JsonProcessingException {
        JsonNode node = JSON.readTree(text);
        if (node != null && node.isObject()
                && node.path("v").asInt(-1) == 1
                && node.has("items")
                && node.get("items").isArray()
                && node.get("items").size() == 1) {
            return node.get("items").get(0);
        }
        return node;
    }
}
