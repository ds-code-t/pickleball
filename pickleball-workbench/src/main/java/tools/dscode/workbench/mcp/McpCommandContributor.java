package tools.dscode.workbench.mcp;

import java.util.Map;
import java.util.function.Function;

/**
 * G4 registers popup commands by listing an implementation in
 * {@code META-INF/services/tools.dscode.workbench.mcp.McpCommandContributor}.
 * The contributor must not edit {@link WorkbenchMcpServer} or {@link WorkbenchMcpTools}.
 */
public interface McpCommandContributor {
    void contribute(Sink sink);

    interface Sink {
        void register(String name, String description, Map<String, Object> schema, Function<Map<String, Object>, Object> handler);
    }
}
