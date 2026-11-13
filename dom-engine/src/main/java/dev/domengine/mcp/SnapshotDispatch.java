package dev.domengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

/**
 * MCP {@code snapshot} body. Calls G3 {@code CompositeSnapshots.browser},
 * {@code desktop}, or {@code offline} when that class is on the classpath and
 * returns that JSON unchanged. Until then this is a schema-shaped placeholder
 * ({@code pickleball.composite-snapshot} version 1). It does not build a snapshot itself.
 */
public final class SnapshotDispatch {
    private SnapshotDispatch() {
    }

    public static ObjectNode render(JsonNode arguments) {
        ObjectNode produced = invoke(arguments);
        return produced == null ? placeholder(arguments) : produced;
    }

    static boolean producersPresent() {
        try {
            Class.forName("dev.domengine.snapshot.CompositeSnapshots");
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    private static ObjectNode invoke(JsonNode arguments) {
        Class<?> type;
        try {
            type = Class.forName("dev.domengine.snapshot.CompositeSnapshots");
        } catch (ClassNotFoundException failure) {
            return null;
        }
        String kind = kind(arguments);
        Method method = method(type, kind);
        if (method == null) {
            throw new IllegalStateException(
                    "error: CompositeSnapshots." + kind + " is missing.\n"
                            + "fix: G3 owns browser, desktop, and offline.");
        }
        Object[] params = adapt(method.getParameterTypes(), arguments);
        if (params == null) {
            throw new IllegalArgumentException(
                    "error: snapshot " + kind + " needs " + java.util.Arrays.toString(method.getParameterTypes()) + ".\n"
                            + "fix: Pass session, file, html, or the JSON arguments that method declares.");
        }
        try {
            Object value = method.invoke(null, params);
            return asObject(value);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException(
                    "error: CompositeSnapshots." + kind + " failed: " + cause.getMessage() + "\n"
                            + "fix: Read the producer error. The MCP command does not rebuild the snapshot.",
                    cause);
        }
    }

    private static String kind(JsonNode arguments) {
        String kind = text(arguments, "kind");
        if (!kind.isBlank()) return kind;
        if (arguments != null && (arguments.hasNonNull("session") || arguments.hasNonNull("cdp"))) return "browser";
        String platform = text(arguments, "platform");
        if (platform.contains("uia") || platform.contains("ax") || platform.contains("atspi")
                || (arguments != null && arguments.hasNonNull("desktop"))) {
            return "desktop";
        }
        return "offline";
    }

    private static Method method(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())) return method;
        }
        return null;
    }

    private static Object[] adapt(Class<?>[] types, JsonNode arguments) {
        Object[] params = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object value = adaptOne(types[i], arguments);
            if (value == UNADAPTED) {
                if (!types[i].isPrimitive()) {
                    params[i] = null;
                    continue;
                }
                return null;
            }
            params[i] = value;
        }
        return params;
    }

    private static final Object UNADAPTED = new Object();

    private static Object adaptOne(Class<?> type, JsonNode arguments) {
        if (type == JsonNode.class || ObjectNode.class.isAssignableFrom(type)) return arguments;
        if (type == String.class) {
            for (String key : List.of("session", "id", "file", "html", "cdp")) {
                String value = text(arguments, key);
                if (!value.isBlank()) return value;
            }
            return "";
        }
        try {
            Method factory = type.getMethod("from", JsonNode.class);
            if (Modifier.isStatic(factory.getModifiers())) return factory.invoke(null, arguments);
        } catch (ReflectiveOperationException ignored) {
            // No JSON factory.
        }
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ignored) {
            return UNADAPTED;
        }
    }

    private static ObjectNode asObject(Object value) {
        if (value instanceof ObjectNode node) return node;
        if (value instanceof JsonNode node && node.isObject()) return (ObjectNode) node;
        try {
            if (value instanceof String text) return (ObjectNode) Jsons.MAPPER.readTree(text);
            JsonNode tree = Jsons.MAPPER.valueToTree(value);
            if (tree instanceof ObjectNode node) return node;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "error: CompositeSnapshots JSON could not be read.\nfix: The producer must return an object.",
                    failure);
        }
        throw new IllegalStateException(
                "error: CompositeSnapshots did not return an object.\nfix: The producer must return the snapshot object.");
    }

    static ObjectNode placeholder(JsonNode arguments) {
        ObjectNode node = Jsons.object();
        node.put("schema", "pickleball.composite-snapshot");
        node.put("version", 1);
        node.put("id", "placeholder");
        node.put("session_id", text(arguments, "session"));
        node.put("doc_version", "");
        node.put("platform", text(arguments, "platform").isBlank() ? "chromium" : text(arguments, "platform"));
        node.put("transport", "fixture");
        node.put("captured_at", Instant.now().toString());
        node.put("duration_ms", 0);
        ObjectNode viewport = node.putObject("viewport");
        viewport.put("css_width", 0);
        viewport.put("css_height", 0);
        viewport.put("device_pixel_ratio", 1);
        viewport.put("scroll_x", 0);
        viewport.put("scroll_y", 0);
        ObjectNode visual = viewport.putObject("visual");
        visual.put("offset_x", 0);
        visual.put("offset_y", 0);
        visual.put("scale", 1);
        ObjectNode screen = viewport.putObject("screen");
        screen.put("x", 0);
        screen.put("y", 0);
        screen.put("width", 0);
        screen.put("height", 0);
        ObjectNode screenshot = node.putObject("screenshot");
        screenshot.put("format", "png");
        screenshot.put("width_px", 0);
        screenshot.put("height_px", 0);
        screenshot.put("image_scale", 1);
        screenshot.putNull("clip");
        screenshot.put("sha256", "");
        ObjectNode source = node.putObject("source");
        source.put("kind", "html");
        source.put("text", "");
        source.put("bytes", 0);
        node.putArray("frames");
        node.putArray("nodes");
        ObjectNode premap = node.putObject("premap");
        premap.putArray("sections");
        premap.putArray("labels");
        premap.putArray("unlabeled");
        premap.putObject("boxes");
        premap.putObject("viewport_map");
        ObjectNode indexes = premap.putObject("indexes");
        for (String key : List.of(
                "by_role", "by_role_name", "by_text_token", "by_tag", "by_id", "by_class",
                "by_xpath", "by_section_title", "interactables", "in_viewport")) {
            indexes.putArray(key);
        }
        premap.putObject("image");
        premap.putObject("colours");
        node.putArray("warnings").add(
                "g3-producers-absent: CompositeSnapshots is not on the classpath; schema-shaped placeholder");
        return node;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return "";
        return node.get(field).asText("");
    }
}
