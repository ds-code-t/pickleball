package com.example.pickleball.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ValueFormatting;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Standalone Java 21 test harness for Tokenized and NodeMap query behavior.
 *
 * <p>The source filename may be versioned, but the runnable class deliberately
 * remains {@code TokenizedQueryTest}. Run its {@code main} method directly.
 * Each result prints the starting data, every query or operation, the expected
 * value, and the actual value.</p>
 */
final class TokenizedQueryTest {
    private static int testNumber;
    private static int passed;
    private static int failed;

    private TokenizedQueryTest() { }

    public static void main(String[] args) throws Exception {
        System.out.println("Tokenized / NodeMap query tests");

        testReadPreprocessing();
        testCurrentPublicApi();
        testReadExecution();
        testDirectWrites();
        testWildcardWrites();
        testSelectedWrites();
        testSafeValues();
        testRejectedWrites();

        section("SUMMARY");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println("Total:  " + (passed + failed));

        if (failed > 0) {
            throw new AssertionError(failed + " test(s) failed");
        }
    }

    private static void testReadPreprocessing() {
        section("READ PREPROCESSING");

        String[][] cases = {
                {"REQUEST", "REQUEST[][-1]"},
                {"REQUEST.endpoint", "REQUEST[][-1].endpoint"},
                {"_CONFIG.endpoint", "_CONFIG.endpoint"},
                {"`_Customer Settings`.endpoint", "`_Customer Settings`.endpoint"},
                {"REQUEST[0].endpoint", "REQUEST[0].endpoint"},
                {"REQUEST[]", "REQUEST[]"},
                {"(REQUEST.endpoint)[-1]", "(REQUEST.endpoint)[-1]"},
                {"$.REQUEST.endpoint", "$.REQUEST.endpoint"},
                {"$contains(name, \"Mr. Smith\")", "$contains(name, \"Mr. Smith\")"},
                {"[REQUEST, RESPONSE]", "[REQUEST, RESPONSE]"},
                {"{\"request\": REQUEST}", "{\"request\": REQUEST}"},
                {"true", "true"},
                {"REQUEST #1", "REQUEST[0]"},
                {"REQUEST #0", "REQUEST[-1]"},
                {"REQUEST #-1", "REQUEST[-2]"},
                {"REQUEST #first", "REQUEST[0]"},
                {"REQUEST #FIRST", "REQUEST[0]"},
                {"REQUEST #First", "REQUEST[0]"},
                {"REQUEST #last", "REQUEST[-1]"},
                {"REQUEST #LAST", "REQUEST[-1]"},
                {"REQUEST #Last", "REQUEST[-1]"},
                {"REQUEST #firstName", "REQUEST[][-1] #firstName"},
                {"REQUEST #1-3", "REQUEST[[0..2]]"},
                {"REQUEST #1--1", "REQUEST[[0..-2]]"},
                {"REQUEST #1, 3, -1", "REQUEST[[0,2,-2]]"},
                {"REQUEST[1..3].endpoint", "REQUEST[1..3].endpoint"},
                {"$contains(message, \"#1-3\")", "$contains(message, \"#1-3\")"},
                {"$match(message, /#1-3/)", "$match(message, /#1-3/)"},
                {"REQUEST /* #1-3 */", "REQUEST[][-1] /* #1-3 */"},
                {"REQUEST#$position", "REQUEST[][-1]#$position"},
                {"`property.with.periods`", "`property.with.periods`[][-1]"},
                {"Customer Requests.Endpoint Name", "`Customer Requests`[][-1].`Endpoint Name`"},
                {"`Customer Requests`.`Endpoint Name`", "`Customer Requests`[][-1].`Endpoint Name`"},
                {"foo-bar", "`foo-bar`[][-1]"},
                {"foo - bar", "foo - bar"},
                {"1-2", "`1-2`[][-1]"},
                {"1 - 2", "1 - 2"},
                {"REQUEST.my-property", "REQUEST[][-1].`my-property`"},
                {"order-items #last.product-name", "`order-items`[-1].`product-name`"},
                {"foo--bar", "`foo--bar`[][-1]"},
                {"-foo", "-foo"},
                {"foo-", "foo-"},
                {"REQUEST[price-tax]", "REQUEST[price-tax]"},
                {"foo and bar", "foo[][-1] and bar"},
                {"price * 1.25", "price[][-1] * 1.25"},
                {"orders[].products[].id", "orders[].products[].id"},
                {"orders[].categories[].products[]", "orders[].categories[].products[]"}
        };

        for (String[] test : cases) {
            checkQuery(
                    "Tokenized.preprocessReadQuery(" + quote(test[0]) + ")",
                    List.of("preprocessReadQuery(" + quote(test[0]) + ")"),
                    test[1],
                    Tokenized.preprocessReadQuery(test[0]));
        }

        checkQuery(
                "Tokenized.preprocessReadQuery(null)",
                List.of("preprocessReadQuery(null)"),
                null,
                Tokenized.preprocessReadQuery(null));
    }

