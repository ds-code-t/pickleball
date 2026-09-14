package tools.dscode.control.api;

import com.fasterxml.jackson.databind.JsonNode;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped STEP_MAP values authored in Workbench before a Gherkin step
 * first runs. The worker copies them onto the live step NodeMap at execute
 * time; this is not a second Mapping store and does not change inheritance.
 */
public final class StepMapSeeds {
    static final String PREFIX = "__pickleball_workbench_step_seed__:";
    private static final ConcurrentHashMap<String, NodeMap> SEEDS = new ConcurrentHashMap<>();

    private StepMapSeeds() {
    }

    public static boolean isSeedReference(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    public static String referenceFor(String stepText) {
        String normalized = normalize(stepText);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return PREFIX + encoded;
    }

    public static String stepTextOf(String reference) {
        if (!isSeedReference(reference)) return "";
        try {
            return new String(
                    java.util.Base64.getUrlDecoder().decode(reference.substring(PREFIX.length())),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static NodeMap mapFor(String stepText) {
        String key = normalize(stepText);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Step text for a STEP_MAP seed must not be blank.");
        }
        return SEEDS.computeIfAbsent(key, ignored -> new NodeMap(MapConfigurations.MapType.STEP_MAP));
    }

    static String normalize(String stepText) {
        String trimmed = stepText == null ? "" : stepText.strip();
        for (String keyword : new String[]{"Given ", "When ", "Then ", "And ", "But ", "* "}) {
            if (trimmed.startsWith(keyword)) {
                return trimmed.substring(keyword.length()).strip();
            }
        }
        return trimmed;
    }

    public static NodeMap mapForReference(String reference) {
        String text = stepTextOf(reference);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Invalid STEP_MAP seed reference: " + reference);
        }
        return mapFor(text);
    }

    public static void apply(NodeMap target, String stepText) {
        if (target == null) return;
        NodeMap seed = SEEDS.get(normalize(stepText));
        if (seed == null) return;
        seed.getRoot().fields().forEachRemaining(field -> {
            if (field.getKey() == null || NodeMap.MAP_TYPE_KEY.equals(field.getKey())) return;
            target.put(field.getKey(), jsonValue(field.getValue()));
        });
    }

    public static void clear() {
        SEEDS.clear();
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt() || node.isLong()) return node.longValue();
        if (node.isNumber()) return node.doubleValue();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            node.forEach(child -> list.add(jsonValue(child)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(field -> map.put(field.getKey(), jsonValue(field.getValue())));
            return map;
        }
        return node.asText();
    }
}