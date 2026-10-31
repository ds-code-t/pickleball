package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.SecretRedactor;
import tools.dscode.workbench.WorkbenchServices;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP failures use the same error/fix lines as the launcher. */
class WorkbenchStructuredErrorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void blankArgumentAndUnknownToolAreErrorAndFix() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(idleServices(), JSON);
        var result = tools.specifications().stream()
                .filter(spec -> "workbench_execute_step".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .callHandler()
                .apply(null, new McpSchema.CallToolRequest("workbench_execute_step", Map.of(), null));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("error: workbench_execute_step requires text"), text);
        assertTrue(text.contains("fix: Pass a non-blank string."), text);

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> tools.call("workbench_not_a_tool", Map.of())
        );
        assertTrue(unknown.getMessage().startsWith("error: Unknown Workbench tool: workbench_not_a_tool"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("\nfix: Use tools/list."), unknown.getMessage());
    }

    @Test
    void toolResultRedactsASecretValueAndKeepsAStoredReference() throws Exception {
        WorkbenchMcpTools mcpTools = new WorkbenchMcpTools(idleServices(), JSON);
        var method = WorkbenchMcpTools.class.getDeclaredMethod("result", Object.class, boolean.class);
        method.setAccessible(true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pkb_password", "hunter2-workspace-secret");
        body.put("pkb_rp_api_key", "${protected:custom_slot}");
        body.put("note", "token hunter2-workspace-secret");
        SecretRedactor.note("pkb_password", "hunter2-workspace-secret");
        try {
            McpSchema.CallToolResult result = (McpSchema.CallToolResult) method.invoke(mcpTools, body, false);
            String text = ((McpSchema.TextContent) result.content().getFirst()).text();
            assertFalse(text.contains("hunter2-workspace-secret"), text);
            assertTrue(text.contains("<redacted>"), text);
            assertTrue(text.contains("${protected:custom_slot}"), text);
        } finally {
            SecretRedactor.clear();
        }
    }

    private static WorkbenchServices idleServices() {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