    private static void testCurrentPublicApi() {
        section("CURRENT PUBLIC API AND REMOVED META SYNTAX");

        String[][] unchanged = {
                {"REQUEST.endpoint=", "REQUEST[][-1].endpoint="},
                {"-REQUEST", "-REQUEST"},
                {"DEFAULT::REQUEST", "DEFAULT[][-1]::REQUEST"},
                {"$.REQUEST", "$.REQUEST"},
                {"?REQUEST", "?REQUEST"},
                {"!REQUEST", "!REQUEST"},
                {"@REQUEST", "@REQUEST"}
        };

        for (String[] test : unchanged) {
            checkQuery(
                    "preprocessReadQuery(" + quote(test[0]) + ") — no legacy meta-character trimming",
                    List.of("Tokenized.preprocessReadQuery(" + quote(test[0]) + ")"),
                    test[1],
                    Tokenized.preprocessReadQuery(test[0]));
        }

        checkQuery(
                "Tokenized.singletonWrite(\"SESSION\") — explicit singleton-write factory remains available",
                List.of("Tokenized.singletonWrite(\"SESSION\")"),
                true,
                Tokenized.singletonWrite("SESSION") != null);
    }

    private static void testReadExecution() throws Exception {
        section("READ EXECUTION");

        TraceNodeMap map = new TraceNodeMap();
        map.put("REQUEST.endpoint", "/first");
        map.put("REQUEST", object("status", "complete"));

        checkMapJson(
                "get(\"REQUEST\") — returns the final collection entry",
                map,
                "{\"status\":\"complete\"}",
                map.get("REQUEST"));
        checkMap(
                "get(\"REQUEST.endpoint\") — reads only the final REQUEST entry",
                map,
                null,
                map.get("REQUEST.endpoint"));
        checkMap(
                "get(\"(REQUEST.endpoint)[-1]\") — last endpoint across the collection",
                map,
                "/first",
                map.get("(REQUEST.endpoint)[-1]"));

        checkMapJson(
                "get(\"REQUEST[]\") — returns the complete collection",
                map,
                "[{\"endpoint\":\"/first\"},{\"status\":\"complete\"}]",
                map.get("REQUEST[]"));

        List<JsonNode> requests = map.getAsList("REQUEST");
        checkMap(
                "getAsList(\"REQUEST\") — returns both entries",
                map,
                2,
                requests == null ? null : requests.size());

        TraceNodeMap orders = new TraceNodeMap();
        orders.put("orders[].id", 1);
        orders.put("orders[].id", 2);
        checkMap(
                "get(\"orders.id\") — reads the final order id",
                orders,
                2,
                orders.get("orders.id"));
        checkMapJson(
                "get(\"orders[].id\") — reads every order id",
                orders,
                "[1,2]",
                orders.get("orders[].id"));

        TraceNodeMap singleton = new TraceNodeMap();
        singleton.put("_CONFIG.endpoint", "/service");
        singleton.put("_STATUS", "ready");
        checkMap(
                "get(\"_CONFIG.endpoint\") — underscore object is not array-selected",
                singleton,
                "/service",
                singleton.get("_CONFIG.endpoint"));
        checkMap(
                "get(\"_STATUS\") — underscore scalar is returned directly",
                singleton,
                "ready",
                singleton.get("_STATUS"));
        checkMap(
                "get(\"DOES_NOT_EXIST\") — missing property returns null",
                singleton,
                null,
                singleton.get("DOES_NOT_EXIST"));

        TraceNodeMap arithmetic = new TraceNodeMap("{\"price\":[10,20],\"foo\":[false,true],\"bar\":true}");
        checkMapNumber(
                "get(\"price * 1.25\") — rewritten price[][-1] participates in arithmetic",
                arithmetic,
                25.0,
                arithmetic.get("price * 1.25"));
        checkMap(
                "get(\"foo and bar\") — rewritten foo[][-1] participates in Boolean logic",
                arithmetic,
                true,
                arithmetic.get("foo and bar"));
    }

