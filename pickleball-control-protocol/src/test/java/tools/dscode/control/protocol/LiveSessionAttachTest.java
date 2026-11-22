package tools.dscode.control.protocol;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI and MCP share one attach: isolate worker, else the start host, or a session id. */
class LiveSessionAttachTest {
    @TempDir
    Path project;

    @Test
    void sessionIdSelectsStartBesideAWorkerAndAMissIsEmpty() throws Exception {
        HttpServer worker = server("worker");
        HttpServer start = server("start");
        worker.start();
        start.start();
        try {
            LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                    "127.0.0.1", worker.getAddress().getPort(), "worker-token",
                    ProcessHandle.current().pid(), "worker-session"
            ));
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
            LiveSessionAttach.Attached current = LiveSessionAttach.current(project).orElseThrow();
            assertEquals("worker", current.kind());
            assertEquals("worker-session", current.bridge().sessionId());

            LiveSessionAttach.Attached named = LiveSessionAttach.resolve(project, "start-session", true).orElseThrow();
            assertEquals("start", named.kind());
            assertEquals(start.getAddress().getPort(), named.bridge().port());

            Optional<LiveSessionAttach.Attached> loose = LiveSessionAttach.resolve(project, "engine-session", false);
            assertEquals("worker", loose.orElseThrow().kind());
            assertTrue(LiveSessionAttach.resolve(project, "missing", true).isEmpty());
        } finally {
            worker.stop(0);
            start.stop(0);
        }
    }

    @Test
    void postReachesTheSelectedHost() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer start = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        start.createContext("/v1/agent/command", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"op\":\"snap\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        start.start();
        try {
            LiveHostFile.write(project, new LiveHostFile.Host(
                    "http://127.0.0.1:" + start.getAddress().getPort(),
                    "start-token",
                    start.getAddress().getPort(),
                    ProcessHandle.current().pid(),
                    0L,
                    "start-session",
                    false,
                    "chrome",
                    true,
                    "",
                    true,
                    1L,
                    1L
            ));
            LiveSessionAttach.Attached attached = LiveSessionAttach.resolve(project, "start-session", true).orElseThrow();
            LiveSessionAttach.Posted posted = LiveSessionAttach.post(
                    attached.bridge(), "snap", java.util.Map.of("budget", "600"), java.util.List.of(),
                    project, "start-session", "agent"
            );
            assertEquals(0, posted.exit());
            assertEquals("", posted.error());
            assertTrue(posted.bodyJson().contains("\"op\":\"snap\""), posted.bodyJson());
            assertEquals("Bearer start-token", auth.get());
            assertTrue(body.get().contains("\"op\":\"snap\""), body.get());
            assertTrue(body.get().contains("\"budget\":\"600\""), body.get());
        } finally {
            start.stop(0);
        }
    }

    private static HttpServer server(String name) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/command", exchange -> {
            byte[] response = ("{\"exit\":0,\"body\":{\"ok\":true,\"who\":\"" + name + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }
}
