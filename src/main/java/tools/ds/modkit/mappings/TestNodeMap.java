package tools.ds.modkit.mappings;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;

import java.util.Arrays;
import java.util.List;

public class TestNodeMap {

    // ---------- tiny test helpers ----------
    private static void put(NodeMap nm, String key, Object val) {
        System.out.printf("PUT  %-30s = %s%n", key, toOneLine(val));
        nm.put(key, val);
    }

    private static Object get(NodeMap nm, String key) {
        System.out.printf("GET  %-30s%n", key);
        return nm.get(key);
    }

    private static void header(String title) {
        System.out.println();
        System.out.println("── " + title + " ───────────────────────────────────────────");
    }

    private static void dumpRoot(NodeMap nm) {
        System.out.println("ROOT: " + toOneLine(nm.objectNode()));
    }

    private static String toOneLine(Object o) {
        if (o == null) return "null";
        if (o instanceof ObjectNode on) return on.toString();
        return String.valueOf(o);
    }

    private static void expectEq(String label, Object expected, Object actual) {
        String e = toOneLine(expected);
        String a = toOneLine(actual);
        boolean pass = e.equals(a);
        System.out.println("Expected: " + e);
        System.out.println("Actual  : " + a + (pass ? "   ✅ PASS" : "   ❌ FAIL"));
    }

    // For convenience when the “actual” is the full root JSON
    private static void expectRoot(String label, NodeMap nm, String expectedJson) {
        expectEq(label, expectedJson, nm.objectNode());
    }

    public static void main(String[] args) {
        NodeMap nm = new NodeMap();

        // 1) Top-level multimap behavior (append)
        header("1) users top-level append");
        put(nm, "users", "Alice");
        put(nm, "users", "Bob");
        dumpRoot(nm);
        expectRoot("users array", nm, "{\"users\":[\"Alice\",\"Bob\"]}");

        // 2) Nested write under last top-level element (no explicit index)
        header("2) users.name attaches to last element");
        put(nm, "users.name", "Charlie");
        dumpRoot(nm);
        expectRoot("users with nested name", nm, "{\"users\":[\"Alice\",{\"name\":\"Charlie\"}]}");

        // 3) Repeated write to same field → promote to array and append
        header("3) users[1].tags collects values into array");
        put(nm, "users[1].tags", "admin");
        put(nm, "users[1].tags", "editor");
        dumpRoot(nm);
        // Verify just that field:
        Object tags = get(nm, "$.users[1].tags");
        expectEq("users[1].tags", List.of("admin", "editor"),
                ((LinkedListMultimap<String, Object>) tags).values());

        // 4) Explicit complete path with creation of missing parents
        header("4) $.accounts[2].id created");
        put(nm, "$.accounts[2].id", 123);
        dumpRoot(nm);
        // Only assert the sub-tree we care about:
        Object accountsId = get(nm, "$.accounts[2].id");
        expectEq("$.accounts[2].id", List.of(123),
                ((LinkedListMultimap<String, Object>) accountsId).values());

        // 5) GET normalizes to last index for top-level keys (no explicit index)
        header("5) get(logs) returns last element only");
        put(nm, "logs", "first");
        put(nm, "logs", "second");
        Object logsLast = get(nm, "logs"); // should point to $.logs[1]
        expectEq("logs last", List.of("second"),
                ((LinkedListMultimap<String, Object>) logsLast).values());

        // 6) Explicit index overwrite vs. top-level append
        header("6) numbers index overwrite then append");
        put(nm, "$.numbers[0]", 42);
        put(nm, "$.numbers[0]", 43);  // overwrite at index 0
        put(nm, "numbers", 44);       // append at top-level
        dumpRoot(nm);
        expectRoot("numbers array", nm,
                "{\"users\":[\"Alice\",{\"name\":\"Charlie\",\"tags\":[\"admin\",\"editor\"]}],"
                        + "\"accounts\":[null,null,{\"id\":123}],"
                        + "\"logs\":[\"first\",\"second\"],"
                        + "\"numbers\":[43,44]}");

        // 7) Construct from Guava LinkedListMultimap
        header("7) Construct from Multimap");
        LinkedListMultimap<String, String> mm = LinkedListMultimap.create();
        mm.put("k1", "v1");
        mm.put("k1", "v2");
        NodeMap nm2 = new NodeMap(mm);
        dumpRoot(nm2);
        expectRoot("from multimap", nm2, "{\"k1\":[\"v1\",\"v2\"]}");

        // 8) Merge another NodeMap
        header("8) Merge another NodeMap");
        NodeMap nm3 = new NodeMap();
        put(nm3, "extra", "hello");
        System.out.println("MERGE nm3.objectNode() into nm");
        nm.merge(nm3.objectNode());
        dumpRoot(nm);
        expectRoot("after merge extra", nm,
                "{\"users\":[\"Alice\",{\"name\":\"Charlie\",\"tags\":[\"admin\",\"editor\"]}],"
                        + "\"accounts\":[null,null,{\"id\":123}],"
                        + "\"logs\":[\"first\",\"second\"],"
                        + "\"numbers\":[43,44],"
                        + "\"extra\":[\"hello\"]}");

        // 9) GET on missing top-level property
        header("9) get(missing) returns empty");
        Object miss = get(nm, "missing");
        expectEq("missing multimap empty", "[]",
                ((LinkedListMultimap<String, Object>) miss).entries().toString());

        // 10) Deep missing path with createIfMissing
        header("10) deep nested creation");
        put(nm, "$.deep[0].child[1].leaf", "x");
        dumpRoot(nm);
        Object deepLeaf = get(nm, "$.deep[0].child[1].leaf");
        expectEq("$.deep[0].child[1].leaf", List.of("x"),
                ((LinkedListMultimap<String, Object>) deepLeaf).values());

        System.out.println();
        System.out.println("All checks done.");
    }
}
