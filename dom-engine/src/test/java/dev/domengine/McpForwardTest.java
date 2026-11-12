package dev.domengine;

import dev.domengine.mcp.McpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpForwardTest {
    @Test
    void toolsCallForwardsToTheCli() {
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"domengine","arguments":{"argv":["version"]}}}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"name\":\"domengine\""), text);
        assertTrue(text.contains("3.0.0"), text);
        assertTrue(text.contains("Content-Length:"), text);
    }
}