    private static void testDirectWrites() throws Exception {
        section("DIRECT WRITES");

        TraceNodeMap collection = new TraceNodeMap();
        collection.put("REQUEST", "first");
        collection.put("REQUEST", "second");
        checkMapJson(
                "put(\"REQUEST\", value) twice — ordinary root appends",
                collection,
                "[\"first\",\"second\"]",
                collection.getRoot().get("REQUEST"));

        TraceNodeMap nested = new TraceNodeMap();
        nested.put("REQUEST.endpoint", "/first");
        nested.put("REQUEST.status", "ready");
        nested.put("REQUEST", object("endpoint", "/second"));
        nested.put("REQUEST.status", "complete");
        checkMapJson(
                "put(\"REQUEST.status\", value) — nested writes target the last root entry",
                nested,
                "[{\"endpoint\":\"/first\",\"status\":\"ready\"},"
                        + "{\"endpoint\":\"/second\",\"status\":\"complete\"}]",
                nested.getRoot().get("REQUEST"));

        TraceNodeMap singleton = new TraceNodeMap();
        singleton.put("_CONFIG.endpoint", "/first");
        singleton.put("_CONFIG.endpoint", "/second");
        singleton.put("_STATUS", "one");
        singleton.put("_STATUS", "two");
        checkMapJson(
                "put(\"_CONFIG.endpoint\", value) twice — underscore root replaces",
                singleton,
                "{\"endpoint\":\"/second\"}",
                singleton.getRoot().get("_CONFIG"));
        checkMap(
                "put(\"_STATUS\", value) twice — underscore scalar replaces",
                singleton,
                "two",
                singleton.getRoot().get("_STATUS").asText());

        TraceNodeMap forced = new TraceNodeMap();
        forced.putAsSingleton("SESSION", "first");
        forced.putAsSingleton("SESSION", "second");
        checkMap(
                "putAsSingleton(\"SESSION\", value) twice — explicit API replaces without '-' syntax",
                forced,
                "second",
                forced.getRoot().get("SESSION").asText());

        TraceNodeMap spaced = new TraceNodeMap();
        spaced.put("Customer Requests.Endpoint Name", "/service");
        checkMapJson(
                "put(\"Customer Requests.Endpoint Name\", value) — spaced properties",
                spaced,
                "[{\"Endpoint Name\":\"/service\"}]",
                spaced.getRoot().get("Customer Requests"));

        TraceNodeMap dashed = new TraceNodeMap();
        dashed.put("order-items[].product-name", "first");
        dashed.put("order-items[].product-name", "second");
        checkMapJson(
                "put(\"order-items[].product-name\", value) — compact dashes are property characters",
                dashed,
                "[{\"product-name\":\"first\"},{\"product-name\":\"second\"}]",
                dashed.getRoot().get("order-items"));

        TraceNodeMap appended = new TraceNodeMap();
        appended.put("orders[].id", 1);
        appended.put("orders[].id", 2);
        appended.put("orders[0].status", "ready");
        checkMapJson(
                "put(\"orders[].id\", value) — [] appends a new object",
                appended,
                "[{\"id\":1,\"status\":\"ready\"},{\"id\":2}]",
                appended.getRoot().get("orders"));

        TraceNodeMap nestedAppend = new TraceNodeMap();
        nestedAppend.put("orders[].products[].id", 10);
        nestedAppend.put("orders.products[].id", 11);
        nestedAppend.put("orders[].categories[].products[]", "item");
        checkMapJson(
                "put(\"orders[].products[].id\", value) and nested [] variations",
                nestedAppend,
                "[{\"products\":[{\"id\":10},{\"id\":11}]},"
                        + "{\"categories\":[{\"products\":[\"item\"]}]}]",
                nestedAppend.getRoot().get("orders"));

        TraceNodeMap indexed = new TraceNodeMap();
        indexed.put("items[2].name", "third");
        indexed.put("items[-1].status", "ready");
        checkMapJson(
                "put(\"items[2].name\", value), put(\"items[-1].status\", value)",
                indexed,
                "[null,null,{\"name\":\"third\",\"status\":\"ready\"}]",
                indexed.getRoot().get("items"));

        TraceNodeMap customIndex = new TraceNodeMap();
        customIndex.put("items #1.name", "first");
        customIndex.put("items #0.name", "last");
        checkMapJson(
                "put(\"items #1.name\", value), put(\"items #0.name\", value)",
                customIndex,
                "[{\"name\":\"last\"}]",
                customIndex.getRoot().get("items"));

        TraceNodeMap overwriteObject = new TraceNodeMap("{\"_CONFIG\":{\"items\":\"wrong type\"}}");
        overwriteObject.put("_CONFIG.items.status", "ready");
        checkMapJson(
                "put(\"_CONFIG.items.status\", value) — direct path replaces incompatible intermediate scalar",
                overwriteObject,
                "{\"items\":{\"status\":\"ready\"}}",
                overwriteObject.getRoot().get("_CONFIG"));

        TraceNodeMap overwriteArray = new TraceNodeMap("{\"_CONFIG\":{\"value\":[1,2]}}");
        overwriteArray.put("_CONFIG.value", "scalar");
        overwriteArray.put("_CONFIG.value[0]", "first");
        checkMapJson(
                "put(\"_CONFIG.value\", scalar), then put(\"_CONFIG.value[0]\", value) — node types can be replaced",
                overwriteArray,
                "{\"value\":[\"first\"]}",
                overwriteArray.getRoot().get("_CONFIG"));

        TraceNodeMap wrongCollectionType = new TraceNodeMap("{\"orders\":\"old scalar\"}");
        wrongCollectionType.put("orders.id", 1);
        checkMapJson(
                "put(\"orders.id\", value) — normal root is rebuilt as an ArrayNode",
                wrongCollectionType,
                "[{\"id\":1}]",
                wrongCollectionType.getRoot().get("orders"));
    }

