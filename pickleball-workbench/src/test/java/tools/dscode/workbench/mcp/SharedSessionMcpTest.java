package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;
import tools.dscode.workbench.WorkbenchController;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP page tools drive the start host by session id and do not need a paused worker. */
class SharedSessionMcpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void snapAndActShareTheStartSessionAndRejectABadBudget() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer start = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        start.createContext("/v1/agent/command", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"op\":\"snap\",\"items\":[{\"id\":\"s1.h1\"}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        start.start();
        WorkbenchController controller = new WorkbenchController(project);
        try {
            LiveHostFile.write(project, new LiveHostFile.Host(
                    "http://127.0.0.1:" + start.getAddress().getPort(),
                    "start-token",
                    start.getAddress().getPort(),
                    ProcessHandle.current().pid(),
                    0L,
                    "start-session",
                    true,
                    "chrome",
                    true,
                    "",
                    true,
                    1L,
                    1L
            ));
            WorkbenchMcpTools tools = new WorkbenchMcpTools(controller, JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) tools.call("workbench_snap", Map.of(
                    "session", "start-session",
                    "budget", 600
            ));
            assertEquals(Boolean.TRUE, snap.get("ok"));
            assertFalse(String.valueOf(snap).contains("paused interactive worker"), String.valueOf(snap));
            assertTrue(body.get().contains("\"op\":\"snap\""), body.get());
            assertTrue(body.get().contains("\"budget\":\"600\""), body.get());
            assertTrue(body.get().contains("\"runId\":\"start-session\""), body.get());
            assertFalse(body.get().contains("\"session\""), body.get());

            body.set("");
            tools.call("workbench_act", Map.of("sessionId", "start-session", "kind", "click", "ref", "s1.h1"));
            assertTrue(body.get().contains("\"op\":\"act\""), body.get());
            assertTrue(body.get().contains("\"ref\":\"s1.h1\""), body.get());

            var rejected = tools.specifications().stream()
                    .filter(spec -> "workbench_snap".equals(spec.tool().name()))
                    .findFirst()
                    .orElseThrow()
                    .callHandler()
                    .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                            "workbench_snap",
                            Map.of("budget", "nope", "session", "start-session"),
                            null
                    ));
            assertTrue(Boolean.TRUE.equals(rejected.isError()));
            String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) rejected.content().getFirst()).text();
            assertTrue(text.contains("budget must be a whole number"), text);
            assertTrue(text.contains("\"fix\""), text);
            assertFalse(text.contains("paused interactive worker"), text);

            @SuppressWarnings("unchecked")
            Map<String, Object> missing = (Map<String, Object>) tools.call("workbench_find", Map.of("session", "no-such"));
            assertEquals(Boolean.FALSE, missing.get("ok"));
            assertTrue(String.valueOf(missing.get("error")).contains("unknown session"), String.valueOf(missing));
        } finally {
            controller.close();
            start.stop(0);
        }
    }
}
