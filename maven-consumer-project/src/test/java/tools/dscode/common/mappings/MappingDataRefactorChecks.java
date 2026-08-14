package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.coredefinitions.MappingSteps;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.custommappings.ValConverter.convertSpecialValues;

public class MappingDataRefactorChecks {

    @Test
    void normalGetPreservesJacksonContainersAndConvertsScalars() {
        NodeMap map = runMap();
        ObjectNode object = MAPPER.createObjectNode().put("name", "Alice");
        ArrayNode array = MAPPER.createArrayNode().add("a").add("b");

        map.put("object", object);
        map.put("array", array);
        map.put("text", TextNode.valueOf("value"));
        map.put("number", IntNode.valueOf(7));
        map.put("boolean", BooleanNode.TRUE);
        map.put("nullValue", NullNode.instance);

        assertEquals(object, assertInstanceOf(ObjectNode.class, map.get("object")));
        assertEquals(array, assertInstanceOf(ArrayNode.class, map.get("array")));
        assertEquals("value", map.get("text"));
        assertEquals(7, map.get("number"));
        assertEquals(true, map.get("boolean"));
        assertNull(map.get("nullValue"));
    }

    @Test
    void embeddedNodeMapCollectionsUnwrapScalarsAndPreserveStructuredValues() {
        NodeMap scenarioMap = runMap();
        ArrayNode array = MAPPER.createArrayNode().add("a").add("b");
        scenarioMap.put("SCENARIO NAME", "Selection fixture A");
        scenarioMap.put("array", array);

        NodeMap map = runMap();
        map.putReference("inlineScenario", scenarioMap.getRoot());

        assertEquals("Selection fixture A", map.get("inlineScenario.SCENARIO NAME"));
        assertEquals(array, assertInstanceOf(
                ArrayNode.class,
                map.get("inlineScenario.array")
        ));
    }

    @Test
    void indexedReadsAddressTheLatestStoredArrayValue() {
        NodeMap map = runMap();
        ArrayNode rows = MAPPER.createArrayNode()
                .add(MAPPER.createObjectNode().put("rowName", "first"))
                .add(MAPPER.createObjectNode().put("rowName", "last"));

        map.put("rows", rows);

        assertEquals(rows, assertInstanceOf(ArrayNode.class, map.get("rows")));
        assertEquals("first", map.get("rows[0].rowName"));
        assertEquals("last", map.get("rows[1].rowName"));
        assertEquals(
                "rows[][-1][0].rowName",
                Tokenized.preprocessReadQuery("rows[0].rowName")
        );
        assertEquals("rows[]", Tokenized.preprocessReadQuery("rows[]"));
    }

    @Test
    void nativeCucumberValuesUseTheNormalNodeMapReferenceRegistry() {
        NodeMap map = runMap();
        DataTable table = DataTable.create(List.of(
                List.of("key", "value"),
                List.of("a", "b")
        ));
        DocString docString = DocString.create("{\"name\":\"Alice\"}", "json");

        map.put("table", table);
        map.put("docString", docString);

        assertSame(table, map.get("table"));
        assertSame(docString, map.get("docString"));

        TestProcessor processor = new TestProcessor(map);
        assertSame(table, ValueFormatting.fromReferenceText(
                processor.resolveWholeText("<table>")));
        assertSame(docString, ValueFormatting.fromReferenceText(
                processor.resolveWholeText("<docString>")));
    }

    @Test
    void valueWrappersPreserveNativeCucumberValues() {
        DataTable table = DataTable.create(List.of(
                List.of("key", "value"),
                List.of("a", "b")
        ));
        DocString docString = DocString.create("{\"name\":\"Alice\"}", "json");

        assertSame(table, ValueWrapper.createValueWrapper(table).getValue());
        assertSame(docString, ValueWrapper.createValueWrapper(docString).getValue());
        assertSame(table, convertSpecialValues(table));
        assertSame(docString, convertSpecialValues(docString));
    }

