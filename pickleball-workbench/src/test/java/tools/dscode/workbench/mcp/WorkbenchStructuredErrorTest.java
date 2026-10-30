package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.dscode.workbench.WorkbenchServices;

import java.lang.reflect.Proxy;
import java.util.Map;

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
