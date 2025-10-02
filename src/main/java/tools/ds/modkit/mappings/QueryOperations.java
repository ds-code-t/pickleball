package tools.ds.modkit.mappings;

import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static tools.ds.modkit.mappings.NodeMap.MAPPER;

public class QueryOperations {

    public static boolean setValue(ObjectNode root, String jsonataExpr, Object value)
            throws ParseException, IOException, EvaluateException {
        boolean valueSet = false;

        // normalize: drop leading "$."
        jsonataExpr = jsonataExpr.strip().replaceAll("^\\$\\.", "");

        // detect tail [index] (optional)
        boolean isArrayNode = jsonataExpr.matches(".*\\[\\s*(?:\\d+)?\\s*\\]$");
        String mainPath = jsonataExpr;
        Integer arrayIndex = null;
        if (isArrayNode) {
            mainPath = jsonataExpr.substring(0, jsonataExpr.lastIndexOf("["));
            String arrayIndexString = jsonataExpr.substring(jsonataExpr.lastIndexOf("[") + 1, jsonataExpr.lastIndexOf("]"));
            if (!arrayIndexString.isBlank()) {
                try {
                    arrayIndex = Integer.parseInt(arrayIndexString.strip());
                } catch (Exception e) {
                    throw new RuntimeException("invalid array syntax '" + arrayIndexString + "'");
                }
            }
        }

        // parent path + final field name (quoted names allowed with '.' separators; no brackets for names)
        int dot = mainPath.lastIndexOf('.');
        boolean singleProperty = (dot < 0);

        String parentPath = singleProperty ? "$" : mainPath.substring(0, dot);
        isArrayNode = isArrayNode || singleProperty; // (intentional) treat top-level field as array when creating

        String seg = singleProperty ? mainPath : mainPath.substring(dot + 1);
//        final String fieldName = seg.strip();
        seg = seg.strip();
        // unwrap a single pair of surrounding quotes if present
        if (seg.length() >= 2 &&
                ((seg.charAt(0) == '\'' && seg.charAt(seg.length() - 1) == '\'') ||
                        (seg.charAt(0) == '\"' && seg.charAt(seg.length() - 1) == '\"'))) {
            seg = seg.substring(1, seg.length() - 1);
        }
        final String fieldName = seg;

        // "directness" heuristic: allow [digits], disallow wildcards/slices/[*], no trailing parent operator
        String normalized = jsonataExpr.replaceAll("\\s+", "");
        String s = normalized.replaceAll("\\[\\d+\\]", ""); // strip allowed [123]
        s = s.replaceAll("(\\*\\*|(?<=\\.)\\*|\\[\\*\\])", ""); // remove **, .*, [*]
        final boolean isDirect = !s.contains("[") && !s.endsWith("^");

        if (isDirect) {
            // DIRECT: parent must be an ObjectNode (or ArrayNode if parentPath points to array and you intend to append)
            Expressions e = Expressions.parse(parentPath);
            JsonNode parent = e.evaluate(root);

            if (parent instanceof ObjectNode parentObject) {
                JsonNode child = parentObject.get(fieldName);

                if (child == null && isArrayNode) {
                    child = MAPPER.createArrayNode();
                    parentObject.set(fieldName, child);
                }

                if (child instanceof ArrayNode childArray) {
                    if (arrayIndex == null) {
                        childArray.add(MAPPER.valueToTree(value));
                        valueSet = true;
                    } else {
                        ensureIndex(childArray, arrayIndex, MAPPER.valueToTree(value));
                        valueSet = true;
                    }
                } else {
                    if (isArrayNode) {
                        throw new RuntimeException("'" + fieldName + "' is not an Array but is trying to be set as an Array");
                    }
                    parentObject.set(fieldName, MAPPER.valueToTree(value));
                    valueSet = true;
                }

            } else if (parent instanceof ArrayNode parentArray) {
                // (intentional) if parent resolves to an array directly, just append
                parentArray.add(MAPPER.valueToTree(value));
                valueSet = true;

            } else if (parent == null) {
                throw new RuntimeException("parent object of '" + fieldName + "' is not defined");

            } else {
                throw new RuntimeException("parent object of '" + fieldName + "' must be an ObjectNode or ArrayNode");
            }

        } else {
            // NON-DIRECT: build (parent, currentChild) pairs only for ObjectNode parents (intentional)
            List<JsonNode> parentNodes = evalToList(root, parentPath);
            List<Pair> pairs = parentNodes.stream()
                    .filter(n -> n instanceof ObjectNode)
                    .map(n -> (ObjectNode) n)
                    .map(po -> new Pair(po, po.get(fieldName)))
                    .filter(p -> p.current() != null)
                    .toList();

            for (Pair pair : pairs) {
                JsonNode obj = pair.current();
                if (obj instanceof ArrayNode childArray) {
                    if (arrayIndex == null) {
                        childArray.add(MAPPER.valueToTree(value));
                        valueSet = true;
                    } else {
                        ensureIndex(childArray, arrayIndex, MAPPER.valueToTree(value));
                        valueSet = true;
                    }
                } else {
                    pair.parent().set(fieldName, MAPPER.valueToTree(value));
                    valueSet = true;
                }
            }
        }

        return valueSet;
    }

