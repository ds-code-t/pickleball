//package tools.ds.modkit.mappings;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.fasterxml.jackson.databind.node.NullNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.fasterxml.jackson.databind.node.POJONode;
//import com.fasterxml.jackson.datatype.guava.GuavaModule;
//import com.google.common.collect.LinkedListMultimap;
//import com.jayway.jsonpath.*;
//import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
//import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//
//public class NodeMap2 {
//    private static final ObjectMapper MAPPER = new ObjectMapper();
//
//    static {
//        // Enable Guava → JSON conversions (e.g., LinkedListMultimap becomes a map of arrays)
//        MAPPER.registerModule(new GuavaModule());
//    }
//
//    /**
//     * Store: each top-level key maps to an ArrayNode (history/log).
//     */
//    private final ObjectNode multi;
//
//    public NodeMap2() {
//        multi = MAPPER.createObjectNode();
//    }
//
//    public NodeMap2(Object obj) {
//        if (obj instanceof Map<?, ?> map) {
//            LinkedListMultimap<Object, Object> mm = LinkedListMultimap.create();
//            map.forEach((k, v) -> mm.put(k, v));
//            multi = MAPPER.valueToTree(mm);
//        } else
//            multi = MAPPER.valueToTree(obj);
//    }
//
//    public void merge(Object obj){
//        multi.setAll((ObjectNode) MAPPER.valueToTree(obj));
//    }
//
//    // JsonPath configs
//    private static final Configuration NORMAL_CFG = Configuration.builder()
//            .jsonProvider(new JacksonJsonNodeJsonProvider())
//            .mappingProvider(new JacksonMappingProvider())
//            .build();
//
//    private static final Configuration PATHS_TOLERANT_CFG = Configuration.builder()
//            .jsonProvider(new JacksonJsonNodeJsonProvider())
//            .mappingProvider(new JacksonMappingProvider())
//            .options(Option.ALWAYS_RETURN_LIST, Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS)
//            .build();
//
//    private static final Configuration TOLERANT_LIST_CFG = Configuration.builder()
//            .jsonProvider(new JacksonJsonNodeJsonProvider())
//            .mappingProvider(new JacksonMappingProvider())
//            .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
//            .build();
//
//
//    /**
//     * Sidecar to keep original POJOs by JSON Pointer (so we can retrieve them later).
//     */
//    private final Map<String, POJONode> pojoByPointer = new ConcurrentHashMap<>();
//
//    // ============================== PUT ==============================
//
//    public void put(Object key, Object value) {
//        final String raw = String.valueOf(key).trim();
//        final JsonNode jsonView = (value instanceof JsonNode jn) ? jn : MAPPER.valueToTree(value);
//
//        // 1) Simple top-level forms
//        TopRef tr = parseTopRef(raw);
//        if (tr != null) {
//            ArrayNode log = multi.withArray(tr.top);
//            if (tr.idx == null) {
//                log.add(jsonView);                      // append
//                int idx = log.size() - 1;
//                recordPojoIfCustom(value, pointerOfTopIndex(tr.top, idx));
//            } else {
//                setIndexWithPad(log, tr.idx, jsonView); // set at index
//                recordPojoIfCustom(value, pointerOfTopIndex(tr.top, tr.idx));
//            }
//            return;
//        }
//
//        // 2) General JSONPath
//        String jp = ensureJsonPath(raw);
//
//        // 2a) Wildcard write: set only existing matches; do not create; skip POJO record (ambiguous multi-target)
//        if (jp.contains("*")) {
//            List<String> targets = JsonPath
//                    .using(PATHS_TOLERANT_CFG)
//                    .parse(multi)
//                    .read(jp, new TypeRef<List<String>>() {
//                    });
//            if (targets == null || targets.isEmpty()) return;
//            DocumentContext ctx = JsonPath.using(NORMAL_CFG).parse(multi);
//            for (String p : targets) {
//                try {
//                    ctx.set(p, jsonView);
//                } catch (PathNotFoundException ignored) {
//                    // do not create for wildcard writes
//                }
//            }
//            return;
//        }
//
//        // 2b) Direct path (no wildcards, no recursive descent): recursively create.
//        //     When encountering an existing ArrayNode without explicit [idx], target LAST element (create one if empty).
//        if (isDirectPath(jp)) {
//            List<Segment> segs = parseDirectSegments(jp);
//            if (segs == null || segs.isEmpty()) {
//                JsonPath.using(NORMAL_CFG).parse(multi).set(jp, jsonView);
//                return;
//            }
//
//            // Top segment is ALWAYS the array "log"
//            Segment top = segs.get(0);
//            ArrayNode topArray = multi.withArray(top.name);
//
//            int topIdx;
//            if (top.idx == null) {
//                if (topArray.size() == 0) topArray.add(MAPPER.createObjectNode());
//                topIdx = topArray.size() - 1;
//            } else {
//                topIdx = Math.max(0, top.idx);
//                while (topArray.size() <= topIdx) topArray.add(NullNode.getInstance());
//                if (!(topArray.get(topIdx) instanceof ObjectNode)) {
//                    topArray.set(topIdx, MAPPER.createObjectNode());
//                }
//            }
//
//            // If only top provided ("nums" or "nums[idx]"), set that slot directly.
//            if (segs.size() == 1) {
//                topArray.set(topIdx, jsonView);
//                recordPojoIfCustom(value, pointerOfTopIndex(top.name, topIdx));
//                return;
//            }
//
//            // Continue inside chosen top object with IMPLICIT-LAST logic for arrays
//            ObjectNode topObj = (ObjectNode) topArray.get(topIdx);
//            StringBuilder basePtr = new StringBuilder()
//                    .append('/').append(escapePtr(top.name)).append('/').append(topIdx);
//
//            String finalPtr = upsertWithImplicitLast(topObj, segs.subList(1, segs.size()), jsonView, basePtr);
//            recordPojoIfCustom(value, finalPtr);
//            return;
//        }
//
//        // 2c) Fallback: try setting as-is (no creation); cannot reliably compute single pointer → skip sidecar
//        JsonPath.using(NORMAL_CFG).parse(multi).set(jp, jsonView);
//    }
//
//
//
//    public ArrayNode getAllMatches(Object key) {
//        return toArrayNode(readScalar("$." + String.valueOf(key).trim()));
//    }
//
//
//    public ArrayNode get(Object key) {
//        final String raw = String.valueOf(key).trim();
//        if(raw.startsWith("$."))
//            return toArrayNode(readScalar(raw));
//
//        // "name" → latest entry of top-level array (scalar/object) ⇒ wrap as [value]
//        if (isSimpleTopName(raw)) {
//            JsonNode n = readScalar("$." + raw + "[-1]");
////            JsonNode n = readScalar("$." + raw );
//            return toArrayNode(n);
//        }
//        // "name[idx]" → that top-level index (scalar/object) ⇒ wrap as [value]
//        TopRef tr = parseTopRef(raw);
//        if (tr != null && tr.idx != null) {
//            JsonNode n = readScalar("$." + tr.top + "[" + tr.idx + "]");
//            return toArrayNode(n);
//        }
//        String jp = ensureJsonPath(raw);
//
//        // Direct (no wildcards / deep-scan) → resolve concrete path and read scalar/object/array
//        if (isDirectPath(jp)) {
//            Resolution r = resolveConcreteJsonPath(jp);
//            if (r.status == Status.NO_TARGET) {
//                return MAPPER.createArrayNode(); // []
//            }
//            if (r.status == Status.OK) {
//                try {
//                    JsonNode n = JsonPath.using(NORMAL_CFG).parse(multi).read(r.path, JsonNode.class);
//                    return toArrayNode(n); // ensure ArrayNode
//                } catch (PathNotFoundException e) {
//                    return MAPPER.createArrayNode(); // []
//                }
//            }
//            // NOT_APPLICABLE → fall through
//        }
//        // Wildcards / deep-scan (tolerant) already produce a List ⇒ ArrayNode
//        Object result = JsonPath.using(TOLERANT_LIST_CFG).parse(multi).read(jp);
//        JsonNode n = MAPPER.valueToTree(result);
//        return (n != null && n.isArray()) ? (ArrayNode) n : toArrayNode(n);
//    }
//
//
//    public ArrayNode getAsArrayNode(Object key) {
//        return getAllMatches(key); // get(...) now always returns ArrayNode
//    }
//
//
//    /**
//     * Wrap any node as an ArrayNode: null → [], array → itself, other → [node].
//     */
//    private ArrayNode toArrayNode(JsonNode n) {
//        if (n == null || n.isNull()) return MAPPER.createArrayNode();
//        if (n.isArray()) return (ArrayNode) n;
//        ArrayNode arr = MAPPER.createArrayNode();
//        arr.add(n);
//        return arr;
//    }
//
//
//    // ============================== GET (POJO retrieval) ==============================
//
//    /**
//     * Retrieve the original Java object stored at an exact (non-wildcard) path, if one was recorded.
//     */
//    public Object getPojo(Object key) {
//        String ptr = resolvePointerForKey(key);
//        if (ptr == null) return null;
//        POJONode pj = pojoByPointer.get(ptr);
//        return pj == null ? null : pj.getPojo();
//    }
//
//    /**
//     * Type-safe retrieval of original Java object if recorded; returns null if absent or type mismatch.
//     */
//    public <T> T getPojo(Object key, Class<T> type) {
//        Object o = getPojo(key);
//        if (o == null) return null;
//        return type.isInstance(o) ? type.cast(o) : null;
//    }
//
//    // ============================== Helpers ==============================
//
//    private JsonNode readScalar(String jsonPath) {
//        String normalized = jsonPath.replaceAll("\\.([A-Za-z0-9]+\\s+[A-Za-z0-9 ]*)", ".['$1']");
//        System.out.println("@@readScalar-normalized: " + normalized);
//        try {
//            return JsonPath.using(NORMAL_CFG).parse(multi).read(normalized, JsonNode.class);
//        } catch (PathNotFoundException e) {
//            return null;
//        } catch (Throwable t)
//        {
//            throw new RuntimeException("Could not parse query path '" +normalized+ "'", t);
//        }
//    }
//
//    private static String ensureJsonPath(String s) {
//        if (s.startsWith("$")) return s;
//        if (s.startsWith(".")) return "$" + s;
//        return "$." + s;
//    }
//
//    /**
//     * Parse top-level simple forms: "name", "name[idx]", "$.name", "$.name[idx]". Returns null for deep/wildcard.
//     */
//    private static TopRef parseTopRef(String path) {
//        String p = path.trim();
//        if (p.startsWith("$")) p = p.substring(1);
//        if (p.startsWith(".")) p = p.substring(1);
//        if (p.indexOf('.') >= 0 || p.indexOf('*') >= 0) return null;
//
//        int lb = p.indexOf('[');
//        if (lb < 0) {
//            if (p.isEmpty()) return null;
//            return new TopRef(p, null);
//        }
//        int rb = p.lastIndexOf(']');
//        if (rb != p.length() - 1 || lb == 0) return null;
//
//        String name = p.substring(0, lb).trim();
//        String inside = p.substring(lb + 1, rb).trim();
//        if (name.isEmpty()) return null;
//        try {
//            Integer idx = Integer.valueOf(inside);
//            return new TopRef(name, idx);
//        } catch (NumberFormatException nfe) {
//            return null;
//        }
//    }
//
//    private static boolean isSimpleTopName(String s) {
//        if (s.isEmpty()) return false;
//        if (s.startsWith("$") || s.startsWith(".")) return false;
//        return s.indexOf('.') < 0 && s.indexOf('[') < 0 && s.indexOf('*') < 0;
//    }
//
//    private static void setIndexWithPad(ArrayNode arr, int idx, JsonNode node) {
//        int i = Math.max(0, idx);
//        while (arr.size() <= i) arr.add(NullNode.getInstance());
//        arr.set(i, node);
//    }
//
//    // --------------------- Direct path parsing / concrete resolution ---------------------
//
//    /**
//     * A "direct" path has no wildcards and no recursive descent, e.g. "$.a.b[0].c".
//     */
//    private static boolean isDirectPath(String jp) {
//        if (!jp.startsWith("$")) return false;
//        // no wildcards, no deep-scan, no filters
//        return !jp.contains("*") && !jp.contains("..") && !jp.contains("?(");
//    }
//
//    /**
//     * Segment model: name plus optional [index]. Example: "arrayA[3]" or "prop".
//     */
//    private static final Pattern SEGMENT = Pattern.compile("(?<name>[^.\\[\\]]+)(?:\\[(?<idx>\\d+)])?");
//
//    /**
//     * Parse "$.a.b[2].c" into ordered segments. Returns null if not a direct path shape.
//     */
//    private static List<Segment> parseDirectSegments(String jp) {
//        if (!isDirectPath(jp)) return null;
//        String s = jp.startsWith("$.") ? jp.substring(2) : jp.substring(1); // drop "$." or "$"
//        String[] parts = s.isEmpty() ? new String[0] : s.split("\\.");
//        List<Segment> out = new ArrayList<>(parts.length);
//        for (String part : parts) {
//            Matcher m = SEGMENT.matcher(part);
//            if (!m.matches()) return null; // disallow anything beyond name or name[idx]
//            String name = m.group("name");
//            String idxStr = m.group("idx");
//            Integer idx = (idxStr == null) ? null : Integer.valueOf(idxStr);
//            out.add(new Segment(name, idx));
//        }
//        return out;
//    }
//
//    private enum Status {OK, NO_TARGET, NOT_APPLICABLE}
//
//    private static final class Resolution {
//        final Status status;
//        final String path;
//
//        Resolution(Status s, String p) {
//            this.status = s;
//            this.path = p;
//        }
//    }
//
//    /**
//     * Resolve a concrete JsonPath for GET by inserting [-1] for arrays when index omitted.
//     */
//    private Resolution resolveConcreteJsonPath(String jp) {
//        List<Segment> segs = parseDirectSegments(jp);
//        if (segs == null || segs.isEmpty()) {
//            return new Resolution(Status.NOT_APPLICABLE, null);
//        }
//
//        // Top-level is always an ArrayNode
//        Segment top = segs.get(0);
//        JsonNode topNode = multi.get(top.name);
//        if (!(topNode instanceof ArrayNode topArr)) return new Resolution(Status.NO_TARGET, null);
//        if (topArr.size() == 0) return new Resolution(Status.NO_TARGET, null);
//
//        int topIdx = (top.idx != null) ? Math.max(0, top.idx) : (topArr.size() - 1);
//        if (topIdx < 0 || topIdx >= topArr.size()) return new Resolution(Status.NO_TARGET, null);
//
//        StringBuilder sb = new StringBuilder()
//                .append("$.").append(top.name).append("[").append(topIdx).append("]");
//        JsonNode current = topArr.get(topIdx);
//
//        for (int i = 1; i < segs.size(); i++) {
//            Segment seg = segs.get(i);
//
//            if (!(current instanceof ObjectNode obj)) {
//                return new Resolution(Status.NO_TARGET, null);
//            }
//
//            JsonNode fieldVal = obj.get(seg.name);
//            if (fieldVal == null) {
//                return new Resolution(Status.NO_TARGET, null);
//            }
//
//            sb.append(".").append(seg.name);
//
//            if (seg.idx == null) {
//                if (fieldVal instanceof ArrayNode arr) {
//                    if (arr.size() == 0) return new Resolution(Status.NO_TARGET, null);
//                    sb.append("[-1]");
//                    current = arr.get(arr.size() - 1);
//                } else {
//                    current = fieldVal;
//                }
//            } else {
//                if (!(fieldVal instanceof ArrayNode arr)) {
//                    return new Resolution(Status.NO_TARGET, null);
//                }
//                int idx = Math.max(0, seg.idx);
//                if (idx >= arr.size()) return new Resolution(Status.NO_TARGET, null);
//                sb.append("[").append(idx).append("]");
//                current = arr.get(idx);
//            }
//        }
//
//        return new Resolution(Status.OK, sb.toString());
//    }
//
//    /**
//     * Continue traversal from an ObjectNode using name or name[idx] segments; create containers as needed.
//     * IMPLICIT-LAST WRITE behavior: if we encounter an existing ArrayNode and the segment has NO [idx],
//     * we target the LAST element (creating one if empty).
//     * Returns the final JSON Pointer to the value set (starting from basePtr).
//     */
//    private String upsertWithImplicitLast(ObjectNode rootObj, List<Segment> segs, JsonNode value, StringBuilder basePtr) {
//        JsonNode current = rootObj;
//
//        for (int i = 0; i < segs.size(); i++) {
//            Segment seg = segs.get(i);
//            boolean last = (i == segs.size() - 1);
//
//            // Debug prints (comment out if noisy)
//            System.out.printf("Inner Step %d/%d: segment=%s (idx=%s), last=%s, currentType=%s%n",
//                    i + 1, segs.size(), seg.name, seg.idx, last,
//                    current == null ? "null" : current.getClass().getSimpleName());
//
//            if (seg.idx == null) {
//                // FIELD
//                if (!(current instanceof ObjectNode obj)) {
//                    throw new IllegalStateException("Expected ObjectNode before field '" + seg.name + "'");
//                }
//
//                JsonNode fieldVal = obj.get(seg.name);
//
//                if (last) {
//                    // SET behavior:
//                    // If fieldVal is an ArrayNode → set/append last element; else set scalar/object normally
//                    if (fieldVal instanceof ArrayNode arr) {
//                        if (arr.size() == 0) {
//                            arr.add(value);
//                            basePtr.append('/').append(escapePtr(seg.name)).append('/').append(0);
//                            return basePtr.toString();
//                        } else {
//                            int li = arr.size() - 1;
//                            arr.set(li, value);
//                            basePtr.append('/').append(escapePtr(seg.name)).append('/').append(li);
//                            return basePtr.toString();
//                        }
//                    } else {
//                        obj.set(seg.name, value);
//                        basePtr.append('/').append(escapePtr(seg.name));
//                        return basePtr.toString();
//                    }
//                }
//
//                // Not last: need to traverse deeper
//                if (fieldVal == null || fieldVal.isNull()) {
//                    // Create an ObjectNode by default (we don't know if next is array yet)
//                    fieldVal = obj.putObject(seg.name);
//                }
//
//                if (fieldVal instanceof ArrayNode arr) {
//                    // The caller didn't specify [idx]; choose LAST element (create object if empty)
//                    if (arr.size() == 0) {
//                        ObjectNode child = MAPPER.createObjectNode();
//                        arr.add(child);
//                        basePtr.append('/').append(escapePtr(seg.name)).append('/').append(0);
//                        current = child;
//                    } else {
//                        int li = arr.size() - 1;
//                        JsonNode slot = arr.get(li);
//                        if (!(slot instanceof ObjectNode)) {
//                            slot = MAPPER.createObjectNode();
//                            arr.set(li, slot);
//                        }
//                        basePtr.append('/').append(escapePtr(seg.name)).append('/').append(li);
//                        current = slot;
//                    }
//                } else if (fieldVal instanceof ObjectNode) {
//                    basePtr.append('/').append(escapePtr(seg.name));
//                    current = fieldVal;
//                } else {
//                    // Replace wrong scalar with an object to continue traversal
//                    ObjectNode child = obj.putObject(seg.name);
//                    basePtr.append('/').append(escapePtr(seg.name));
//                    current = child;
//                }
//
//            } else {
//                // FIELD with [idx]
//                if (!(current instanceof ObjectNode obj)) {
//                    throw new IllegalStateException("Expected ObjectNode before array field '" + seg.name + "'");
//                }
//                JsonNode arrNode = obj.get(seg.name);
//                ArrayNode arr = (arrNode instanceof ArrayNode) ? (ArrayNode) arrNode : obj.putArray(seg.name);
//
//                int idx = Math.max(0, seg.idx);
//                while (arr.size() <= idx) arr.add(NullNode.getInstance());
//
//                if (last) {
//                    arr.set(idx, value);
//                    basePtr.append('/').append(escapePtr(seg.name)).append('/').append(idx);
//                    return basePtr.toString();
//                }
//
//                JsonNode slot = arr.get(idx);
//                if (!(slot instanceof ObjectNode)) {
//                    slot = MAPPER.createObjectNode();
//                    arr.set(idx, slot);
//                }
//                basePtr.append('/').append(escapePtr(seg.name)).append('/').append(idx);
//                current = slot;
//            }
//        }
//        return basePtr.toString();
//    }
//
//    // ============================== POJO sidecar helpers ==============================
//
//    private void recordPojoIfCustom(Object value, String pointer) {
//        if (pointer == null) return;
//        if (isCustomPojo(value)) {
//            pojoByPointer.put(pointer, new POJONode(value));
//        }
//    }
//
//    private String resolvePointerForKey(Object key) {
//        final String raw = String.valueOf(key).trim();
//
//        // Simple top-level latest
//        if (isSimpleTopName(raw)) {
//            ArrayNode arr = multi.withArray(raw);
//            if (arr.size() == 0) return null;
//            return pointerOfTopIndex(raw, arr.size() - 1);
//        }
//
//        // Simple top-level index
//        TopRef tr = parseTopRef(raw);
//        if (tr != null && tr.idx != null) {
//            ArrayNode arr = multi.withArray(tr.top);
//            if (tr.idx < 0 || tr.idx >= arr.size()) return null;
//            return pointerOfTopIndex(tr.top, tr.idx);
//        }
//
//        // Direct path → compute pointer without creating nodes; bail if anything missing
//        String jp = ensureJsonPath(raw);
//        if (isDirectPath(jp)) {
//            List<Segment> segs = parseDirectSegments(jp);
//            if (segs == null || segs.isEmpty()) return null;
//
//            Segment top = segs.get(0);
//            JsonNode n = multi.get(top.name);
//            if (!(n instanceof ArrayNode topArr)) return null;
//
//            int topIdx = (top.idx == null)
//                    ? (topArr.size() - 1)
//                    : top.idx;
//            if (topIdx < 0 || topIdx >= topArr.size()) return null;
//
//            StringBuilder ptr = new StringBuilder().append('/').append(escapePtr(top.name)).append('/').append(topIdx);
//            JsonNode cur = topArr.get(topIdx);
//
//            for (int i = 1; i < segs.size(); i++) {
//                Segment seg = segs.get(i);
//                if (!(cur instanceof ObjectNode obj)) return null;
//                JsonNode val = obj.get(seg.name);
//                if (val == null) return null;
//                if (seg.idx == null) {
//                    if (val instanceof ArrayNode arr) {
//                        if (arr.size() == 0) return null;
//                        int li = arr.size() - 1;
//                        ptr.append('/').append(escapePtr(seg.name)).append('/').append(li);
//                        cur = arr.get(li);
//                    } else {
//                        ptr.append('/').append(escapePtr(seg.name));
//                        cur = val;
//                    }
//                } else {
//                    if (!(val instanceof ArrayNode arr)) return null;
//                    if (seg.idx < 0 || seg.idx >= arr.size()) return null;
//                    ptr.append('/').append(escapePtr(seg.name)).append('/').append(seg.idx);
//                    cur = arr.get(seg.idx);
//                }
//            }
//            return ptr.toString();
//        }
//        return null;
//    }
//
//    private static String pointerOfTopIndex(String top, int idx) {
//        return "/" + escapePtr(top) + "/" + idx;
//    }
//
//    /**
//     * JSON Pointer escaping per RFC6901: ~ → ~0, / → ~1
//     */
//    private static String escapePtr(String s) {
//        return s.replace("~", "~0").replace("/", "~1");
//    }
//
//    /**
//     * Decide if value is a "custom" object worth tracking.
//     */
//    private static boolean isCustomPojo(Object v) {
//        if (v == null) return false;
//        if (v instanceof JsonNode) return false;
//        if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) return false;
//        if (v.getClass().isArray()) return false;
//        if (v instanceof Iterable) return false;
//        if (v instanceof Map) return false;
//        return true;
//    }
//
//    // ============================== Models ==============================
//
//    private static final class Segment {
//        final String name;
//        final Integer idx; // null → field; non-null → array index on this field
//
//        Segment(String name, Integer idx) {
//            this.name = name;
//            this.idx = idx;
//        }
//    }
//
//    private record TopRef(String top, Integer idx) {
//    }
//
//    // Accessor to inspect the store
//    public ObjectNode multi() {
//        return multi;
//    }
//
//    // ============================== Demo / Edge tests ==============================
//
//    public static void main2(String[] args) {
//        NodeMap2 map = new NodeMap2();
//
//        System.out.println("\n=== A) Implicit-last GET for nested array ===");
//        map.put("nums.arrayA[0].tag1", "AAA");
//        System.out.println(map.multi().toPrettyString());
//        System.out.println("get nums.arrayA.tag1 -> " + map.get("nums.arrayA.tag1")); // AAA
//
//        System.out.println("\n=== B) Implicit-last PUT for nested array ===");
//        map.put("nums.arrayA[1].tag1", "A2");
//        map.put("nums.arrayA.tag1", "A-LAST"); // should set tag1 on last element ([1])
//        System.out.println(map.multi().toPrettyString());
//        System.out.println("get nums.arrayA[*].tag1 -> " + map.get("$.nums[-1].arrayA[*].tag1")); // ["AAA","A-LAST"]
//
//        System.out.println("\n=== C) Implicit-last PUT for array of scalars ===");
//        map.put("nums.arrayB[0]", "BBB");
//        map.put("nums.arrayB", "DDD"); // sets last element (index 0) to "DDD"
//        System.out.println(map.multi().toPrettyString());
//        System.out.println("get nums.arrayB -> " + map.get("nums.arrayB")); // "DDD"
//
//        System.out.println("\n=== D) Explicit index still works ===");
//        map.put("nums.arrayB[1]", "EEE");
//        System.out.println("get nums.arrayB -> " + map.get("nums.arrayB")); // "EEE" (last element)
//        System.out.println("get $.nums[-1].arrayB[*] -> " + map.get("$.nums[-1].arrayB[*]"));
//
//        System.out.println("\n=== E) Guava LinkedListMultimap is queryable ===");
//        LinkedListMultimap<String, String> mm = LinkedListMultimap.create();
//        mm.put("color", "red");
//        mm.put("color", "blue");
//        map.put("mm.prop", mm);
//        System.out.println(map.multi().toPrettyString());
//        System.out.println("$.mm[-1].prop.color[*] -> " + map.get("$.mm[-1].prop.color[*]"));
//
//        System.out.println("\n=== F) POJO sidecar test ===");
//        record MyClass(String name, int n) {
//        }
//        MyClass mc = new MyClass("alpha", 7);
//        map.put("pojos.info", mc);
//        System.out.println(map.multi().toPrettyString());
//        System.out.println("$.pojos[-1].info.name -> " + map.get("$.pojos[-1].info.name"));
//        System.out.println("getPojo('pojos.info') -> " + map.getPojo("pojos.info"));
//    }
//}
