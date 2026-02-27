package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ValueFormatting {
    public static final String NON_SERIALIZABLE_FIELD = "_NonSerializableReferenceID";
    protected final ObjectNode root;

    // Global mapper (or reuse your existing one if you prefer)
    public static final ObjectMapper MAPPER = new ObjectMapper();

    // Registry for non-serializable / “unsafe” objects
    public static final Map<String, Object> nonSerializable = new ConcurrentHashMap<>();

    /**
     * Lightweight placeholder type that gets serialized instead of the real object.
     * JSON will look like: { "id": "some-uuid" }
     */
    public record NonSerializableRef(String _NonSerializableReferenceID) {}

    protected ValueFormatting(ObjectNode root) {
        this.root = root;
    }

    /**
     * Forward conversion:
     *  - For safe packages → MAPPER.valueToTree(obj)
     *  - For others → store obj in nonSerializable and return JSON for NonSerializableRef
     */
    public static JsonNode toSafeJsonNode(Object obj) {
        if (obj == null) {
            return JsonNodeFactory.instance.nullNode();
        }

        // Already JsonNode → leave as-is
        if (obj instanceof JsonNode node) {
            return node;
        }

        // Safe packages → normal Jackson tree serialization
        if (isFromSafePackage(obj)) {
            return MAPPER.valueToTree(obj);
        }

        // Unsafe / non-serializable: put in registry, return placeholder ref as JSON
        String id = UUID.randomUUID().toString();
        nonSerializable.put(id, obj);

        NonSerializableRef ref = new NonSerializableRef(id);
        return MAPPER.valueToTree(ref);
    }

    public static Object fromSafeJsonNode(Object node) {
        if (node instanceof ObjectNode objectNode) {
            // Only treat as a NonSerializableRef placeholder if our special field is present
            if (objectNode.has(NON_SERIALIZABLE_FIELD)) {
                NonSerializableRef ref = MAPPER.convertValue(objectNode, NonSerializableRef.class);
                return nonSerializable.get(ref._NonSerializableReferenceID());
            }
        }
        // Not a placeholder → just return as-is
        return node;
    }




    // ───────── safe package detection ─────────

    private static final String[] SAFE_PACKAGE_PREFIXES = {
            "java.",
            "javax.",
            "com.google.common.",     // Guava
            "com.fasterxml.jackson.", // Jackson & dataformats
            "org.json.",
            "org.yaml."
    };

    private static boolean isFromSafePackage(Object obj) {
        Package p = obj.getClass().getPackage();
        if (p == null) return false;
        String pkg = p.getName();
        for (String prefix : SAFE_PACKAGE_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }



    public JsonNode getByNormalizedPath(String periodSeparatedPath) {
        if (root == null || periodSeparatedPath == null) {
            return JsonNodeFactory.instance.missingNode();
        }

        String path = periodSeparatedPath.trim();
        if (path.isEmpty()) {
            return JsonNodeFactory.instance.missingNode();
        }

        // Per-call cache: ObjectNode identity -> (normalizedFieldName -> actualFieldName)
        IdentityHashMap<JsonNode, Map<String, String>> fieldLookupCache = new IdentityHashMap<>();

        JsonNode current = root;
        for (String segment : splitByDot(path)) {
            if (segment.isBlank()) continue;

            ParsedSegment ps = parseSegment(segment);

            // Optional property name (e.g., "customers[0]" => name="customers", indexes=[0])
            if (!ps.name.isEmpty()) {
                if (current == null || !current.isObject()) {
                    return JsonNodeFactory.instance.missingNode();
                }
                current = getFieldIgnoreCaseSpaceNormalized(current, ps.name, fieldLookupCache);
                if (current == null || current.isMissingNode()) {
                    return JsonNodeFactory.instance.missingNode();
                }
            }

            // Any number of array indexes in the segment (e.g., "matrix[0][1]")
            for (int idx : ps.indexes) {
                if (current == null || !current.isArray()) {
                    return JsonNodeFactory.instance.missingNode();
                }
                if (idx < 0 || idx >= current.size()) {
                    return JsonNodeFactory.instance.missingNode();
                }
                current = current.get(idx);
            }
        }

        return current == null ? JsonNodeFactory.instance.missingNode() : current;
    }

    /** Split on '.' with basic trimming (indexes are in [n], so '.' is safe to split on). */
    private static List<String> splitByDot(String path) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '.') {
                parts.add(path.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(path.substring(start).trim());
        return parts;
    }

    private static JsonNode getFieldIgnoreCaseSpaceNormalized(
            JsonNode objectNode,
            String rawName,
            IdentityHashMap<JsonNode, Map<String, String>> cache
    ) {
        String normalizedTarget = normalizeKey(rawName);

        Map<String, String> lookup = cache.get(objectNode);
        if (lookup == null) {
            lookup = new HashMap<>();
            var it = objectNode.fieldNames();
            while (it.hasNext()) {
                String actual = it.next();
                lookup.put(normalizeKey(actual), actual);
            }
            cache.put(objectNode, lookup);
        }

        String actualField = lookup.get(normalizedTarget);
        return actualField == null ? JsonNodeFactory.instance.missingNode() : objectNode.get(actualField);
    }

    private static ParsedSegment parseSegment(String segment) {
        // Examples:
        //  "customers[0]" -> name="customers", indexes=[0]
        //  "[0]"          -> name="",         indexes=[0]
        //  "a[0][1]"      -> name="a",        indexes=[0,1]
        String s = segment.trim();

        StringBuilder name = new StringBuilder();
        List<Integer> indexes = new ArrayList<>();

        int i = 0;
        // Read name until first '[' (or end)
        while (i < s.length() && s.charAt(i) != '[') {
            name.append(s.charAt(i));
            i++;
        }

        // Read one or more [number] parts
        while (i < s.length()) {
            if (s.charAt(i) != '[') {
                // Unexpected character (e.g., stray text after indexes) -> treat as invalid segment
                // Returning empty name + no indexes here would silently succeed; better to fail fast.
                return new ParsedSegment("__INVALID__", List.of(Integer.MIN_VALUE));
            }
            int close = s.indexOf(']', i + 1);
            if (close < 0) {
                return new ParsedSegment("__INVALID__", List.of(Integer.MIN_VALUE));
            }

            String inside = s.substring(i + 1, close).trim();
            if (inside.isEmpty()) {
                return new ParsedSegment("__INVALID__", List.of(Integer.MIN_VALUE));
            }

            // Parse integer index (allow whitespace; disallow non-digits except leading '-')
            int idx;
            try {
                idx = Integer.parseInt(inside);
            } catch (NumberFormatException e) {
                return new ParsedSegment("__INVALID__", List.of(Integer.MIN_VALUE));
            }
            indexes.add(idx);

            i = close + 1;
        }

        // Validate invalid sentinel
        if (name.toString().equals("__INVALID__") || indexes.contains(Integer.MIN_VALUE)) {
            return new ParsedSegment("__INVALID__", List.of(Integer.MIN_VALUE));
        }

        return new ParsedSegment(name.toString(), indexes);
    }

    private record ParsedSegment(String name, List<Integer> indexes) {}

    private static String normalizeKey(String key) {
        if (key == null) return "";
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().trim();
    }
}