    @Test
    void quotedJsonTextDropsParserEscapesBeforeItIsSaved() {
        ValueWrapper objectText = ValueWrapper.createValueWrapper(
                "\"{\\\"active\\\":true,\\\"meta\\\":{\\\"score\\\":2}}\""
        );
        ValueWrapper arrayText = ValueWrapper.createValueWrapper(
                "\"[\\\"compiler\\\",2,{\\\"stable\\\":true}]\""
        );

        assertEquals(
                "{\"active\":true,\"meta\":{\"score\":2}}",
                objectText.getValue()
        );
        assertEquals(
                "[\"compiler\",2,{\"stable\":true}]",
                arrayText.getValue()
        );
    }

    @Test
    void setDataTableStoresTheNativeTableInTheRunMap() {
        MappingProcessor.resetCommonMaps();
        DataTable table = DataTable.create(List.of(
                List.of("key", "value"),
                List.of("a", "b")
        ));

        MappingSteps.setDataTable("DataTable1", table);

        assertSame(table, MappingProcessor.getRunMap().get("DataTable1"));
    }

    @Test
    void nativeDataTableCanDriveDataRowIterationWithoutChangingTheStoredValue() {
        NodeMap map = runMap();
        DataTable table = DataTable.create(List.of(
                List.of("key1", "key2"),
                List.of("a", "b"),
                List.of("c", "d")
        ));
        map.put("DataTable1", table);

        DataTable retrieved = assertInstanceOf(DataTable.class, map.get("DataTable1"));
        var rows = io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap(retrieved)
                .get("Data Row");

        assertSame(table, retrieved);
        assertEquals(2, rows.size());
        assertEquals("a", rows.getFirst().get("key1").getFirst());
        assertEquals("d", rows.getLast().get("key2").getFirst());
    }

    @Test
    void explicitBackticksDistinguishLiteralAndNestedPropertiesForReadsAndWrites() {
        NodeMap map = runMap();

        map.put("`customer.name`", "literal");
        map.put("customer.name", "nested");

        assertEquals("literal", map.get("`customer.name`"));
        assertEquals("nested", map.get("customer.name"));
    }

    @Test
    void questionPrefixedSimplePathSegmentsAreLiteralProperties() {
        NodeMap map = runMap();

        map.put("?keyA", "root");
        map.put("topLevelProp.?keyA", "nested");

        assertEquals("root", map.get("?keyA"));
        assertEquals("nested", map.get("topLevelProp.?keyA"));
        assertEquals("`?keyA`[][-1]", Tokenized.preprocessReadQuery("?keyA"));
        assertEquals(
                "topLevelProp[][-1].`?keyA`",
                Tokenized.preprocessReadQuery("topLevelProp.?keyA")
        );
    }

    @Test
    void legalJsonataQuestionMarkSyntaxIsNotGloballyRewritten() {
        String ternary = "true ? 'yes' : 'no'";
        assertEquals(ternary, Tokenized.preprocessReadQuery(ternary));
    }

    @Test
    void literalQueryHelperSupportsAPropertyContainingABacktick() {
        ObjectNode root = MAPPER.createObjectNode().put("a`b", "value");
        String query = Tokenized.quoteLiteralProperty("a`b");

        assertEquals("value", Tokenized.evaluate(root, query).textValue());
        assertTrue(query.startsWith("$lookup("));
    }

    @Test
    void fallbackCoversMissingNullEmptyAndBlankPrimaryValues() {
        TestProcessor processor = processor();
        processor.put("`?missing`", "missing-default");
        processor.put("`?nullValue`", "null-default");
        processor.put("`?empty`", "empty-default");
        processor.put("`?blank`", "blank-default");

        processor.put("nullValue", NullNode.instance);
        processor.put("empty", "");
        processor.put("blank", "   ");

        assertEquals("missing-default", processor.get("missing"));
        assertEquals("null-default", processor.get("nullValue"));
        assertEquals("empty-default", processor.get("empty"));
        assertEquals("blank-default", processor.get("blank"));
    }