    private static void testWildcardWrites() throws Exception {
        section("WILDCARD WRITES");

        TraceNodeMap values = new TraceNodeMap("{\"values\":[\"a\",{\"id\":1},[1],null]}");
        values.put("values[*]", "x");
        checkMapJson(
                "put(\"values[*]\", value) — final wildcard replaces every existing element",
                values,
                "[\"x\",\"x\",\"x\",\"x\"]",
                values.getRoot().get("values"));

        TraceNodeMap nested = new TraceNodeMap();
        nested.put("orders[].items[].status", "new");
        nested.put("orders[0].items[].status", "new");
        nested.put("orders[].items[].status", "new");
        nested.put("orders[*].items[*].status", "complete");
        checkMapJson(
                "put(\"orders[*].items[*].status\", value) — nested wildcards",
                nested,
                "[{\"items\":[{\"status\":\"complete\"},{\"status\":\"complete\"}]},"
                        + "{\"items\":[{\"status\":\"complete\"}]}]",
                nested.getRoot().get("orders"));

        TraceNodeMap missing = new TraceNodeMap();
        missing.put("_CONFIG.items[*].status", "complete");
        checkMapJson(
                "put(\"_CONFIG.items[*].status\", value) — missing wildcard path is not created",
                missing,
                "{}",
                missing.userRoot());

        TraceNodeMap existingParent = new TraceNodeMap("{\"_CONFIG\":{\"mode\":\"test\"}}");
        existingParent.put("_CONFIG.items[*].status", "complete");
        checkMapJson(
                "put(\"_CONFIG.items[*].status\", value) — existing parent remains unchanged",
                existingParent,
                "{\"mode\":\"test\"}",
                existingParent.getRoot().get("_CONFIG"));

        TraceNodeMap wrongArrayType = new TraceNodeMap("{\"_CONFIG\":{\"items\":\"not an array\"}}");
        wrongArrayType.put("_CONFIG.items[*].status", "complete");
        checkMapJson(
                "put(\"_CONFIG.items[*].status\", value) — non-array wildcard target is skipped",
                wrongArrayType,
                "{\"items\":\"not an array\"}",
                wrongArrayType.getRoot().get("_CONFIG"));

        TraceNodeMap mixed = new TraceNodeMap(
                "{\"values\":[{\"id\":1},\"text\",7,true,null,[{\"id\":99}],{\"id\":2}]}" );
        mixed.put("values[*].status", "complete");
        checkMapJson(
                "put(\"values[*].status\", value) — only ObjectNode elements receive the property",
                mixed,
                "[{\"id\":1,\"status\":\"complete\"},\"text\",7,true,null,"
                        + "[{\"id\":99}],{\"id\":2,\"status\":\"complete\"}]",
                mixed.getRoot().get("values"));

        TraceNodeMap mixedNested = new TraceNodeMap(
                "{\"groups\":[{\"items\":[{\"id\":1},\"skip\"]},"
                        + "{\"items\":\"not an array\"},{\"items\":[{\"id\":2},null]}]}" );
        mixedNested.put("groups[*].items[*].status", "complete");
        checkMapJson(
                "put(\"groups[*].items[*].status\", value) — incompatible branches are filtered independently",
                mixedNested,
                "[{\"items\":[{\"id\":1,\"status\":\"complete\"},\"skip\"]},"
                        + "{\"items\":\"not an array\"},"
                        + "{\"items\":[{\"id\":2,\"status\":\"complete\"},null]}]",
                mixedNested.getRoot().get("groups"));
    }

