package dev.domengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRegistryTest {
    @AfterEach
    void clear() {
        McpRegistry.resetForTests();
    }

    @Test
    void theCatalogueIsThePublicAgentEntry() {
        List<String> names = McpRegistry.names();
        assertEquals(List.of("domengine", "query", "click", "type", "snapshot", "observe", "launch", "surfaces"), names);
        assertTrue(SnapshotDispatch.producersPresent());
    }

    @Test
    void snapshotReturnsTheCompositeSnapshot() throws Exception {
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"snapshot","arguments":{"kind":"offline"}}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"launch","arguments":{}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"surfaces","arguments":{}}}
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"domengine","arguments":{"argv":["version"]}}}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"name\":\"snapshot\""), text);
        assertTrue(text.contains("\"name\":\"query\""), text);
        JsonNode snapshot = content(text, "2");
        assertEquals("pickleball.composite-snapshot", snapshot.path("schema").asText());
        assertEquals(1, snapshot.path("version").asInt());
        assertFalse(snapshot.path("warnings").toString().contains("g3-producers-absent"), snapshot.toString());
        assertTrue(snapshot.has("premap"));
        assertTrue(snapshot.has("nodes"));
        assertTrue(text.contains("fix: Call the workbench MCP launch command"), text);
        assertTrue(text.contains("html-write"), text);
        assertTrue(text.contains("3.0.0"), text);
    }

    @Test
    void browserAndDesktopReturnTheProducerError() throws Exception {
        assertProducerError("browser", "CompositeSnapshots.browser failed", "browser snapshot needs a page session");
        assertProducerError("desktop", "CompositeSnapshots.desktop failed", "session not found");
    }

    private static void assertProducerError(String kind, String failed, String detail) throws Exception {
        String request = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"snapshot","arguments":{"kind":"%s"}}}
                """.formatted(kind);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        String framed = out.toString(StandardCharsets.UTF_8);
        JsonNode result = null;
        for (byte[] frame : McpServer.frames(framed.getBytes(StandardCharsets.UTF_8))) {
            JsonNode node = Jsons.MAPPER.readTree(frame);
            if ("1".equals(node.path("id").asText())) result = node.path("result");
        }
        assertTrue(result != null && result.path("isError").asBoolean(), framed);
        String text = result.path("content").path(0).path("text").asText();
        assertTrue(text.contains(failed), text);
        assertTrue(text.contains(detail), text);
    }

    @Test
    void anExtraCommandRegistersWithoutEditingTheServer() throws Exception {
        McpRegistry.register(new McpCommand() {
            @Override
            public String name() {
                return "popup-chat";
            }

            @Override
            public String description() {
                return "registered by another group";
            }

            @Override
            public ObjectNode inputSchema() {
                return McpRegistry.schema();
            }

            @Override
            public ObjectNode call(JsonNode arguments) {
                return McpRegistry.tool("{\"ok\":true}", false);
            }
        });
        assertTrue(McpRegistry.names().contains("popup-chat"));
        assertEquals("domengine", McpRegistry.names().get(0));
        String request = """
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"popup-chat","arguments":{}}}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        JsonNode body = content(out.toString(StandardCharsets.UTF_8), "9");
        assertEquals(true, body.path("ok").asBoolean());
    }

    private static JsonNode content(String framed, String id) throws Exception {
        for (byte[] frame : McpServer.frames(framed.getBytes(StandardCharsets.UTF_8))) {
            JsonNode node = Jsons.MAPPER.readTree(frame);
            if (!id.equals(node.path("id").asText())) continue;
            String body = node.path("result").path("content").path(0).path("text").asText();
            return Jsons.MAPPER.readTree(body);
        }
        throw new AssertionError("missing id " + id + " in " + framed);
    }
}