    @Test
    void nonblankPrimaryWinsAndMissingFallbackPreservesExistingNullResult() {
        TestProcessor processor = processor();
        processor.put("keyA", "actual");
        processor.put("`?keyA`", "default");

        assertEquals("actual", processor.get("keyA"));
        assertNull(processor.get("unmapped"));
    }

    @Test
    void fallbackForAPathUsesOneRootLiteralProperty() {
        TestProcessor processor = processor();
        processor.put("`?customer.name`", "path-default");

        assertEquals("path-default", processor.get("customer.name"));
    }

    @Test
    void structuredValuesRenderAsCompactJsonAndRemainStructuredAsWholeValues() {
        TestProcessor processor = processor();
        ObjectNode object = MAPPER.createObjectNode().put("name", "Alice");
        ArrayNode array = MAPPER.createArrayNode().add("a").add("b");
        processor.put("object", object);
        processor.put("array", array);

        assertEquals("value={\"name\":\"Alice\"}", processor.resolveWholeText("value=<object>"));
        assertEquals("value=[\"a\",\"b\"]", processor.resolveWholeText("value=<array>"));
        assertEquals(object, processor.resolveWholeValue("<object>"));
        assertEquals(array, processor.resolveWholeValue("<array>"));
    }

    @Test
    void directiveResolverSupportsConversionsPipelinesAndDynamicQueries() throws Exception {
        ParsingMap processor = directiveProcessor();
        processor.put("payload", "{\"name\":\"Alice\",\"items\":[{\"name\":\"first\"},{\"name\":\"second\"}]}");
        processor.put("idx", 1);

        JsonNode converted = assertInstanceOf(
                JsonNode.class,
                processor.resolveWholeValue("<payload~JSON;>")
        );
        assertEquals("Alice", converted.get("name").textValue());
        assertEquals(
                "Alice",
                processor.resolveWholeValue("<payload~JSON;::name>")
        );
        assertEquals(
                "second",
                processor.resolveWholeValue(
                        "<payload~JSON;::items[<idx>].name>"
                )
        );
        assertEquals(
                "a::b",
                processor.resolveWholeValue(
                        "<value:{\"text\":\"a::b\"}~JSON;::text>"
                )
        );
        assertEquals(
                "a::b",
                processor.resolveWholeValue(
                        "<value:{\\\"text\\\":\\\"a::b\\\"}~JSON;::text>"
                )
        );
    }

    @Test
    void directiveResolverSupportsAllConversionDirectiveFamilies() {
        ParsingMap processor = directiveProcessor();
        processor.put("json", "{\"name\":\"Alice\"}");
        processor.put("xml", "<root><name>Alice</name></root>");
        processor.put("yaml", "name: Alice");
        processor.put("array", "[1,2,2]");
        processor.put("table", "[[\"key\",\"value\"],[\"a\",\"b\"]]");

        assertEquals(
                "{\"name\":\"Alice\"}",
                processor.resolveWholeValue("<json~JSON;~JSON-STRING;>")
        );
        assertEquals(
                "Alice",
                processor.resolveWholeValue("<xml~XML;::name>")
        );
        assertTrue(
                String.valueOf(processor.resolveWholeValue("<json~JSON;~XML-STRING;>"))
                        .contains("<name>Alice</name>")
        );
        assertEquals(
                "Alice",
                processor.resolveWholeValue("<yaml~YAML;::name>")
        );
        String yamlString = String.valueOf(
                processor.resolveWholeValue("<json~JSON;~YAML-STRING;>")
        );
        assertTrue(yamlString.contains("name:"));
        assertTrue(yamlString.contains("Alice"));
        assertInstanceOf(
                JsonNode.class,
                processor.resolveWholeValue("<json~DATA;>")
        );
        assertEquals(
                "{\"name\":\"Alice\"}",
                processor.resolveWholeValue("<json~JSON;~STRING;>")
        );
        assertInstanceOf(
                Map.class,
                processor.resolveWholeValue("<json~JSON;~MAP;>")
        );
        assertInstanceOf(
                List.class,
                processor.resolveWholeValue("<array~JSON;~LIST;>")
        );
        assertEquals(
                Set.of(1, 2),
                processor.resolveWholeValue("<array~JSON;~SET;>")
        );
        Object multimap = processor.resolveWholeValue(
                "<value:{\"a\":[1,2]}~JSON;~MULTIMAP;>"
        );
        assertEquals(
                List.of(1, 2),
                assertInstanceOf(LinkedListMultimap.class, multimap).get("a")
        );
        DataTable dataTable = assertInstanceOf(
                DataTable.class,
                processor.resolveWholeValue("<table~JSON;~DATATABLE;>")
        );
        assertEquals("b", dataTable.cells().get(1).get(1));
        DocString docString = assertInstanceOf(
                DocString.class,
                processor.resolveWholeValue("<json~JSON;~DOCSTRING;>")
        );
        assertEquals("json", docString.getContentType());
        assertEquals(
                "Alice",
                processor.resolveWholeValue("~[~json~JSON;::name~]~")
        );
        assertEquals(
                "<root><name>Alice</name></root>",
                processor.resolveWholeValue(
                        "<root><name>~[~json~JSON;::name~]~</name></root>"
                )
        );
    }

