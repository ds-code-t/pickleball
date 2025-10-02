//package tools.ds.modkit.mappings;
//
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.google.common.collect.LinkedListMultimap;
//
//import java.util.List;
//import java.util.Map;
//
//@SuppressWarnings("unchecked")
//public class TestNodeB_EdgeCases {
//
//    // ---------- tiny test helpers ----------
//    private static void put(NodeB nm, String key, Object val) {
//        System.out.printf("PUT  %-34s = %s%n", key, toOneLine(val));
//        nm.put(key, val);
//    }
//
//    private static Object get(NodeB nm, String key) {
//        System.out.printf("GET  %-34s%n", key);
//        return nm.get(key);
//    }
//
//    private static void header(String title) {
//        System.out.println();
//        System.out.println("── " + title + " ───────────────────────────────────────────");
//    }
//
//    private static String toOneLine(Object o) {
//        if (o == null) return "null";
//        if (o instanceof ObjectNode on) return on.toString();
//        return String.valueOf(o);
//    }
//
//    private static void expectEq(String label, Object expected, Object actual) {
//        String e = toOneLine(expected);
//        String a = toOneLine(actual);
//        boolean pass = e.equals(a);
//        System.out.println("Expected: " + e);
//        System.out.println("Actual  : " + a + (pass ? "   ✅ PASS" : "   ❌ FAIL"));
//    }
//
//    private static void expectVals(String label, Object multimapObj, List<?> expected) {
//        var mm = (LinkedListMultimap<String, Object>) multimapObj;
//        expectEq(label, expected, mm.values());
//    }
//
//    private static void expectRoot(NodeB nm, String json) {
//        expectEq("ROOT", json, nm.objectNode());
//    }
//
//    public static void main(String[] args) {
//        // 9) Disallow descending through a terminal [] segment
//        header("9) cannot descend past terminal []");
//        NodeB nm9 = new NodeB();
////        put(nm9, "$.k[]", "x");
//        // attempt to write deeper using a [] parent path should not create anything
//        put(nm9, "$.k[].sub", "y"); // invalid descent; implementation should ignore/no-op
//        Object kAll = get(nm9, "$.k[*]");
//        expectVals("$.k[*] remains only first append", kAll, List.of("x"));
//
//    }
//
//    public static void main2(String[] args) {
//
//
//        // 1) Nested write when top-level array is empty → create [0] object and write field
//        header("1) nested write on empty top-level array");
//        NodeB nm1 = new NodeB();
//        put(nm1, "people.name", "Ava");       // no explicit index; should create people[0] as object
//        expectRoot(nm1, "{\"people\":[{\"name\":\"Ava\"}]}");
//
//        // 2) Nested write when last element is scalar → promote last to object, keep new field
//        header("2) nested write promotes last scalar to object");
//        NodeB nm2 = new NodeB();
//        put(nm2, "users", "Alice");
//        put(nm2, "users.name", "Kept");       // last was scalar "Alice" → becomes {}
//        expectRoot(nm2, "{\"users\":[{\"name\":\"Kept\"}]}");
//
//        // 3) Wildcard GET (provider returns ArrayNode of path strings) → should enumerate all values
//        header("3) wildcard get enumerates all");
//        NodeB nm3 = new NodeB();
//        put(nm3, "items", 5);
//        put(nm3, "items", 7);
//        Object itemsAll = get(nm3, "$.items[*]");
//        expectVals("$.items[*]", itemsAll, List.of(5, 7));
//
//        // 4) Direct-path fallback when provider returns empty path list → still return the value
//        header("4) direct path fallback (no path list)");
//        NodeB nm4 = new NodeB();
//        put(nm4, "$.cfg.flags[0]", true);
//        Object flag0 = get(nm4, "$.cfg.flags[0]");
//        expectVals("$.cfg.flags[0]", flag0, List.of(true));
//
//        // 5) Terminal field[] creates/returns an array node; subsequent puts on that [] path append
//        header("5) terminal field[] append semantics");
//        NodeB nm5 = new NodeB();
//        put(nm5, "$.bag[]", "x");
//        put(nm5, "$.bag[]", "y");
//        Object bagAll = get(nm5, "$.bag[*]");
//        expectVals("$.bag[*]", bagAll, List.of("x", "y"));
//
//        // 6) Deep explicit index creation with padding and object insertion
//        header("6) deep explicit index creation");
//        NodeB nm6 = new NodeB();
//        put(nm6, "$.a[1].b[2].c", 123);
//        Object deep = get(nm6, "$.a[1].b[2].c");
//        expectVals("$.a[1].b[2].c", deep, List.of(123));
//        // sanity: structure has null padding
//        expectRoot(nm6, "{\"a\":[null,{\"b\":[null,null,{\"c\":123}]}]}");
//
//        // 7) Top-level property initially scalar via merge → a subsequent top-level append wraps + appends
//        header("7) scalar→array promotion on top-level via merge + append");
//        NodeB nm7 = new NodeB();
//        nm7.merge(Map.of("misc", "solo"));     // top-level scalar
//        put(nm7, "misc", "two");               // append → wrap to array and add "two"
//        expectRoot(nm7, "{\"misc\":[\"solo\",\"two\"]}");
//
//        // 8) Top-level GET normalization to last index (no explicit index)
//        header("8) top-level get → last element");
//        NodeB nm8 = new NodeB();
//        put(nm8, "queue", "q1");
//        put(nm8, "queue", "q2");
//        Object qLast = get(nm8, "queue");
//        expectVals("queue last only", qLast, List.of("q2"));
//
//
//        // 10) Wildcard with objects
//        header("10) wildcard get over objects' field");
//        NodeB nm10 = new NodeB();
//        put(nm10, "users", Map.of("name", "Ann"));
//        put(nm10, "users", Map.of("name", "Ben"));
//        Object names = get(nm10, "$.users[*].name");
//        expectVals("$.users[*].name", names, List.of("Ann", "Ben"));
//
//        System.out.println();
//        System.out.println("Edge-case sweep complete.");
//    }
//
//
//}
