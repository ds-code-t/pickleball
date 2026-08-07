package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.cucumber.docstring.DocString;
import org.junit.jupiter.api.Test;
import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.dataelements.DataAttribute.SIZE;
import static tools.dscode.common.dataelements.DataAttribute.VALUE;
import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementKind.DATA_STRING;
import static tools.dscode.common.dataelements.DataElementKind.JSON_DATA;
import static tools.dscode.common.dataelements.DataElementKind.JSON_STRING;
import static tools.dscode.common.dataelements.DataElementKind.LIST;
import static tools.dscode.common.dataelements.DataElementKind.MAP;
import static tools.dscode.common.dataelements.DataElementKind.MULTIMAP;
import static tools.dscode.common.dataelements.DataElementKind.SET;
import static tools.dscode.common.dataelements.DataElementKind.STRUCTURED_DATA;
import static tools.dscode.common.dataelements.DataElementKind.XML_DATA;
import static tools.dscode.common.dataelements.DataElementKind.XML_STRING;
import static tools.dscode.common.dataelements.DataElementKind.YAML_DATA;
import static tools.dscode.common.dataelements.DataElementKind.YAML_STRING;

public final class DataElementPhaseFourAndSixChecks {
    private final CollectionQueryEngine collectionEngine =
            new CollectionQueryEngine();
    private final DataElementRuntime runtime = new DataElementRuntime();

    @Test
    void listsExpandOneLevelAndUseOperationSpecificProjections() {
        List<List<String>> source = List.of(
                List.of("alpha", "middle", "omega"),
                List.of("beta", "tail")
        );

        assertEquals(2, queryLists(source).size());
        assertEquals(
                List.of("alpha", "middle", "omega"),
                queryLists(
                        source,
                        TextOp.of(
                                "alpha",
                                ExecutionDictionary.Op.EQUALS
                        )
                ).first().value()
        );
        assertEquals(
                List.of("beta", "tail"),
                queryLists(
                        source,
                        TextOp.of(
                                "tail",
                                ExecutionDictionary.Op.ENDS_WITH
                        )
                ).first().value()
        );
        assertEquals(
                List.of("alpha", "middle", "omega"),
                queryLists(
                        source,
                        TextOp.of(
                                "middle",
                                ExecutionDictionary.Op.CONTAINS
                        )
                ).first().value()
        );
    }

    @Test
    void listNumericComparisonsRequireSizeOrCount() {
        List<List<String>> source = List.of(
                List.of("a", "b", "c"),
                List.of("d")
        );
        TextOp greaterThanTwo = TextOp.of(
                2,
                ExecutionDictionary.Op.GT
        );

        assertThrows(
                DataQueryException.class,
                () -> queryLists(source, greaterThanTwo)
        );

        DataSelection selected = collectionEngine.query(
                DataContextFactory.create(source, LIST),
                DataQuery.builder(LIST, PLURAL)
                        .comparisonAttribute(SIZE)
                        .predicate(greaterThanTwo)
                        .build()
        );
        assertEquals(1, selected.size());
        assertEquals(List.of("a", "b", "c"), selected.first().value());
    }