    @Test
    void directiveResolverSupportsMasksUnresolvedValuesAndSpecialMarkers() {
        ParsingMap processor = directiveProcessor();
        processor.put("name", "Alice");
        processor.put("deferred", "<name>");

        assertEquals("Alice", processor.resolveWholeValue("<deferred>"));
        assertEquals(
                "<name>",
                processor.resolveWholeValue("<deferred~unresolved;>")
        );
        assertEquals(
                "hello <name> :: ~JSON;",
                processor.resolveWholeValue(
                        "<value:~^^hello <name> :: ~JSON;^^~>"
                )
        );
        assertEquals("", processor.resolveWholeValue("<^~EMPTY~^>"));
        assertEquals("\t", processor.resolveWholeValue("<^~TAB~^>"));
        assertSame(
                NullNode.getInstance(),
                processor.resolveWholeValue("<^~NULL~^>")
        );
        assertEquals(
                Double.POSITIVE_INFINITY,
                processor.resolveWholeValue("<^~INF~^>")
        );
        assertEquals(
                Double.NEGATIVE_INFINITY,
                processor.resolveWholeValue("<^~-INF~^>")
        );
        assertEquals(
                Double.NaN,
                processor.resolveWholeValue("<^~NAN~^>")
        );
        assertEquals(
                "left=; right=ok",
                processor.resolveWholeText(
                        "left=<?missing>; right=<value:ok>"
                )
        );
    }

    @Test
    void unquotedDirectiveEmbedsStructuredValuesAsRawJson() throws Exception {
        ParsingMap processor = directiveProcessor();
        ObjectNode object = MAPPER.createObjectNode()
                .put("name", "Alice")
                .set("metadata", MAPPER.createObjectNode().put("active", true));
        processor.put("object", object);

        JsonNode resolved = MAPPER.readTree(processor.resolveWholeText(
                """
                {
                  "object": "<object~unquoted;>",
                  "objectText": "<object>"
                }
                """));

        assertEquals(object, resolved.get("object"));
        assertEquals(object.toString(), resolved.get("objectText").textValue());
    }

    @Test
    void embeddedStructuredReferencesEscapeForTheContainingQuote() throws Exception {
        ParsingMap processor = directiveProcessor();
        ObjectNode object = MAPPER.createObjectNode()
                .put("name", "Alice")
                .set("metadata", MAPPER.createObjectNode().put("active", true));
        processor.put("object", object);

        JsonNode resolved = MAPPER.readTree(processor.resolveWholeText(
                """
                {
                  "message": "profile=<object>",
                  "direct": "<object>"
                }
                """));

        assertEquals("profile=" + object, resolved.get("message").textValue());
        assertEquals(object.toString(), resolved.get("direct").textValue());
    }

