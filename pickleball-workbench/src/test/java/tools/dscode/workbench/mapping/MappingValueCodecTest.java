package tools.dscode.workbench.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingValueCodecTest {
    @Test
    void decodesEachSupportedMappingValueType() {
        assertEquals("plain", MappingValueCodec.decode("string", "plain"));
        assertEquals(42L, MappingValueCodec.decode("numeric", "42"));
        assertEquals(1.5d, MappingValueCodec.decode("numeric", "1.5"));
        assertEquals(Boolean.TRUE, MappingValueCodec.decode("boolean", "true"));
        Object json = MappingValueCodec.decode("object-as-json", "{\"city\":\"Austin\"}");
        assertEquals("Austin", ((Map<?, ?>) json).get("city"));
        Object xml = MappingValueCodec.decode("object-as-xml", "<city id=\"1\">Austin</city>");
        assertInstanceOf(Map.class, xml);
        assertEquals("city", ((Map<?, ?>) xml).get("_name"));
        assertEquals("Austin", ((Map<?, ?>) xml).get("_text"));
    }

    @Test
    void treeEditsPreserveTypedValuesForRestore() {
        MappingTreeModel model = new MappingTreeModel(
                "OVERRIDE",
                "OVERRIDE",
                true,
                Map.of("count", 1)
        );
        MappingTreeModel edited = model
                .upsert("flag", MappingValueCodec.ValueType.BOOLEAN, "true")
                .upsert("payload", MappingValueCodec.ValueType.OBJECT_JSON, "{\"ok\":true}")
                .rename("count", "total");

        assertEquals(Boolean.TRUE, edited.values().get("flag"));
        assertEquals(1, edited.values().get("total"));
        assertEquals(true, ((Map<?, ?>) edited.values().get("payload")).get("ok"));
        assertTrue(edited.properties().stream().anyMatch(property ->
                property.key().equals("flag") && property.type() == MappingValueCodec.ValueType.BOOLEAN));
    }

    @Test
    void rejectsBlankKeysAndInvalidBooleanText() {
        MappingTreeModel model = new MappingTreeModel("OVERRIDE", "OVERRIDE", true, Map.of());
        assertThrows(IllegalArgumentException.class, () ->
                model.upsert(" ", MappingValueCodec.ValueType.STRING, "x"));
        assertThrows(IllegalArgumentException.class, () ->
                MappingValueCodec.decode(MappingValueCodec.ValueType.BOOLEAN, "yes"));
        assertEquals(List.of(), MappingValueCodec.decode(MappingValueCodec.ValueType.OBJECT_JSON, "[]"));
    }
}
