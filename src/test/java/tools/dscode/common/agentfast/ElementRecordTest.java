package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A find hit and a desktop hit share one element object. */
class ElementRecordTest {
    @Test
    void findHitAndDesktopHitShareKeys() {
        Map<String, Object> find = new LinkedHashMap<>();
        find.put("id", "s1.h1");
        find.put("role", "button");
        find.put("name", "Save");
        find.put("tag", "button");
        find.put("x", 1);
        find.put("y", 2);
        find.put("width", 3);
        find.put("height", 4);
        find.put("backendNodeId", 9);
        find.put("frame", "top");
        find.put("identity", Map.of("chain", List.of("css=#save")));
        find.put("widget", "secret-widget");

        Map<String, Object> desktop = new LinkedHashMap<>();
        desktop.put("handle", "s1.h1");
        desktop.put("role", "button");
        desktop.put("name", "Save");
        desktop.put("node_name", "button");
        desktop.put("bounds", Map.of("x", 1, "y", 2, "width", 3, "height", 4));
        desktop.put("backend_node_id", 9);
        desktop.put("frame_id", "top");
        desktop.put("chain", List.of("css=#save"));
        desktop.put("widget", "secret-widget");

        Map<String, Object> fromFind = ElementRecord.from(find, null);
        Map<String, Object> fromDesktop = ElementRecord.from(desktop, null);
        assertEquals(ElementRecord.KEYS, List.copyOf(fromFind.keySet()));
        assertEquals(List.copyOf(fromFind.keySet()), List.copyOf(fromDesktop.keySet()));
        assertEquals("s1.h1", fromFind.get("handle"));
        assertEquals("s1.h1", fromDesktop.get("handle"));
        assertEquals(List.of("css=#save"), ((Map<?, ?>) fromFind.get("identity")).get("chain"));
        assertEquals(List.of("css=#save"), ((Map<?, ?>) fromDesktop.get("identity")).get("chain"));
        assertEquals("button", fromFind.get("role"));
        assertEquals("Save", fromFind.get("name"));
        assertEquals("button", fromFind.get("tag"));
        assertEquals("button", fromDesktop.get("tag"));
        assertEquals(9L, ((Number) fromFind.get("backendNodeId")).longValue());
        assertEquals(9L, ((Number) fromDesktop.get("backendNodeId")).longValue());
        assertEquals("top", fromFind.get("frame"));
        assertEquals(Map.of("x", 1, "y", 2, "width", 3, "height", 4), fromFind.get("bounds"));
        assertFalse(fromFind.containsKey("extra"));
        assertFalse(fromFind.containsKey("widget"));
        assertFalse(fromDesktop.containsKey("widget"));

        Map<String, Object> withField = ElementRecord.from(find, "widget");
        assertEquals("secret-widget", ((Map<?, ?>) withField.get("extra")).get("widget"));
        assertFalse(withField.containsKey("widget"));
        assertNull(ElementRecord.from(Map.of("widget", "secret-widget"), null).get("extra"));
    }

    @Test
    void presentRowFieldsStayOnTheRecord() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "s1.h2");
        row.put("role", "tab");
        row.put("name", "Chat");
        row.put("tag", "button");
        row.put("ref", "ref:d1:10");
        row.put("visible", false);
        row.put("enabled", true);
        row.put("interactive", true);
        row.put("via", "coverage");
        row.put("purpose", "panel,chat-tab");
        row.put("highlight", "yellow");
        row.put("shadow", "closed");
        row.put("shadow_path", "/html[1]/body[1]/div[1]");
        row.put("widget", "secret-widget");
        row.put("msg", null);

        Map<String, Object> record = ElementRecord.from(row, "widget,purpose");
        assertEquals("s1.h2", record.get("handle"));
        assertEquals("ref:d1:10", record.get("ref"));
        assertEquals(Boolean.FALSE, record.get("visible"));
        assertEquals(Boolean.TRUE, record.get("enabled"));
        assertEquals(Boolean.TRUE, record.get("interactive"));
        assertEquals("coverage", record.get("via"));
        assertEquals("panel,chat-tab", record.get("purpose"));
        assertEquals("yellow", record.get("highlight"));
        assertEquals("closed", record.get("shadow"));
        assertEquals("/html[1]/body[1]/div[1]", record.get("shadow_path"));
        assertFalse(record.containsKey("id"));
        assertFalse(record.containsKey("msg"));
        assertFalse(record.containsKey("checked"));
        assertFalse(record.containsKey("widget"));
        Map<?, ?> extra = (Map<?, ?>) record.get("extra");
        assertEquals("secret-widget", extra.get("widget"));
        assertFalse(extra.containsKey("purpose"));
        assertFalse(extra.containsKey("visible"));
        assertFalse(extra.containsKey("via"));
        assertFalse(extra.containsKey("shadow"));
        assertFalse(extra.containsKey("shadow_path"));
    }

    @Test
    void desktopQueryDropsExtraUnlessFieldsNamesIt() {
        String json = """
                {"ok":true,"nodes":[{"role":"button","name":"Save","widget":"secret-widget","x":1,"y":2,"width":3,"height":4}],"composite":[]}
                """;
        String plain = ElementRecord.rewriteDesktopQuery(json, null);
        assertFalse(plain.contains("widget"), plain);
        assertFalse(plain.contains("secret-widget"), plain);
        String named = ElementRecord.rewriteDesktopQuery(json, "widget");
        assertTrueContains(named, "secret-widget");
        assertEquals("not-json", ElementRecord.rewriteDesktopQuery("not-json", null));
    }

    private static void assertTrueContains(String text, String token) {
        org.junit.jupiter.api.Assertions.assertTrue(text.contains(token), text);
    }
}