    private static void testSelectedWrites() throws Exception {
        section("SELECTOR-BASED WRITES");

        TraceNodeMap selected = new TraceNodeMap();
        selected.put("orders[].status", "active");
        selected.put("orders[].status", "inactive");
        selected.put("orders[].status", "active");
        selected.put("orders[status = \"active\"].result.code", "OK");
        checkMapJson(
                "put(\"orders[status = \\\"active\\\"].result.code\", value) — filtered parent update",
                selected,
                "[{\"status\":\"active\",\"result\":{\"code\":\"OK\"}},"
                        + "{\"status\":\"inactive\"},"
                        + "{\"status\":\"active\",\"result\":{\"code\":\"OK\"}}]",
                selected.getRoot().get("orders"));

        TraceNodeMap noMatch = new TraceNodeMap();
        noMatch.put("orders[].status", "inactive");
        noMatch.put("orders[status = \"active\"].result", "ignored");
        checkMapJson(
                "put(\"orders[status = \\\"active\\\"].result\", value) — no matches is a no-op",
                noMatch,
                "[{\"status\":\"inactive\"}]",
                noMatch.getRoot().get("orders"));

        TraceNodeMap spaced = new TraceNodeMap();
        spaced.put("Customer Requests[].Customer Name", "Alice");
        spaced.put("Customer Requests[].Customer Name", "Bob");
        spaced.put("`Customer Requests`[`Customer Name` = \"Alice\"].Result Code", "OK");
        checkMapJson(
                "put(\"`Customer Requests`[`Customer Name` = \\\"Alice\\\"].Result Code\", value)",
                spaced,
                "[{\"Customer Name\":\"Alice\",\"Result Code\":\"OK\"},"
                        + "{\"Customer Name\":\"Bob\"}]",
                spaced.getRoot().get("Customer Requests"));

        TraceNodeMap descendantReplace = new TraceNodeMap(
                "{\"left\":{\"values\":[1,{\"id\":1}]},"
                        + "\"right\":{\"values\":\"not an array\"},"
                        + "\"nested\":{\"child\":{\"values\":[true,null]}},"
                        + "\"other\":{\"values\":{\"id\":9}}}" );
        descendantReplace.put("**.values[*]", "scalar");
        checkMapJson(
                "put(\"**.values[*]\", value) — only descendant values properties that are arrays participate",
                descendantReplace,
                "{\"left\":{\"values\":[\"scalar\",\"scalar\"]},"
                        + "\"right\":{\"values\":\"not an array\"},"
                        + "\"nested\":{\"child\":{\"values\":[\"scalar\",\"scalar\"]}},"
                        + "\"other\":{\"values\":{\"id\":9}}}",
                descendantReplace.userRoot());

        TraceNodeMap descendantProperty = new TraceNodeMap(
                "{\"left\":{\"values\":[{\"id\":1},\"skip\",[1],null]},"
                        + "\"right\":{\"values\":7},"
                        + "\"nested\":{\"child\":{\"values\":[{\"id\":2},false]}},"
                        + "\"other\":{\"values\":{\"id\":9}}}" );
        descendantProperty.put("**.values[*].status", "complete");
        checkMapJson(
                "put(\"**.values[*].status\", value) — only ObjectNodes inside matching arrays are updated",
                descendantProperty,
                "{\"left\":{\"values\":[{\"id\":1,\"status\":\"complete\"},\"skip\",[1],null]},"
                        + "\"right\":{\"values\":7},"
                        + "\"nested\":{\"child\":{\"values\":[{\"id\":2,\"status\":\"complete\"},false]}},"
                        + "\"other\":{\"values\":{\"id\":9}}}",
                descendantProperty.userRoot());

        TraceNodeMap detached = new TraceNodeMap();
        detached.put("orders[].status", "active");
        checkMapThrows(
                "put(\"orders.{\\\"copy\\\": $}.status\", value) — detached constructed results are rejected",
                detached,
                IllegalArgumentException.class,
                () -> detached.put("orders.{\"copy\": $}.status", "changed"));
    }

