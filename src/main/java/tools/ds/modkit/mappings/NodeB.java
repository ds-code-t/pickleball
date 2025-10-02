//package tools.ds.modkit.mappings;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.*;
//import com.fasterxml.jackson.datatype.guava.GuavaModule;
//import com.google.common.collect.LinkedListMultimap;
//import com.google.common.collect.Multimap;
//import com.jayway.jsonpath.*;
//
//import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
//import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider;
//import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
//
//import java.util.*;
//import java.util.regex.Pattern;
//
//import static tools.ds.modkit.mappings.JsonPathUtil.getOrCreate;
//
//public class NodeB {
//
//    // ---- TypeRefs ----
//    private static final TypeRef<List<Object>> LIST_OF_OBJECTS = new TypeRef<>() {
//    };
//
//    // ---- ObjectMapper ----
//    private static final ObjectMapper MAPPER = new ObjectMapper();
//
//    static {
//        MAPPER.registerModule(new GuavaModule());
//        // keep it simple: no default typing
//    }
//
//
//    private static final Configuration PATHS_CFG = Configuration.builder()
//            .jsonProvider(new JacksonJsonNodeJsonProvider(MAPPER))
//            .mappingProvider(new JacksonMappingProvider(MAPPER))
//            .options(Option.ALWAYS_RETURN_LIST, Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS)
//            .build();
//
//    private static final Configuration VALUES_CFG = Configuration.builder()
//            .jsonProvider(new JacksonJsonNodeJsonProvider(MAPPER))
//            .mappingProvider(new JacksonMappingProvider(MAPPER))
//            .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
//            .build();
//
//    // Simple complete path: "$.a.b[2].c" (no wildcards, filters, or recursive)
//    private static final Pattern SIMPLE_PATH = Pattern.compile(
//            "^\\$\\.(?:[^.\\[\\]*?()@!]+(?:\\[\\d+])?)(?:\\.(?:[^.\\[\\]*?()@!]+(?:\\[\\d+])?))*$"
//    );
//
//    private static final Pattern MULTI_MATCH_META = Pattern.compile("[*]|\\.\\.|\\?|:|,");
//    private static final String arrayIndexFlag = "\u206A-AIndex";
//
//    // ---- State ----
//    private final ObjectNode root;
//
//    // ---- Ctors ----
//    public NodeB() {
//        this.root = MAPPER.createObjectNode();
//    }
//
//    public NodeB(Map<?, ?> map) {
//        this.root = toObjectNode(map);
//    }
//
//    public NodeB(LinkedListMultimap<?, ?> multimap) {
//        this.root = toObjectNode(multimap);
//    }
//
//    public ObjectNode objectNode() {
//        return root;
//    }
//
//    // ---- Key preprocessing ----
//    private static String requireDollarDot(String key) {
//        if (key == null || key.isBlank()) throw new IllegalArgumentException("key cannot be null or blank");
//        String out = (key.startsWith("$.") ? key : "$." + key).strip();
//        if (out.equals("$.")) throw new IllegalArgumentException("key cannot be '$.'");
//        return out;
//    }
//
//    public List<Object> getValues(String key) {
//        return get(key).values();
//    }
//
//    // ---- GET: returns LinkedListMultimap<fullPath, value> ----
//    public LinkedListMultimap<String, Object> get(String key) {
//        String q = requireDollarDot(key);
//
//        // If the query is a top-level field with no explicit index (e.g., "$.logs" or "logs.something"),
//        // normalize to **last existing index** instead of "append". This uses the actual root to decide.
//        // Regex: capture top field; if followed by nothing or a dot, inject [lastIndex] if array exists.
//        String injected = q.replaceAll("(^\\$\\.[^\\[\\.]+)($|\\..*$)", "$1" + arrayIndexFlag + "$2");
//        if (injected.contains(arrayIndexFlag)) {
//            String topField = injected.substring(2, injected.indexOf(arrayIndexFlag));
//            JsonNode top = root.get(topField);
//            if (top != null && top.isArray() && top.size() > 0) {
//                q = injected.replace(arrayIndexFlag, "[" + (top.size() - 1) + "]");
//            } else {
//                // Nothing to read at a top field without index -> no results
//                return LinkedListMultimap.create();
//            }
//        }
//
//        // Try to get both paths and values
//        Object rawPaths = JsonPath.using(PATHS_CFG).parse(root).read(q);
//        @SuppressWarnings("unchecked")
//        List<String> paths;
//        if (rawPaths instanceof List<?> l) {
//            // json-smart provider shape
//            paths = new ArrayList<>(l.size());
//            for (Object o : l) paths.add(String.valueOf(o));
//        } else if (rawPaths instanceof ArrayNode an) {
//            // jackson provider shape
//            paths = new ArrayList<>(an.size());
//            for (JsonNode n : an) paths.add(n.asText());
//        } else {
//            paths = List.of();
//        }
//
//        List<Object> values = JsonPath.using(VALUES_CFG).parse(root).read(q, LIST_OF_OBJECTS);
//        if (values == null) values = List.of();
//
//        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
//
//        // Some providers can return empty path list for direct leaf queries.
//        // If no paths but this is a direct path, just pair the normalized q with the values.
//        boolean direct = !MULTI_MATCH_META.matcher(q).find();
//        if (paths.isEmpty() && direct && !values.isEmpty()) {
//            for (Object v : values) out.put(q, v);
//            return out;
//        }
//        int n = Math.min(paths.size(), values.size());
//        for (int i = 0; i < n; i++) out.put(paths.get(i), values.get(i));
//        return out;
//    }
//
//    // ---- PUT ----
//    public void put(String key, Object value) {
//        String q = requireDollarDot(key);
//// ... after the top-level normalization block ...
//        System.out.println("PUT normalized-for-put q -> " + q);
//
//        // Top-level normalization:
//        // If it's a plain top-level put (e.g., "$.users"), treat as APPEND -> use current size.
//        // If it's a nested write without an explicit index (e.g., "$.users.name"),
//        // target the **last existing** element; if array empty, create [0] as object; if last is scalar, promote to object.
//        String injected = q.replaceAll("(^\\$\\.[^\\[\\.]+)($|\\..*$)", "$1" + arrayIndexFlag + "$2");
//        if (injected.contains(arrayIndexFlag)) {
//            String topField = injected.substring(2, injected.indexOf(arrayIndexFlag));
//            JsonNode top = root.get(topField);
//
//            // Ensure top is an array for top-level multimap semantics
//            ArrayNode arr;
//            if (top == null || top.isNull()) {
//                arr = MAPPER.createArrayNode();
//                root.set(topField, arr);
//            } else if (top.isArray()) {
//                arr = (ArrayNode) top;
//            } else {
//                arr = MAPPER.createArrayNode();
//                arr.add(top);               // preserve existing scalar/object as first element
//                root.set(topField, arr);
//            }
//
//            boolean hasNested = injected.indexOf('.', injected.indexOf(arrayIndexFlag)) >= 0;
//
//            boolean hasExplicitIndex = q.matches("^\\$\\.[^\\.\\[]+\\[\\d+\\].*");
//
//
//            if (!hasExplicitIndex) {
//                if (!hasNested) {
//                    // Plain top-level append (e.g., "$.users") -> point to next slot
//                    q = injected.replace(arrayIndexFlag, "[" + arr.size() + "]");
//                } else {
//                    // Nested write (e.g., "$.users.name"): attach to last existing element
//                    int lastIdx = Math.max(arr.size() - 1, 0);
//                    if (arr.size() == 0) {
//                        arr.add(MAPPER.createObjectNode()); // create [0] object if empty
//                    } else if (!arr.get(lastIdx).isObject()) {
//                        arr.set(lastIdx, MAPPER.createObjectNode()); // promote scalar to object
//                    }
//                    q = injected.replace(arrayIndexFlag, "[" + Math.max(arr.size() - 1, 0) + "]");
//                }
//            }
//            // if explicit index was present originally, q stays unchanged
//        }
//
//        boolean isDirectPath = !MULTI_MATCH_META.matcher(q).find();
//        if (isDirectPath) {
//            updateRoot(q,value,true);
//            return;
//        }
//        Object rawPaths = JsonPath.using(PATHS_CFG).parse(root).read(q);
//        @SuppressWarnings("unchecked")
//        List<String> paths = (rawPaths instanceof List<?> l) ? (List<String>) l : new ArrayList<>();
//
//
//        for (String p : paths) {
//            updateRoot(p,value,false);
//        }
//    }
//
//    public void updateRoot(String path, Object value , boolean createMissing){
//        JsonPathUtil.NodeHandle h = getOrCreate(root, path, createMissing); // create only for direct, never for multi
//        if (h == null) return;
//
//        JsonNode v = MAPPER.valueToTree(value);
//        if (h.node instanceof ArrayNode a) {
//            // If the node we resolved IS an array, append
//            a.add(v);
//        } else if (h.parent instanceof ObjectNode obj && h.fieldName != null) {
//            obj.set(h.fieldName, v); // replace field
//        } else if (h.parent instanceof ArrayNode arr && h.index != null) {
//            arr.set(h.index, v);     // replace element
//        }
//    }
//
//    // ---- Merge (ObjectNode, Map, LinkedListMultimap) ----
//    public void merge(ObjectNode other) {
//        if (other != null) root.setAll(other);
//    }
//
//    public void merge(Map<?, ?> other) {
//        if (other != null) root.setAll(toObjectNode(other));
//    }
//
//    public void merge(LinkedListMultimap<?, ?> other) {
//        if (other != null) root.setAll(toObjectNode(other));
//    }
//
//    // ---- Normalize constructor/merge inputs ----
//    private static ObjectNode toObjectNode(Map<?, ?> map) {
//        if (map == null) return MAPPER.createObjectNode();
//        JsonNode n = MAPPER.valueToTree(map);
//        if (n != null && n.isObject()) return (ObjectNode) n;
//        throw new IllegalArgumentException("Map did not serialize to an ObjectNode");
//    }
//
//    private static ObjectNode toObjectNode(LinkedListMultimap<?, ?> mm) {
//        if (mm == null) return MAPPER.createObjectNode();
//        Map<Object, Collection<Object>> tmp = new LinkedHashMap<>();
//        mm.asMap().forEach((k, coll) -> tmp.put(k, new ArrayList<>(coll)));
//        JsonNode n = MAPPER.valueToTree(tmp);
//        if (n != null && n.isObject()) return (ObjectNode) n;
//        throw new IllegalArgumentException("Multimap did not serialize to an ObjectNode");
//    }
//
//    private static boolean isSimpleCompletePath(String path) {
//        return path != null && SIMPLE_PATH.matcher(path).matches();
//    }
//
//}