    record Pair(ObjectNode parent, JsonNode current) {
    }

    /**
     * Evaluate JSONata; always return a List. Prints parse/eval errors and returns [] on failure.
     */
    public static List<JsonNode> evalToList(JsonNode input, String jsonataExpr) {
        try {
            Expressions e = Expressions.parse(jsonataExpr);
            JsonNode out = e.evaluate(input);
            if (out == null || out.isNull()) return List.of();
            if (out.isArray()) {
                List<JsonNode> list = new ArrayList<>();
                out.forEach(list::add);
                return list;
            }
            return List.of(out);
        } catch (ParseException pe) {
            System.err.println("JSONata syntax error: " + pe.getMessage());
            return List.of();
        } catch (Exception ex) {
            System.err.println("JSONata evaluation error: " + ex.getMessage());
            return List.of();
        }
    }

    public static void ensureIndex(ArrayNode array, int index, JsonNode value) {
        if (array == null || index < 0) {
            throw new IllegalArgumentException("ArrayNode is null or index < 0");
        }
        while (array.size() <= index) {
            array.add(NullNode.instance);
        }
        array.set(index, value == null ? NullNode.instance : value);
    }


//    public String parseTopLevelProperty(String input, ObjectNode root) {
//        input = input.strip().replaceAll("^\\$\\.", "");
//        String topLevel = input.substring(0, input.indexOf("."));
//        String topLevelReplacement = !topLevel.contains("[") ? topLevel + "[-1]" : topLevel;
//        if (root != null) {
//            JsonNode jsonNode = root.get(topLevel);
//            if (jsonNode == null)
//                jsonNode = MAPPER.createArrayNode();
//
//            if (!(jsonNode instanceof ArrayNode)) {
//                ArrayNode newArray = MAPPER.createArrayNode();
//                newArray.add(MAPPER.valueToTree(jsonNode));
//                root
//            }
//        }
//
//        return input.replaceFirst(topLevel, topLevelReplacement);
//    }
//
//    public void createTopLevelArray(String topLevelProperty, ObjectNode root) {
//        JsonNode jsonNode = root.get(topLevelProperty);
//        if (jsonNode == null)
//            jsonNode = MAPPER.createArrayNode();
//
//        if (!(jsonNode instanceof ArrayNode)) {
//            ArrayNode newArray = MAPPER.createArrayNode();
//            newArray.add(MAPPER.valueToTree(jsonNode));
//            root.set(topLevelProperty, newArray);
//        }
//    }

