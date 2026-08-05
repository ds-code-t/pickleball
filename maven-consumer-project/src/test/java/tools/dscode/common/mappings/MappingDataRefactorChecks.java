package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.coredefinitions.MappingSteps;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void structuredValuesCanBeEmbeddedAsJsonValuesAndStrings() throws Exception {
        TestProcessor processor = processor();
        ObjectNode object = MAPPER.createObjectNode()
                .put("name", "Alice")
                .set("metadata", MAPPER.createObjectNode().put("active", true));
        ArrayNode array = MAPPER.createArrayNode()
                .add("a")
                .add(MAPPER.createObjectNode().put("nested", true));
        processor.put("object", object);
        processor.put("array", array);

        JsonNode resolved = MAPPER.readTree(processor.resolveWholeText(
                """
                {
                  "object": "<object~unquote>",
                  "array": "<array~unquote>",
                  "objectText": "<object>",
                  "arrayText": "<array>"
                }
                """));

        assertEquals(object, resolved.get("object"));
        assertEquals(array, resolved.get("array"));
        assertEquals(object.toString(), resolved.get("objectText").textValue());
        assertEquals(array.toString(), resolved.get("arrayText").textValue());
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

    private static final class TestProcessor extends MappingProcessor {
        private TestProcessor(NodeMap map) {
            super(map);
        }
    }
}
