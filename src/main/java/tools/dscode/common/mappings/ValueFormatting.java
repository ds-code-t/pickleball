package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class ValueFormatting {

    protected final ObjectNode root;

    // Global mapper (or reuse your existing one if you prefer)
    public static final ObjectMapper MAPPER = new ObjectMapper();

    // Registry for non-serializable / “unsafe” objects
    public static final Map<String, Object> nonSerializable = new ConcurrentHashMap<>();

    /**
     * Lightweight placeholder type that gets serialized instead of the real object.
     * JSON will look like: { "id": "some-uuid" }
     */
    public record NonSerializableRef(String id) {}

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

    /**
     * Reverse conversion:
     *  - If the object is a NonSerializableRef → resolve from nonSerializable map
     *  - Otherwise → just return it unchanged
     *
     * This is meant to be called on *deserialized* objects, not JsonNode.
     */
    public static Object fromSafeJsonNode(Object obj) {
        if (obj == null) return null;

        if (obj instanceof NonSerializableRef ref) {
            return nonSerializable.get(ref.id());
        }

        return obj;
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
}