    private static void testSafeValues() {
        section("SAFE VALUE HANDLING");

        ValueFormatting.nonSerializable.clear();
        TraceNodeMap map = new TraceNodeMap();
        map.put("orders[]", object("id", 1));
        map.put("orders[]", object("id", 2));

        UnsafeValue unsafe = new UnsafeValue();
        map.put("orders[*].payload", unsafe);

        ArrayNode orders = (ArrayNode) map.getRoot().get("orders");
        String firstId = orders.get(0).get("payload")
                .get(ValueFormatting.NON_SERIALIZABLE_FIELD).asText();
        String secondId = orders.get(1).get("payload")
                .get(ValueFormatting.NON_SERIALIZABLE_FIELD).asText();

        checkMap(
                "put(\"orders[*].payload\", unsafeObject) — one UUID is reused",
                map,
                firstId,
                secondId);
        checkMap(
                "get(\"orders[0].payload\") — original unsafe object is restored",
                map,
                true,
                map.get("orders[0].payload") == unsafe);
    }

    private static void testRejectedWrites() {
        section("REJECTED WRITES");

        TraceNodeMap map = new TraceNodeMap();
        checkMapThrows(
                "put(\"$count(REQUEST)\", value) — computed expression is not writable",
                map,
                IllegalArgumentException.class,
                () -> map.put("$count(REQUEST)", 1));
        checkMapThrows(
                "put(\"REQUEST.{\\\"copy\\\": $}\", value) — projection without writable suffix",
                map,
                IllegalArgumentException.class,
                () -> map.put("REQUEST.{\"copy\": $}", "value"));
        checkMapThrows(
                "put(\"REQUEST.endpoint=\", value) — removed '=' meta syntax is rejected",
                map,
                IllegalArgumentException.class,
                () -> map.put("REQUEST.endpoint=", "value"));
        checkMapThrows(
                "put(\"DEFAULT::REQUEST\", value) — removed '::' routing syntax is rejected",
                map,
                IllegalArgumentException.class,
                () -> map.put("DEFAULT::REQUEST", "value"));
        checkQueryThrows(
                "new Tokenized(null) — null query is rejected",
                List.of("new Tokenized(null)"),
                IllegalArgumentException.class,
                () -> new Tokenized(null));
    }

    private static ObjectNode object(String name, Object value) {
        ObjectNode node = ValueFormatting.MAPPER.createObjectNode();
        node.set(name, ValueFormatting.MAPPER.valueToTree(value));
        return node;
    }

    private static void section(String title) {
        System.out.println("\n============================================================");
        System.out.println(title);
        System.out.println("============================================================");
    }

    private static void checkQuery(String title, List<String> operations, Object expected, Object actual) {
        print(title, null, operations, display(expected), display(actual), Objects.deepEquals(expected, actual));
    }

