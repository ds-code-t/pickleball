package tools.dscode.workbench.mcp.popup;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.mcp.McpCommandContributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupMcpCommandsTest {
    @Test
    void threeCommandsForwardTheOpAndArgs() {
        List<String> names = new ArrayList<>();
        new PopupMcpCommands().contribute(new McpCommandContributor.Sink() {
            @Override
            public void register(String name, String description, Map<String, Object> schema, java.util.function.Function<Map<String, Object>, Object> handler) {
                names.add(name);
                @SuppressWarnings("unchecked")
                Map<String, Object> schemaProperties = (Map<String, Object>) schema.get("properties");
                assertTrue(schemaProperties.containsKey("target"));
                assertTrue(schemaProperties.containsKey("pane"));
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) handler.apply(Map.of("runId", "r1", "target", "popup"));
                assertEquals(name.replace("agent_", "").replace('_', '-'), body.get("op"));
                assertEquals("r1", ((Map<?, ?>) body.get("args")).get("runId"));
                assertEquals("popup", ((Map<?, ?>) body.get("args")).get("target"));
            }
        });
        assertEquals(List.of("agent_popup_open", "agent_popup_status", "agent_popup_close"), names);
    }
}