    @Test
    void tableRowAndStructuredObjectKeysUseTheSameDirectiveGrammar() {
        MappingProcessor.resetCommonMaps();
        MappingSteps.mapValues(
                null,
                null,
                "RUN",
                DataTable.create(List.of(
                        List.of("payload~JSON;", "{\"count\":3}"),
                        List.of(
                                "deferred~JSON;~unresolved;",
                                "{\"later\":\"<lateValue>\"}"
                        )
                ))
        );
        Object tableMapped = MappingProcessor.getRunMap().get("payload");
        assertEquals(3, assertInstanceOf(JsonNode.class, tableMapped).get("count").intValue());
        JsonNode deferred = assertInstanceOf(
                JsonNode.class,
                MappingProcessor.getRunMap().get("deferred")
        );
        assertEquals("<lateValue>", deferred.get("later").textValue());

        Object converted = convertSpecialValues(Map.of(
                "payload~JSON;",
                "{\"active\":true}"
        ));
        Map<?, ?> map = assertInstanceOf(Map.class, converted);
        JsonNode payload = assertInstanceOf(JsonNode.class, map.get("payload"));
        assertTrue(payload.get("active").booleanValue());

        Object wrapped = convertSpecialValues(Map.of(
                "~JSON;",
                "{\"score\":4}"
        ));
        assertEquals(4, assertInstanceOf(JsonNode.class, wrapped).get("score").intValue());
    }

    @Test
    void removedConversionAndUnquoteSyntaxFailsWithMigrationErrors() {
        ParsingMap processor = directiveProcessor();
        processor.put("object", MAPPER.createObjectNode().put("name", "Alice"));

        IllegalArgumentException unquote = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeText("<object~unquote>")
        );
        assertTrue(unquote.getMessage().contains("~unquoted;"));

        IllegalArgumentException oldObjectMarker = assertThrows(
                IllegalArgumentException.class,
                () -> convertSpecialValues(Map.of(
                        "~JSON~",
                        "{\"name\":\"Alice\"}"
                ))
        );
        assertTrue(oldObjectMarker.getMessage().contains("was removed"));

        IllegalArgumentException oldStringMarker = assertThrows(
                IllegalArgumentException.class,
                () -> convertSpecialValues("~INT~:3")
        );
        assertTrue(oldStringMarker.getMessage().contains("was removed"));

        IllegalArgumentException oldBareNullMarker = assertThrows(
                IllegalArgumentException.class,
                () -> convertSpecialValues("^~NULL~^")
        );
        assertTrue(oldBareNullMarker.getMessage().contains("was removed"));

        IllegalArgumentException objectBehavior = assertThrows(
                IllegalArgumentException.class,
                () -> convertSpecialValues(Map.of(
                        "payload~unresolved;",
                        "<laterValue>"
                ))
        );
        assertTrue(objectBehavior.getMessage().contains("conversion directives only"));

        IllegalArgumentException tableUnquoted = assertThrows(
                IllegalArgumentException.class,
                () -> MappingSteps.mapValues(
                        null,
                        null,
                        "RUN",
                        DataTable.create(List.of(
                                List.of("payload~unquoted;", "value")
                        ))
                )
        );
        assertTrue(tableUnquoted.getMessage().contains("row key"));

