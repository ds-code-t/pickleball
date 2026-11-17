package tools.dscode.workbench.mcp.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.mcp.McpCommandContributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchUiToolsTest {
    @Test
    @SuppressWarnings("unchecked")
    void uiToolsListedWithSchemasAndForwardToTheSpine() {
        List<String> names = new ArrayList<>();
        new WorkbenchUiTools().contribute((name, description, schema, handler) -> {
            names.add(name);
            assertEquals("object", schema.get("type"));
            assertTrue(description.length() > 40, name);
            if (name.equals("ui")) {
                Map<String, Object> ops = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("ops");
                assertEquals("array", ops.get("type"));
                Map<String, Object> body = (Map<String, Object>) handler.apply(Map.of(
                        "ops", List.of(Map.of("c", "label", "t", "#a", "text", "A")), "runId", "r1"));
                assertEquals("ui-apply", body.get("op"));
                Map<String, String> args = (Map<String, String>) body.get("args");
                assertEquals("[{\"c\":\"label\",\"t\":\"#a\",\"text\":\"A\"}]".length(), args.get("ops").length());
                assertTrue(args.get("ops").contains("\"c\":\"label\""), args.get("ops"));
                assertEquals("r1", args.get("run-id"));
            }
            if (name.equals("ui_poll")) {
                Map<String, Object> body = (Map<String, Object>) handler.apply(Map.of("chatAfter", "m3", "wait", 5));
                assertEquals("ui-poll", body.get("op"));
                assertEquals("m3", ((Map<String, String>) body.get("args")).get("chat-after"));
            }
        });
        assertEquals(List.of("ui", "ui_poll", "ui_catalog"), names);
    }

    @Test
    void registeredByTheServiceFile() {
        boolean found = false;
        for (McpCommandContributor contributor : ServiceLoader.load(McpCommandContributor.class)) {
            if (contributor instanceof WorkbenchUiTools) found = true;
        }
        assertTrue(found, "WorkbenchUiTools is not listed in META-INF/services");
    }
}