    @Test
    void mapsExpandOneLevelAndKeepKeyValueAndStringProjectionsSeparate() {
        Map<String, Object> first = linkedMap(
                "id", "one",
                "status", "ready"
        );
        Map<String, Object> second = linkedMap(
                "code", "two",
                "status", "pending"
        );
        List<Map<String, Object>> source = List.of(first, second);

        DataSelection keys = collectionEngine.query(
                DataContextFactory.create(source, MAP),
                DataQuery.builder(MAP, PLURAL)
                        .predicate(TextOp.of(
                                "id",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .build()
        );
        DataSelection values = collectionEngine.query(
                DataContextFactory.create(source, MAP),
                DataQuery.builder(MAP, PLURAL)
                        .comparisonAttribute(VALUE)
                        .predicate(TextOp.of(
                                "pending",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .build()
        );

        assertEquals(1, keys.size());
        assertSame(first, keys.first().value());
        assertEquals(1, values.size());
        assertSame(second, values.first().value());
        assertEquals(
                "{id=one, status=ready}",
                keys.first().returnProjection(DataAttribute.STRING)
        );
    }

    @Test
    void setsPreserveEncounterOrderAndRejectOrderedPredicates() {
        Set<String> source = new LinkedHashSet<>(
                List.of("alpha", "beta", "gamma")
        );
        DataContext context = DataContextFactory.create(source, SET);

        DataSelection contains = collectionEngine.query(
                context,
                DataQuery.builder(SET, SINGULAR)
                        .predicate(TextOp.of(
                                "beta",
                                ExecutionDictionary.Op.CONTAINS
                        ))
                        .build()
        );

        assertSame(source, contains.materializeTerminal());
        assertEquals(
                List.of("alpha", "beta", "gamma"),
                contains.first().associatedValues()
        );
        assertThrows(
                DataQueryException.class,
                () -> collectionEngine.query(
                        context,
                        DataQuery.builder(SET, SINGULAR)
                                .predicate(TextOp.of(
                                        "alpha",
                                        ExecutionDictionary.Op.STARTS_WITH
                                ))
                                .build()
                )
        );
    }

    @Test
    void multimapsPreserveDuplicateKeysValuesAndGlobalOrder() {
        LinkedListMultimap<String, String> source =
                LinkedListMultimap.create();
        source.put("status", "ready");
        source.put("status", "ready");
        source.put("status", "pending");
        source.put("owner", "team");

        DataSelection selection = collectionEngine.query(
                DataContextFactory.create(source, MULTIMAP),
                DataQuery.builder(MULTIMAP, SINGULAR)
                        .predicate(TextOp.of(
                                "status",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .build()
        );

        assertSame(source, selection.materializeTerminal());
        assertEquals(
                List.of("status", "status", "status", "owner"),
                selection.first().associatedKeys()
        );
        assertEquals(
                List.of("ready", "ready", "pending", "team"),
                selection.first().associatedValues()
        );
    }

    @Test
    void mapWithArrayValuesConvertsOnlyForExplicitMultimapQueries() {
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("status", List.of("ready", "ready"));
        source.put("owner", List.of("team"));

        DataSelection selection = collectionEngine.query(
                DataContextFactory.create(source, MULTIMAP),
                DataQuery.builder(MULTIMAP, SINGULAR).build()
        );

        Multimap<?, ?> converted = assertInstanceOf(
                Multimap.class,
                selection.materializeTerminal()
        );
        assertEquals(
                List.of("ready", "ready", "team"),
                List.copyOf(converted.values())
        );
    }

    @Test
    void collectionRuntimeAggregatesPluralsAndSeparatesAnyFromEvery() {
        List<List<String>> source = List.of(
                List.of("one"),
                List.of("two"),
                List.of("three")
        );
        TerminalResult terminal = assertInstanceOf(
                TerminalResult.class,
                runtime.execute(
                        source,
                        DataQuery.builder(LIST, PLURAL).build()
                )
        );
        assertEquals(1, terminal.values().size());
        assertEquals(source, terminal.values().getFirst());

        IterationResult optional = assertInstanceOf(
                IterationResult.class,
                runtime.execute(
                        source,
                        DataQuery.builder(LIST, SINGULAR)
                                .any()
                                .predicate(TextOp.of(
                                        "missing",
                                        ExecutionDictionary.Op.EQUALS
                                ))
                                .resultUse(DataResultUse.ITERATION)
                                .build()
                )
        );
        assertTrue(optional.values().isEmpty());

        assertThrows(
                DataQueryException.class,
                () -> runtime.execute(
                        source,
                        DataQuery.builder(LIST, SINGULAR)
                                .every()
                                .predicate(TextOp.of(
                                        "missing",
                                        ExecutionDictionary.Op.EQUALS
                                ))
                                .resultUse(DataResultUse.ITERATION)
                                .build()
                )
        );
    }

    @Test
    void structuredDataUsesJsonXmlThenYamlDetection() {
        JsonNode json = structured("{\"name\":\"Ada\",\"active\":true}");
        JsonNode xml = structured(
                "<person id=\"7\"><name>Ada</name></person>"
        );
        JsonNode yaml = structured("name: Ada\nactive: true");
        JsonNode scalar = structured("plain text");

        assertEquals("Ada", json.get("name").asText());
        assertTrue(json.get("active").asBoolean());
        assertEquals("7", xml.get("id").asText());
        assertEquals("Ada", xml.get("name").asText());
        assertEquals("Ada", yaml.get("name").asText());
        assertTrue(yaml.get("active").asBoolean());
        assertTrue(scalar.isTextual());
        assertEquals("plain text", scalar.asText());
    }

    @Test
    void typedDocStringsUseTheirDeclaredStructuredFormat() {
        DocString jsonDocString = DocString.create(
                "{\"name\":\"Ada\"}",
                "application/json"
        );
        DocString yamlDocString = DocString.create(
                "name: Grace",
                "yaml"
        );

        JsonNode json = assertInstanceOf(
                JsonNode.class,
                StructuredDataConverter.convert(
                        jsonDocString,
                        STRUCTURED_DATA
                )
        );
        JsonNode yaml = assertInstanceOf(
                JsonNode.class,
                StructuredDataConverter.convert(
                        yamlDocString,
                        STRUCTURED_DATA
                )
        );

        assertEquals("Ada", json.get("name").asText());
        assertEquals("Grace", yaml.get("name").asText());
    }

    @Test
    void explicitFormatsRejectInvalidInputAndUnsafeXmlDeclarations() {
        assertThrows(
                DataQueryException.class,
                () -> StructuredDataConverter.convert(
                        "{\"name\":",
                        JSON_DATA
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> StructuredDataConverter.convert(
                        "name: [one, two",
                        YAML_DATA
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> StructuredDataConverter.convert(
                        "<person><name>Ada</person>",
                        XML_DATA
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> StructuredDataConverter.convert(
                        "<!DOCTYPE data [<!ENTITY x SYSTEM \"file:///tmp/x\">]>"
                                + "<data>&x;</data>",
                        XML_DATA
                )
        );
    }

    @Test
    void structuredStringsUseStableCompactAndNamedRootSerialization() {
        Map<String, Object> source = linkedMap(
                "name", "Ada",
                "active", true
        );

        assertEquals(
                "{\"name\":\"Ada\",\"active\":true}",
                StructuredDataConverter.convert(source, JSON_STRING)
        );
        assertEquals(
                "\"plain text\"",
                StructuredDataConverter.convert("plain text", JSON_STRING)
        );

        String yaml = (String) StructuredDataConverter.convert(
                source,
                YAML_STRING
        );
        assertTrue(yaml.contains("name: \"Ada\"")
                || yaml.contains("name: Ada"));

        String xml = (String) StructuredDataConverter.convert(
                source,
                XML_STRING
        );
        assertTrue(xml.contains("<Data>"));
        assertTrue(xml.contains("<name>Ada</name>"));
        assertEquals(
                "already text",
                StructuredDataConverter.convert(
                        "already text",
                        DATA_STRING
                )
        );
    }

    @Test
    void formatRuntimeUsesTheSameCardinalityAndResultPolicy() {
        TerminalResult json = assertInstanceOf(
                TerminalResult.class,
                runtime.execute(
                        "{\"name\":\"Ada\"}",
                        DataQuery.builder(JSON_DATA, SINGULAR).build()
                )
        );
        ObjectNode object = assertInstanceOf(
                ObjectNode.class,
                json.values().getFirst()
        );
        assertEquals("Ada", object.get("name").asText());

        TerminalResult strings = assertInstanceOf(
                TerminalResult.class,
                runtime.execute(
                        List.of(Map.of("id", 1), Map.of("id", 2)),
                        DataQuery.builder(JSON_STRING, PLURAL).build()
                )
        );
        assertEquals(
                List.of("{\"id\":1}", "{\"id\":2}"),
                strings.values().getFirst()
        );
    }

    @Test
    void nullStructuredSourcesFollowCardinalityRules() {
        JsonNode direct = assertInstanceOf(
                JsonNode.class,
                StructuredDataConverter.convert(null, STRUCTURED_DATA)
        );
        assertTrue(direct.isNull());

        TerminalResult optional = assertInstanceOf(
                TerminalResult.class,
                runtime.execute(
                        null,
                        DataQuery.builder(DATA_STRING, PLURAL).build()
                )
        );
        assertEquals(List.of(), optional.values().getFirst());

        assertThrows(
                DataQueryException.class,
                () -> runtime.execute(
                        null,
                        DataQuery.builder(JSON_DATA, SINGULAR).build()
                )
        );
    }

    private DataSelection queryLists(
            List<List<String>> source,
            TextOp... predicates
    ) {
        DataQuery.Builder query = DataQuery.builder(LIST, PLURAL);
        for (TextOp predicate : predicates) {
            query.predicate(predicate);
        }
        return collectionEngine.query(
                DataContextFactory.create(source, LIST),
                query.build()
        );
    }

    private static JsonNode structured(String source) {
        return assertInstanceOf(
                JsonNode.class,
                StructuredDataConverter.convert(source, STRUCTURED_DATA)
        );
    }

    private static Map<String, Object> linkedMap(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }
}