        IllegalArgumentException unquotedPlacement = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeText("<object~unquoted;>")
        );
        assertTrue(unquotedPlacement.getMessage().contains("matching quote pair"));

        IllegalArgumentException unquotedOrder = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeText(
                        "\"<object~unquoted;~JSON;>\""
                )
        );
        assertTrue(unquotedOrder.getMessage().contains("terminal"));
    }

    @Test
    void pipelineMissingValuesFailClearly() {
        ParsingMap processor = directiveProcessor();
        processor.put("payload", "{\"name\":\"Alice\"}");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeValue(
                        "<payload~JSON;::missing.path>"
                )
        );
        assertTrue(error.getMessage().contains("produced no value"));

        IllegalArgumentException missingSource = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeValue(
                        "<missing~JSON;::name>"
                )
        );
        assertTrue(missingSource.getMessage().contains("pipeline source"));
    }

    @Test
    void mergePutMutatesTheNormalGetResultInPlace() {
        NodeMap map = runMap();
        ObjectNode initial = MAPPER.createObjectNode()
                .put("name", "Ada")
                .set("settings", MAPPER.createObjectNode()
                        .put("theme", "dark")
                        .put("retries", 2));
        initial.set("tags", MAPPER.createArrayNode().add("alpha"));
        map.put("customer", initial);

        ObjectNode selected = assertInstanceOf(ObjectNode.class, map.get("customer"));
        ObjectNode incoming = MAPPER.createObjectNode()
                .put("active", true)
                .set("settings", MAPPER.createObjectNode()
                        .put("retries", 4)
                        .put("enabled", true));
        incoming.set("tags", MAPPER.createArrayNode().add("beta"));
        incoming.set("nullable", NullNode.instance);

        map.put("customer~merge;", incoming);

        ObjectNode merged = assertInstanceOf(ObjectNode.class, map.get("customer"));
        assertSame(selected, merged);
        assertEquals("Ada", merged.get("name").textValue());
        assertEquals("dark", merged.get("settings").get("theme").textValue());
        assertEquals(4, merged.get("settings").get("retries").intValue());
        assertTrue(merged.get("settings").get("enabled").booleanValue());
        assertEquals(List.of("alpha", "beta"), List.of(
                merged.get("tags").get(0).textValue(),
                merged.get("tags").get(1).textValue()
        ));
        assertTrue(merged.get("nullable").isNull());
    }

    @Test
    void mergePutAppendsArraysFallsBackForMissingAndIgnoresNullInput() {
        NodeMap map = runMap();
        map.put("items", MAPPER.createArrayNode().add(1).add(2));
        ArrayNode selected = assertInstanceOf(ArrayNode.class, map.get("items"));

        map.put("items~merge;", MAPPER.createArrayNode().add(3).add(4));

        ArrayNode merged = assertInstanceOf(ArrayNode.class, map.get("items"));
        assertSame(selected, merged);
        assertEquals(List.of(1, 2, 3, 4), List.of(
                merged.get(0).intValue(),
                merged.get(1).intValue(),
                merged.get(2).intValue(),
                merged.get(3).intValue()
        ));

        ObjectNode fallback = MAPPER.createObjectNode().put("created", true);
        map.put("missing~merge;", fallback);
        assertTrue(assertInstanceOf(ObjectNode.class, map.get("missing"))
                .get("created").booleanValue());

        ObjectNode beforeNull = assertInstanceOf(ObjectNode.class, map.get("missing"));
        map.put("missing~merge;", null);
        map.put("missing~merge;", NullNode.instance);
        assertSame(beforeNull, map.get("missing"));
    }

    @Test
    void mergePutRejectsIncompatibleContainerAndScalarTypes() {
        NodeMap map = runMap();
        map.put("object", MAPPER.createObjectNode().put("name", "Ada"));
        map.put("text", "Ada");

        IllegalArgumentException mixedContainers = assertThrows(
                IllegalArgumentException.class,
                () -> map.put("object~merge;", MAPPER.createArrayNode().add(1))
        );
        assertTrue(mixedContainers.getMessage().contains("ObjectNode"));
        assertTrue(mixedContainers.getMessage().contains("ArrayNode"));

        IllegalArgumentException scalar = assertThrows(
                IllegalArgumentException.class,
                () -> map.put("text~merge;", MAPPER.createObjectNode().put("active", true))
        );
        assertTrue(scalar.getMessage().contains("String"));
        assertTrue(scalar.getMessage().contains("ObjectNode"));
    }

    @Test
    void mappingStepsSupportMergeDestinationKeysAndConvertedTableValues() {
        MappingProcessor.resetCommonMaps();
        MappingSteps.mapDocString(
                "customer",
                "OBJECT",
                "RUN",
                DocString.create(
                        "{\"name\":\"Ada\",\"settings\":{\"theme\":\"dark\"}}",
                        "json"
                )
        );
        ObjectNode selected = assertInstanceOf(
                ObjectNode.class,
                MappingProcessor.getRunMap().get("customer")
        );

        MappingSteps.mapDocString(
                "customer~merge;",
                "OBJECT",
                "RUN",
                DocString.create(
                        "{\"active\":true,\"settings\":{\"retries\":3}}",
                        "json"
                )
        );

        ObjectNode merged = assertInstanceOf(
                ObjectNode.class,
                MappingProcessor.getRunMap().get("customer")
        );
        assertSame(selected, merged);
        assertEquals("dark", merged.get("settings").get("theme").textValue());
        assertEquals(3, merged.get("settings").get("retries").intValue());

        MappingSteps.mapValues(
                null,
                null,
                "RUN",
                DataTable.create(List.of(
                        List.of("tableMerge~JSON;", "{\"nested\":{\"first\":1},\"items\":[1]}")
                ))
        );
        MappingSteps.mapValues(
                null,
                null,
                "RUN",
                DataTable.create(List.of(
                        List.of("tableMerge~JSON;~merge;", "{\"nested\":{\"second\":2},\"items\":[2]}")
                ))
        );

        JsonNode tableMerge = assertInstanceOf(
                JsonNode.class,
                MappingProcessor.getRunMap().get("tableMerge")
        );
        assertEquals(1, tableMerge.get("nested").get("first").intValue());
        assertEquals(2, tableMerge.get("nested").get("second").intValue());
        assertEquals(2, tableMerge.get("items").size());
    }

    @Test
    void mergeDirectiveIsRejectedOutsidePutDestinationKeys() {
        ParsingMap processor = directiveProcessor();
        processor.put("object", MAPPER.createObjectNode().put("name", "Ada"));

        IllegalArgumentException reference = assertThrows(
                IllegalArgumentException.class,
                () -> processor.resolveWholeValue("<object~merge;>")
        );
        assertTrue(reference.getMessage().contains("destination keys"));

        IllegalArgumentException structuredKey = assertThrows(
                IllegalArgumentException.class,
                () -> convertSpecialValues(Map.of(
                        "payload~merge;",
                        Map.of("active", true)
                ))
        );
        assertTrue(structuredKey.getMessage().contains("conversion directives only"));
    }

    @Test
    void assertionWrappersRetainAndCompareJacksonContainers() throws Exception {
        JsonNode first = MAPPER.readTree("{\"name\":\"Alice\",\"tags\":[\"a\"]}");
        JsonNode same = MAPPER.readTree("{\"name\":\"Alice\",\"tags\":[\"a\"]}");
        JsonNode different = MAPPER.readTree("{\"name\":\"Bob\",\"tags\":[\"a\"]}");

        ValueWrapper wrapped = ValueWrapper.createValueWrapper(first);
        ArrayNode array = MAPPER.createArrayNode().add(first);

        assertSame(first, convertSpecialValues(first));
        assertSame(array, convertSpecialValues(array));
        assertSame(first, wrapped.getValue());
        assertEquals(first.toString(), wrapped.asNormalizedText());
        assertEquals(wrapped, same);
        assertTrue(!wrapped.equals(different));
    }

    private static NodeMap runMap() {
        return new NodeMap(MapConfigurations.MapType.RUN_MAP);
    }

    private static TestProcessor processor() {
        return new TestProcessor(runMap());
    }

    private static ParsingMap directiveProcessor() {
        return new ParsingMap(runMap());
    }

    private static final class TestProcessor extends MappingProcessor {
        private TestProcessor(NodeMap map) {
            super(map);
        }
    }
}
