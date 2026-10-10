package tools.dscode.common.mappings.queries;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.NodeMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

class TopLevelCollectionTest {

    @Test
    void fiveScalarSavesAddressTheCollectionAndTheLastItem() {
        NodeMap map = new NodeMap();
        map.put("topProp", "v1");
        map.put("topProp", "v2");
        map.put("topProp", "v3");
        map.put("topProp", "v4");
        map.put("topProp", "v5");

        assertEquals("v5", map.get("topProp"));
        assertEquals("v1", map.get("topProp #1"));
        assertEquals("v1", map.get("topProp[0]"));
        assertEquals("v2", map.get("topProp #2"));
        assertEquals("v2", map.get("topProp[1]"));
        assertEquals("v3", map.get("topProp #3"));
        assertEquals("v3", map.get("topProp[2]"));
        assertEquals("v5", map.get("topProp #last"));
        assertNull(map.get("topProp #9"));
        assertEquals("topProp[0]", Tokenized.preprocessReadQuery("topProp[0]"));
        assertEquals("topProp[0]", Tokenized.preprocessReadQuery("topProp #1"));
    }

    @Test
    void aSavedListConcatenatesAndANestedPathReadsTheRow() {
        NodeMap map = new NodeMap();
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(row("r1", "ready", "SKU-1"));
        rows.add(row("r2", "done", "SKU-2"));

        map.put("orders", rows);

        assertEquals("r1", map.get("orders #1.id"));
        assertEquals("r2", map.get("orders #2.id"));
        assertEquals("done", map.get("orders #2.status"));
        assertEquals("done", map.get("orders[1].status"));
        assertEquals("done", map.get("orders.status"));
        assertEquals("SKU-2", map.get("orders #2.items #1.sku"));
        assertEquals("r2", ((ObjectNode) map.get("orders")).get("id").textValue());
    }

    @Test
    void emptyListsAddNothingAndNestedListsFlattenOnce() {
        NodeMap map = new NodeMap();
        map.put("kept", "sentinel");
        map.put("kept", MAPPER.createArrayNode());
        assertEquals("sentinel", map.get("kept"));

        ArrayNode nested = MAPPER.createArrayNode();
        nested.add("start");
        nested.add(MAPPER.createArrayNode().add("a").add("b"));
        nested.add(MAPPER.createArrayNode().add("c").add("d"));
        nested.add("end");
        map.put("y", nested);

        assertEquals("b", map.get("y #2 #2"));
        assertEquals("end", map.get("y"));
        assertEquals("start", map.get("y #1"));
        ArrayNode whole = (ArrayNode) map.get("y[]");
        assertEquals(4, whole.size());
        assertEquals("end", whole.get(3).textValue());
    }

    @Test
    void explicitAppendMergeNestedPathAndSingletonStayUnflattened() {
        NodeMap map = new NodeMap();
        ArrayNode whole = MAPPER.createArrayNode().add("whole");
        map.put("boxed[]", whole);
        assertEquals("whole", map.get("boxed[0][0]"));
        assertEquals(1, ((ArrayNode) map.get("boxed[]")).size());

        map.put("items", MAPPER.createArrayNode().add(1).add(2));
        map.put("items~merge;", MAPPER.createArrayNode().add(3).add(4));
        assertEquals(1, map.get("items[0]"));
        assertEquals(4, map.get("items[3]"));
        assertEquals(4, map.get("items"));

        ArrayNode boxed = MAPPER.createArrayNode().add(8).add(9);
        map.put("kept[]", boxed);
        ArrayNode selected = (ArrayNode) map.get("kept");
        map.put("kept~merge;", MAPPER.createArrayNode().add(10));
        assertSame(selected, map.get("kept"));
        assertEquals(10, selected.get(2).intValue());

        map.put("parent.name", "holder");
        map.put("parent.nested", MAPPER.createArrayNode().add("x").add("y"));
        assertEquals("x", map.get("parent.nested[0]"));
        assertEquals("y", map.get("parent.nested[1]"));
        assertEquals(1, ((ArrayNode) map.get("parent[]")).size());

        map.put("_solo", "one");
        map.put("_solo", "two");
        assertEquals("two", map.get("_solo"));
        map.put("_wrapped", List.of("only", "item"));
        assertEquals("only", map.get("_wrapped[0]"));
        assertEquals("item", map.get("_wrapped[1]"));
        map.put("_wrapped", "replaced");
        assertEquals("replaced", map.get("_wrapped"));
    }

    private static ObjectNode row(String id, String status, String sku) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", id);
        row.put("status", status);
        row.putArray("items").addObject().put("sku", sku);
        return row;
    }
}
