package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiBatchTest {
    @Test
    void parsesAndMapsShortOpsToVerbFlags() throws Exception {
        List<Map<String, Object>> ops = UiBatch.parse(null, "[{\"c\":\"label\",\"t\":\"#email\",\"text\":\"Email\"},"
                + "{\"c\":\"ask\",\"say\":\"Pro?\",\"choices\":[\"Yes\",\"No\"]},"
                + "{\"c\":\"panel\",\"w\":400,\"min\":true},"
                + "{\"c\":\"guide\",\"steps\":[{\"t\":\"#a\",\"say\":\"Click A\",\"do\":\"click\"}]}]");
        assertEquals(4, ops.size());
        Map<String, String> label = UiBatch.flags(ops.get(0), "h1");
        assertEquals("Email", label.get("text"));
        assertEquals("h1", label.get("handle"));
        assertEquals("page", label.get("target"));
        assertEquals("[\"Yes\",\"No\"]", UiBatch.flags(ops.get(1), null).get("choices"));
        assertEquals(List.of("Yes", "No"), AgentControl.choiceList(UiBatch.flags(ops.get(1), null).get("choices")));
        Map<String, String> panel = UiBatch.flags(ops.get(2), null);
        assertEquals("400", panel.get("width"));
        assertEquals("true", panel.get("minimized"));
        assertTrue(UiBatch.flags(ops.get(3), null).get("steps").contains("\"say\":\"Click A\""));
        assertEquals(1, UiBatch.parse(null, "{\"c\":\"clear\"}").size(), "a single op object is a batch of one");
        Map<String, String> blocked = UiBatch.flags(UiBatch.parse(null, "{\"c\":\"overlay\",\"t\":\"#save\",\"text\":\"X\",\"block\":true}").get(0), "save");
        assertEquals("true", blocked.get("block"));
    }

    @Test
    void rejectsUnknownComponentsOptionsAndMissingFields() throws Exception {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"not-a-widget\"}]"));
        assertTrue(unknown.getMessage().contains("unknown component not-a-widget"), unknown.getMessage());
        IllegalArgumentException toast = assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"toast\"}]"));
        assertTrue(toast.getMessage().contains("toast needs text"), toast.getMessage());
        IllegalArgumentException option = assertThrows(IllegalArgumentException.class,
                () -> UiBatch.parse(null, "[{\"c\":\"label\",\"t\":\"#a\",\"text\":\"x\",\"colour\":\"red\"}]"));
        assertTrue(option.getMessage().contains("has no option colour"), option.getMessage());
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"overlay\",\"t\":\"#a\"}]"));
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"label\",\"text\":\"no target\"}]"));
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"panel\",\"t\":\"#a\"}]"));
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[{\"c\":\"guide\",\"steps\":[{\"t\":\"#a\"}]}]"));
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "[]"));
        assertThrows(IllegalArgumentException.class, () -> UiBatch.parse(null, "not json"));
        assertFalse(UiBatch.parse(null, "[{\"c\":\"chat\",\"text\":\"hi\"}]").isEmpty());
    }
}