    private static void checkQueryThrows(
            String title,
            List<String> operations,
            Class<? extends Throwable> expectedType,
            ThrowingRunnable action) {

        Throwable actual = capture(action);
        print(title, null, operations, expectedType.getSimpleName(), exceptionText(actual),
                expectedType.isInstance(actual));
    }

    private static void checkMap(String title, TraceNodeMap map, Object expected, Object actual) {
        print(title, map.startingData(), map.operations(), display(expected), display(actual),
                Objects.deepEquals(expected, actual));
    }

    private static void checkMapNumber(
            String title,
            TraceNodeMap map,
            double expected,
            Object actual) {

        boolean success = actual instanceof Number number
                && Double.compare(expected, number.doubleValue()) == 0;
        print(title, map.startingData(), map.operations(), String.valueOf(expected), display(actual), success);
    }

    private static void checkMapJson(String title, TraceNodeMap map, String expectedJson, Object actual)
            throws Exception {
        JsonNode expected = ValueFormatting.MAPPER.readTree(expectedJson);
        JsonNode actualNode = actual instanceof JsonNode node
                ? node
                : ValueFormatting.MAPPER.valueToTree(actual);
        print(title, map.startingData(), map.operations(), display(expected), display(actualNode),
                Objects.equals(expected, actualNode));
    }

    private static void checkMapThrows(
            String title,
            TraceNodeMap map,
            Class<? extends Throwable> expectedType,
            ThrowingRunnable action) {

        Throwable actual = capture(action);
        print(title, map.startingData(), map.operations(), expectedType.getSimpleName(), exceptionText(actual),
                expectedType.isInstance(actual));
    }

    private static Throwable capture(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    private static String exceptionText(Throwable actual) {
        return actual == null
                ? "No exception"
                : actual.getClass().getSimpleName() + ": " + actual.getMessage();
    }

    private static void print(
            String title,
            JsonNode startingData,
            List<String> operations,
            String expected,
            String actual,
            boolean success) {

        testNumber++;
        if (success) {
            passed++;
        } else {
            failed++;
        }

        System.out.printf("%n%02d. %s%n", testNumber, title);
        System.out.println("Starting data:");
        System.out.println(startingData == null ? "(not applicable)" : startingData.toPrettyString());
        System.out.println("Queries / operations:");
        if (operations == null || operations.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (int i = 0; i < operations.size(); i++) {
                System.out.println((i + 1) + ". " + operations.get(i));
            }
        }
        System.out.println("Expected:");
        System.out.println(expected);
        System.out.println("Actual:");
        System.out.println(actual);
        System.out.println("Result: " + (success ? "PASS" : "FAIL"));
    }

    private static String display(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonNode node) {
            return node.toPrettyString();
        }
        return String.valueOf(value);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String valueText(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof JsonNode node) {
            return node.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class TraceNodeMap extends NodeMap {
        private final JsonNode startingData;
        private final List<String> operations = new ArrayList<>();

        private TraceNodeMap() {
            super();
            startingData = userRoot();
        }

        private TraceNodeMap(String json) throws Exception {
            super((ObjectNode) ValueFormatting.MAPPER.readTree(json));
            startingData = userRoot();
        }

        @Override
        public void put(String key, Object value) {
            operations.add("put(" + quote(key) + ", " + valueText(value) + ")");
            super.put(key, value);
        }

        @Override
        public void putAsSingleton(String key, Object value) {
            operations.add("putAsSingleton(" + quote(key) + ", " + valueText(value) + ")");
            super.putAsSingleton(key, value);
        }

        @Override
        public Object get(String key) {
            operations.add("get(" + quote(key) + ")");
            return super.get(key);
        }

        @Override
        public List<JsonNode> getAsList(String key) {
            operations.add("getAsList(" + quote(key) + ")");
            return super.getAsList(key);
        }

        private JsonNode startingData() {
            return startingData;
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }

        private ObjectNode userRoot() {
            ObjectNode copy = getRoot().deepCopy();
            copy.remove(NodeMap.MAP_TYPE_KEY);
            return copy;
        }
    }

    private static final class UnsafeValue { }
}
