package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.store.StoreSecrets;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redaction for browser event rows, replies and logs: Authorization, Cookie, Set-Cookie and token headers, cookie
 * values, password fields, and JSON fields whose names match the store's secret-field list
 * ({@code pkb_store_secret_fields} adds names). A redacted value becomes {@code [redacted]} and its name is listed in
 * the row's {@code redacted[]}.
 */
public final class ProtoRedact {
    public static final String MASK = "[redacted]";
    static final Set<String> SECRET_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token");
    private static final Pattern QUERY = Pattern.compile("([?&])([^=&#]+)=([^&#]*)");

    private ProtoRedact() {
    }

    public static boolean secretHeader(String name) {
        if (name == null) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return SECRET_HEADERS.contains(key) || key.contains("token") || key.contains("secret") || key.contains("password");
    }

    public static boolean secretField(Path project, String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (key.contains("password") || key.contains("passwd") || key.contains("token") || key.contains("secret")
                || key.equals("authorization") || key.contains("apikey") || key.contains("api_key") || key.equals("cookie")) return true;
        try {
            return StoreSecrets.secretName(project, name);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Header map (name -> value) to a redacted object; names go to {@code redacted}. */
    public static ObjectNode headers(JsonNode headers, ArrayNode redacted, boolean reveal) {
        ObjectNode out = ProtoJson.MAPPER.createObjectNode();
        if (headers == null) return out;
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                String name = header.path("name").asText("");
                JsonNode v = header.path("value");
                String value = v.isObject() ? v.path("value").asText("") : v.asText("");
                put(out, name, value, redacted, reveal);
            }
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            put(out, entry.getKey(), entry.getValue().asText(""), redacted, reveal);
        }
        return out;
    }

    private static void put(ObjectNode out, String name, String value, ArrayNode redacted, boolean reveal) {
        if (!reveal && secretHeader(name)) {
            out.put(name, MASK);
            if (redacted != null) redacted.add("header:" + name.toLowerCase(Locale.ROOT));
        } else {
            out.put(name, value);
        }
    }

    /** Query parameters with secret names become [redacted]. */
    public static String url(Path project, String url, ArrayNode redacted) {
        if (url == null || url.indexOf('?') < 0) return url;
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher matcher = QUERY.matcher(url);
        while (matcher.find()) {
            String name = matcher.group(2);
            if (secretField(project, name)) {
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + name + "=" + MASK));
                if (redacted != null) redacted.add("query:" + name);
            } else {
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A JSON body with secret-named fields masked, recursively. Non-JSON text is returned unchanged. */
    public static String body(Path project, String text, ArrayNode redacted) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return text;
        try {
            JsonNode node = ProtoJson.MAPPER.readTree(trimmed);
            mask(project, node, redacted, "");
            return ProtoJson.MAPPER.writeValueAsString(node);
        } catch (Exception notJson) {
            return text;
        }
    }

    private static void mask(Path project, JsonNode node, ArrayNode redacted, String path) {
        if (node instanceof ObjectNode object) {
            List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child.isValueNode() && secretField(project, name)) {
                    object.put(name, MASK);
                    if (redacted != null) redacted.add("body:" + (path.isEmpty() ? name : path + "." + name));
                } else {
                    mask(project, child, redacted, path.isEmpty() ? name : path + "." + name);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) mask(project, child, redacted, path);
        }
    }

    /** Free text: {@code Bearer x}, {@code name=value} pairs with secret names. */
    public static String text(String text) {
        if (text == null) return null;
        String out = text.replaceAll("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}", "$1 " + MASK);
        out = out.replaceAll("(?i)((?:password|passwd|token|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s,;&\"']+", "$1" + MASK);
        return out;
    }
}
