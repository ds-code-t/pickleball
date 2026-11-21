package tools.dscode.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ClientHeartbeat;
import tools.dscode.control.protocol.LiveBridgeFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI page verbs POST to the live worker and fail fast when that bridge is gone. */
class CliForwardToWorkerTest {
    @TempDir
    Path project;

    @Test
    void panelOpenPostsToTheLiveWorkerThenFailsFast() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/command", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"forwarded\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            assertTrue(port > 0 && port != 8765, "port " + port);
            LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "token-test", ProcessHandle.current().pid(), "sess-forward"
            ));
            Capture forwarded = agent("panel-open", project.toString(), "--run-id=fwd", "--target=page");
            assertEquals(0, forwarded.code, forwarded.out + forwarded.err);
            assertEquals("/v1/agent/command", path.get());
            assertEquals("Bearer token-test", auth.get());
            assertTrue(body.get().contains("\"op\":\"panel-open\""), body.get());
            assertTrue(forwarded.out.contains("\"forwarded\":true"), forwarded.out);
            assertTrue(ClientHeartbeat.lastMillis(project) > 0, "CLI post touches the heartbeat");

            LiveBridgeFile.delete(project);
            Capture missing = agent("panel-open", project.toString(), "--run-id=fwd", "--target=auto");
            assertEquals(1, missing.code, missing.out + missing.err);
            assertTrue((missing.out + missing.err).contains("error: no live browser"), missing.out + missing.err);
            assertFalse((missing.out + missing.err).contains("forwarded"), missing.out + missing.err);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pageVerbsPostThenFailFastAndKeepTheWorkerClock() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/agent/command", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"timing\":{\"totalMs\":4,\"jvmMs\":2}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "token-page", ProcessHandle.current().pid(), "sess-page"
            ));
            for (String[] args : new String[][]{
                    {"highlight", project.toString(), "--handle=save", "--preset=yellow"},
                    {"screenshot", project.toString(), "--out=shot.png"},
                    {"observe", project.toString()},
                    {"overlay", project.toString(), "--text=Save"}
            }) {
                Capture forwarded = agent(args);
                assertEquals(0, forwarded.code, args[0] + " " + forwarded.out + forwarded.err);
                assertTrue(body.get().contains("\"op\":\"" + args[0] + "\""), body.get());
                assertTrue(forwarded.out.contains("\"workerMs\":4"), forwarded.out);
                assertTrue(forwarded.out.contains("\"elapsedMs\""), forwarded.out);
                assertTrue(forwarded.out.contains("\"jvmMs\":2"), forwarded.out);
            }
            LiveBridgeFile.delete(project);
            Capture missing = agent("highlight", project.toString(), "--handle=save", "--preset=yellow");
            assertEquals(1, missing.code, missing.out + missing.err);
            assertTrue((missing.out + missing.err).contains("error: no live browser"), missing.out + missing.err);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executeStepsPostsPageVerbsAndKeepsDoctorLocal() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/command", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            String frame = request.contains("\"op\":\"frame\"") ? "child" : "top";
            byte[] response = ("{\"exit\":0,\"body\":{\"ok\":true,\"frame\":\"" + frame + "\",\"forwarded\":true}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "token-steps", ProcessHandle.current().pid(), "sess-steps"
            ));
            Path file = project.resolve("steps.json");
            Files.writeString(file, """
                    [
                      {"op":"snap","args":{}},
                      {"op":"click","args":{"ref":"s1.h1"}},
                      {"op":"doctor","args":{}},
                      {"op":"frame","args":{"list":"true"}}
                    ]
                    """);
            Capture ran = agent("execute-steps", "--file=" + file, "--run-id=routed", project.toString());
            assertEquals(0, ran.code, ran.out + ran.err);
            assertEquals(3, bodies.size(), bodies.toString());
            assertTrue(bodies.get(0).contains("\"op\":\"snap\""), bodies.get(0));
            assertTrue(bodies.get(1).contains("\"op\":\"act\""), bodies.get(1));
            assertTrue(bodies.get(1).contains("\"kind\":\"click\""), bodies.get(1));
            assertTrue(bodies.get(2).contains("\"op\":\"frame\""), bodies.get(2));
            assertFalse(bodies.toString().contains("\"op\":\"doctor\""), bodies.toString());
            assertTrue(ran.out.contains("\"browser\":\"up\""), ran.out);
            assertTrue(ran.out.contains("\"frame\":\"child\""), ran.out);
            assertTrue(ran.out.contains("chrome="), ran.out);
            assertTrue(ran.out.contains("frame="), ran.out);
            assertTrue(ran.out.contains("steps ok"), ran.out);
            assertFalse(ran.out.contains("no live browser"), ran.out);
            assertFalse(ran.out.contains("unknown agent command"), ran.out);
        } finally {
            server.stop(0);
            LiveBridgeFile.delete(project);
        }
    }

    private Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
