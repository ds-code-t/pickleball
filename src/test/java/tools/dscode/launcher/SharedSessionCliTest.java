package tools.dscode.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI page verbs attach to the same start host or isolate worker a session id names. */
class SharedSessionCliTest {
    @TempDir
    Path project;

    @Test
    void sessionFlagReachesTheStartHostWhileTheWorkerStaysTheDefault() throws Exception {
        AtomicReference<String> workerBody = new AtomicReference<>("");
        AtomicReference<String> startBody = new AtomicReference<>("");
        HttpServer worker = endpoint(workerBody);
        HttpServer start = endpoint(startBody);
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
            Capture named = run("snap", "--session=start-session", "--budget=600", project.toString());
            assertEquals(0, named.code, named.out + named.err);
            assertTrue(startBody.get().contains("\"op\":\"snap\""), startBody.get());
            assertTrue(startBody.get().contains("\"budget\":\"600\""), startBody.get());
            assertEquals("", workerBody.get());

            workerBody.set("");
            startBody.set("");
            Capture plain = run("snap", project.toString());
            assertEquals(0, plain.code, plain.out + plain.err);
            assertTrue(workerBody.get().contains("\"op\":\"snap\""), workerBody.get());
            assertEquals("", startBody.get());

            workerBody.set("");
            Capture loose = run("observe", "--session=engine-session", project.toString());
            assertEquals(0, loose.code, loose.out + loose.err);
            assertTrue(workerBody.get().contains("\"op\":\"observe\""), workerBody.get());
            assertTrue(workerBody.get().contains("engine-session"), workerBody.get());
        } finally {
            worker.stop(0);
            start.stop(0);
        }
    }

    private static HttpServer endpoint(AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent/command", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"op\":\"snap\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private static Capture run(String... args) {
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