    public static void main(String[] args) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, String> pp =
                n -> {
                    try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n); }
                    catch (Exception e) { return String.valueOf(n); }
                };

        // ===== Seed JSON =====
        ObjectNode root = mapper.createObjectNode();

        // a.b.c array, a.b.d string, a."key with space" object
        ObjectNode a = root.putObject("a").putObject("b");
        a.putArray("c").add(1).add(2);
        a.put("d", "orig");
        root.with("a").putObject("key with space").put("note", "exists");

        // data[].child.name
        ArrayNode data = root.putArray("data");
        ((ObjectNode) data.addObject().putObject("child")).put("name", "x");
        ((ObjectNode) data.addObject().putObject("child")).put("name", "y");

        // people[age].nick
        ArrayNode people = root.putArray("people");
        ((ObjectNode) people.addObject()).put("age", 17).put("nick", "Teen");
        ((ObjectNode) people.addObject()).put("age", 18).put("nick", "Adult0");

        System.out.println("=== INITIAL JSON ===");
        System.out.println(pp.apply(root));
        System.out.println();

        // === Helper for comparisons ===
        java.util.function.BiFunction<String, String, Boolean> assertPtrEquals = (ptr, expectedJson) -> {
            try {
                JsonNode expected = mapper.readTree(expectedJson);
                JsonNode actual = root.at(ptr);
                System.out.println("Expected (value): " + pp.apply(expected));
                System.out.println("Actual   (value): " + pp.apply(actual));
                return expected.equals(actual);
            } catch (Exception ex) {
                System.out.println("Exception comparing: " + ex.getMessage());
                return false;
            }
        };

        int total = 0, passed = 0;
        java.util.function.BiConsumer<String, Boolean> pf = (label, ok) -> {
            System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
            System.out.println();
        };

        // ---------- TESTS ----------

        // [1] Direct: append to a.b.c
        total++;
        System.out.println("[1] Direct a.b.c  (append 99)");
        boolean r1 = setValue(root, "$.a.b.c", mapper.readTree("99"));
        boolean ok1 = r1 && assertPtrEquals.apply("/a/b/c", "[1,2,99]");
        if (ok1) passed++; pf.accept("[1] a.b.c append", ok1);

        // [2] Direct: overwrite a.b.d
        total++;
        System.out.println("[2] Direct a.b.d  (overwrite to \"hello\")");
        boolean r2 = setValue(root, "$.a.b.d", mapper.readTree("\"hello\""));
        boolean ok2 = r2 && assertPtrEquals.apply("/a/b/d", "\"hello\"");
        if (ok2) passed++; pf.accept("[2] a.b.d overwrite", ok2);

        // [3] Direct: quoted field with space
        total++;
        System.out.println("[3] Direct a.'key with space'  (overwrite to {\"k\":1})");
        boolean r3 = setValue(root, "$.a.'key with space'", mapper.readTree("{\"k\":1}"));
        boolean ok3 = r3 && assertPtrEquals.apply("/a/key with space", "{\"k\":1}");
        if (ok3) passed++; pf.accept("[3] a.'key with space' overwrite", ok3);

        // [4] Direct: quoted plain field a."b"
        total++;
        System.out.println("[4] Direct a.\"b\"  (overwrite to {\"x\":1})");
        boolean r4 = setValue(root, "$.a.\"b\"", mapper.readTree("{\"x\":1}"));
        boolean ok4 = r4 && assertPtrEquals.apply("/a/b", "{\"x\":1}");
        if (ok4) passed++; pf.accept("[4] a.\"b\" overwrite", ok4);

        // [5] Non-direct: wildcard data[].child.name
        total++;
        System.out.println("[5] Non-Direct data[].child.name  (set to \"ZZZ\")");
        boolean r5 = setValue(root, "$.data[].child.name", mapper.readTree("\"ZZZ\""));
        boolean ok5 = r5 && assertPtrEquals.apply("/data", "[{\"child\":{\"name\":\"ZZZ\"}},{\"child\":{\"name\":\"ZZZ\"}}]");
        if (ok5) passed++; pf.accept("[5] data[].child.name", ok5);

        // [6] Non-direct: filter people[age >= 18].nick
        total++;
        System.out.println("[6] Non-Direct people[age >= 18].nick  (set to \"Adult\")");
        boolean r6 = setValue(root, "$.people[age >= 18].nick", mapper.readTree("\"Adult\""));
        boolean ok6 = r6 && assertPtrEquals.apply("/people", "[{\"age\":17,\"nick\":\"Teen\"},{\"age\":18,\"nick\":\"Adult\"}]");
        if (ok6) passed++; pf.accept("[6] people[age >= 18].nick", ok6);

        // [7] Direct: top-level single property
        total++;
        System.out.println("[7] Direct top-level 'top' (append \"alpha\" then \"beta\")");
        boolean r7a = setValue(root, "$.top", mapper.readTree("\"alpha\""));
        boolean r7b = setValue(root, "$.top", mapper.readTree("\"beta\""));
        boolean ok7 = (r7a && r7b) && assertPtrEquals.apply("/top", "[\"alpha\",\"beta\"]");
        if (ok7) passed++; pf.accept("[7] $.top array semantics", ok7);

        // [9] Direct: nested quoted keys
        total++;
        System.out.println("[9] Direct a.'dept'.\"team members\"  (overwrite to {\"count\":3})");
        root.with("a").putObject("dept");
        boolean r9 = setValue(root, "$.a.'dept'.\"team members\"", mapper.readTree("{\"count\":3}"));
        boolean ok9 = r9 && assertPtrEquals.apply("/a/dept/team members", "{\"count\":3}");
        if (ok9) passed++; pf.accept("[9] nested quoted keys", ok9);

        // Summary
        System.out.println("==== SUMMARY ====");
        System.out.println("Passed " + passed + " / " + total + " tests");
    }


}
