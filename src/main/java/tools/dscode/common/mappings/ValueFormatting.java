package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static tools.dscode.common.mappings.custommappings.ValConverter.valConverter;


public abstract class ValueFormatting {


    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.registerModule(new GuavaModule());
    }

    public Object directGet(String key) {
        return root.get(key);
    }

    public void directPut(String key, Object value) {
        root.put(key , MAPPER.valueToTree(value));
    }

    public static final String NON_SERIALIZABLE_FIELD = "_NonSerializableReferenceID";
    protected final ObjectNode root;


    // Registry for non-serializable / “unsafe” objects
    public static final Map<String, Object> nonSerializable = new ConcurrentHashMap<>();

    /**
     * Lightweight placeholder type that gets serialized instead of the real object.
     * JSON will look like: { "id": "some-uuid" }
     */
    public record NonSerializableRef(String _NonSerializableReferenceID) {
    }

    protected ValueFormatting(ObjectNode root) {
        this.root = root;
    }

    /**
     * Forward conversion:
     * - For safe packages → MAPPER.valueToTree(obj)
     * - For others → store obj in nonSerializable and return JSON for NonSerializableRef
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
            return valConverter.convert(objectNode);
        }
        return valConverter.convert(node);
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
        if (obj instanceof Enum<?>) {
            return true;
        }
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


    public String getStringValue(String periodSeparatedPath) {
        Object obj = getByNormalizedPath(periodSeparatedPath);
        if (obj == null) return null;
        return String.valueOf(obj);
    }

    public Object getByNormalizedPath(String periodSeparatedPath) {
        if (root == null || periodSeparatedPath == null) return null;

        String path = periodSeparatedPath.trim();
        if (path.isEmpty()) return null;

        IdentityHashMap<JsonNode, Map<String, String>> fieldLookupCache = new IdentityHashMap<>();


        JsonNode current = root;
        for (String segment : splitByDot(path)) {

            if (segment.isBlank()) continue;

            ParsedSegment ps = parseSegment(segment);
            if (ps == null) return null;
            if (!ps.name.isEmpty()) {
                if (current == null || !current.isObject()) return null;
                current = getFieldIgnoreCaseSpaceNormalized(current, ps.name, fieldLookupCache);
                if (current == null || current.isMissingNode()) return null;
            }
            for (int idx : ps.indexes) {
                if (current == null || !current.isArray()) return null;
                if (idx < 0 || idx >= current.size()) return null;
                current = current.get(idx);
            }
        }
        return fromSafeJsonNode(current); // handles null/missing/null-node conversions
    }



    /**
     * Split on '.' with basic trimming (indexes are in [n], so '.' is safe to split on).
     */
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

    public static JsonNode getFieldIgnoreCaseSpaceNormalized(ObjectNode objectNode, String rawName) {
        if (objectNode == null || rawName == null) {
            return JsonNodeFactory.instance.missingNode();
        }
        // One-off lookup: per-call cache
        IdentityHashMap<JsonNode, Map<String, String>> cache = new IdentityHashMap<>();
        return getFieldIgnoreCaseSpaceNormalized(objectNode, rawName, cache);
    }

    private static JsonNode getFieldIgnoreCaseSpaceNormalized(
            JsonNode objectNode,
            String rawName,
            IdentityHashMap<JsonNode, Map<String, String>> cache
    ) {
        JsonNode direct = objectNode.get(rawName);
        if (direct != null) {
            return direct;
        }

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
        String s = segment.trim();

        StringBuilder name = new StringBuilder();
        List<Integer> indexes = new ArrayList<>();

        int i = 0;

        while (i < s.length() && s.charAt(i) != '[') {
            name.append(s.charAt(i));
            i++;
        }

        while (i < s.length()) {
            if (s.charAt(i) != '[') return null;

            int close = s.indexOf(']', i + 1);
            if (close < 0) return null;

            String inside = s.substring(i + 1, close).trim();
            if (inside.isEmpty()) return null;

            try {
                indexes.add(Integer.parseInt(inside));
            } catch (NumberFormatException e) {
                return null;
            }

            i = close + 1;
        }

        return new ParsedSegment(name.toString(), indexes);
    }

    private record ParsedSegment(String name, List<Integer> indexes) {
    }

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
