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
 * Workbench {@code snapshot} command. Calls {@code SnapshotDispatch.render(JsonNode)}
 * when that class is visible, then {@code SnapshotProducers.page} only when the
 * dispatch class is absent and a page method can take these arguments. Otherwise
 * a schema-shaped placeholder. This module does not compile against dom-engine.
 */
final class SnapshotCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Class-name seam so a test can hide the dispatch class without dropping the jar. */
    static String dispatchClassName = "dev.domengine.mcp.SnapshotDispatch";
    static String producersClassName = "tools.dscode.common.seleniumextensions.SnapshotProducers";

    private SnapshotCommand() {
    }

    static Map<String, Object> render(Map<String, Object> arguments) {
        Object produced = callDispatch(arguments);
        if (produced == null) produced = callProducersPage(arguments);
        if (produced != null) return asMap(produced);
        return placeholder(arguments);
    }

    private static Object callDispatch(Map<String, Object> arguments) {
        Class<?> type;
        try {
            type = Class.forName(dispatchClassName);
        } catch (ClassNotFoundException failure) {
            return null;
        }
        Method method;
        try {
            method = type.getMethod("render", JsonNode.class);
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(
                    "error: " + dispatchClassName + ".render(JsonNode) is missing.\n"
                            + "fix: Call SnapshotDispatch.render, not an overload chosen by name.",
                    failure);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException(
                    "error: " + dispatchClassName + ".render(JsonNode) is not static.\n"
                            + "fix: Call the public static SnapshotDispatch.render method.");
        }
        try {
            return method.invoke(null, JSON.valueToTree(arguments == null ? Map.of() : arguments));
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException(
                    "error: " + dispatchClassName + ".render failed: " + cause.getMessage() + "\n"
                            + "fix: Read the producer error.",
                    cause);
        }
    }

    private static Object callProducersPage(Map<String, Object> arguments) {
        Class<?> type;
        try {
            type = Class.forName(producersClassName);
        } catch (ClassNotFoundException failure) {
            return null;
        }
        Method method = null;
        for (Method candidate : type.getMethods()) {
            if (!candidate.getName().equals("page") || !Modifier.isStatic(candidate.getModifiers())) continue;
            if (!adaptable(candidate)) continue;
            method = candidate;
            break;
        }
        if (method == null) return null;
        try {
            return method.getParameterCount() == 0 ? method.invoke(null) : method.invoke(null, adapt(method, arguments));
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException(
                    "error: " + producersClassName + ".page failed: " + cause.getMessage() + "\n"
                            + "fix: Read the producer error.",
                    cause);
        }
    }

    private static boolean adaptable(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type != String.class && !Map.class.isAssignableFrom(type) && !JsonNode.class.isAssignableFrom(type)) {
                return false;
            }
        }
        return true;
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
