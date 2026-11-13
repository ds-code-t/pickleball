package dev.domengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One MCP tool. G4 and other modules register implementations with
 * {@link McpRegistry#register(McpCommand)} or {@code META-INF/services/dev.domengine.mcp.McpCommand}.
 * They do not edit {@link McpServer}.
 */
public interface McpCommand {
    String name();

    String description();

    ObjectNode inputSchema();

    /** MCP {@code tools/call} result object ({@code content}, optional {@code isError}). */
    ObjectNode call(JsonNode arguments);
}
