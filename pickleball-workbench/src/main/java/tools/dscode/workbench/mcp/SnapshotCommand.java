package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workbench {@code snapshot} command. Calls G3 when those classes are visible
 * at runtime and returns their JSON unchanged. Otherwise a schema-shaped placeholder.
 * This module does not compile against dom-engine or the root runtime.
 */
final class SnapshotCommand {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SnapshotCommand() {
    }

    static Map<String, Object> render(Map<String, Object> arguments) {
        Object produced = call("dev.domengine.snapshot.CompositeSnapshots", arguments);
        if (produced == null) produced = call("tools.dscode.common.seleniumextensions.SnapshotProducers", arguments);
        if (produced != null) return asMap(produced);
        return placeholder(arguments);
    }

    private static Object call(String className, Map<String, Object> arguments) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException failure) {
            return null;
        }
        String kind = kind(arguments, className.endsWith("SnapshotProducers") ? "page" : "offline");
        Method method = null;
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(kind) && Modifier.isStatic(candidate.getModifiers())) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            throw new IllegalStateException(
                    "error: " + className + "." + kind + " is missing.\n"
                            + "fix: Call CompositeSnapshots.browser, desktop, or offline, or SnapshotProducers.page.");
        }
        try {
            Object value = method.getParameterCount() == 0 ? method.invoke(null) : method.invoke(null, adapt(method, arguments));
            return value;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "error: snapshot arguments do not match " + className + "." + kind + ".\n"
                            + "fix: Pass the arguments that method declares. The command does not rebuild the snapshot.",
                    failure);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException(
                    "error: " + className + "." + kind + " failed: " + cause.getMessage() + "\n"
                            + "fix: Read the producer error.",
                    cause);
        }
    }

    private static Object[] adapt(Method method, Map<String, Object> arguments) {
        Class<?>[] types = method.getParameterTypes();
        Object[] params = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                params[i] = first(arguments, "session", "id", "file", "html", "cdp");
            } else if (Map.class.isAssignableFrom(types[i])) {
                params[i] = arguments;
            } else if (JsonNode.class.isAssignableFrom(types[i])) {
                params[i] = JSON.valueToTree(arguments);
            } else {
                throw new IllegalArgumentException(
                        "error: cannot adapt " + types[i].getName() + ".\n"
                                + "fix: Pass arguments the snapshot method can take.");
            }
        }
        return params;
    }

    private static String kind(Map<String, Object> arguments, String fallback) {
        Object kind = arguments == null ? null : arguments.get("kind");
        if (kind != null && !String.valueOf(kind).isBlank()) return String.valueOf(kind);
        if (arguments != null && (arguments.get("session") != null || arguments.get("cdp") != null)) return "browser";
        return fallback;
    }

    private static String first(Map<String, Object> arguments, String... keys) {
        if (arguments == null) return "";
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        if (value instanceof String text) {
            try {
                return JSON.readValue(text, Map.class);
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "error: snapshot producer did not return an object.\nfix: Return CompositeSnapshot JSON.",
                        failure);
            }
        }
        return JSON.convertValue(value, Map.class);
    }

    static Map<String, Object> placeholder(Map<String, Object> arguments) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("schema", "pickleball.composite-snapshot");
        node.put("version", 1);
        node.put("id", "placeholder");
        node.put("session_id", first(arguments, "session"));
        node.put("doc_version", "");
        node.put("platform", first(arguments, "platform").isBlank() ? "chromium" : first(arguments, "platform"));
        node.put("transport", "fixture");
        node.put("captured_at", Instant.now().toString());
        node.put("duration_ms", 0);
        node.put("viewport", Map.of(
                "css_width", 0, "css_height", 0, "device_pixel_ratio", 1, "scroll_x", 0, "scroll_y", 0,
                "visual", Map.of("offset_x", 0, "offset_y", 0, "scale", 1),
                "screen", Map.of("x", 0, "y", 0, "width", 0, "height", 0)));
        Map<String, Object> screenshot = new LinkedHashMap<>();
        screenshot.put("format", "png");
        screenshot.put("width_px", 0);
        screenshot.put("height_px", 0);
        screenshot.put("image_scale", 1);
        screenshot.put("clip", null);
        screenshot.put("sha256", "");
        node.put("screenshot", screenshot);
        node.put("source", Map.of("kind", "html", "text", "", "bytes", 0));
        node.put("frames", List.of());
        node.put("nodes", List.of());
        Map<String, Object> indexes = new LinkedHashMap<>();
        for (String key : List.of(
                "by_role", "by_role_name", "by_text_token", "by_tag", "by_id", "by_class",
                "by_xpath", "by_section_title", "interactables", "in_viewport")) {
            indexes.put(key, List.of());
        }
        Map<String, Object> premap = new LinkedHashMap<>();
        premap.put("sections", List.of());
        premap.put("labels", List.of());
        premap.put("unlabeled", List.of());
        premap.put("boxes", Map.of());
        premap.put("viewport_map", Map.of());
        premap.put("indexes", indexes);
        premap.put("image", Map.of());
        premap.put("colours", Map.of());
        node.put("premap", premap);
        node.put("warnings", List.of(
                "g3-producers-absent: CompositeSnapshots and SnapshotProducers.page are not on the classpath; schema-shaped placeholder"));
        return node;
    }
}
