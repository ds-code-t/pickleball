package tools.dscode.common.agentfast;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.launcher.WorkbenchAgentCommands;
import tools.dscode.launcher.WorkbenchSessionCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingFieldsTest {
    @TempDir
    Path temp;

    @Test
    void freshCliWarmVerbAndExecuteStepCarryElapsedMs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger posts = new AtomicInteger();
        server.createContext("/v1/agent/command", exchange -> {
            posts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"timing\":{\"totalMs\":3,\"jvmMs\":1}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands", exchange -> {
            byte[] response = "{\"id\":\"c1\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands/c1", exchange -> {
            String text = "s".repeat(3000);
            byte[] response = ("{\"status\":\"SUCCESS\",\"result\":{\"text\":\"" + text + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            LiveBridgeFile.write(temp, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "token-warm", ProcessHandle.current().pid(), "sess-warm"
            ));
            long started = System.nanoTime();
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "tools.dscode.launcher.PickleballWorkbenchLauncher",
                    "highlight",
                    temp.toString(),
                    "--handle=save",
                    "--preset=yellow"
            ).redirectErrorStream(true).directory(temp.toFile()).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            long warmMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            System.out.println("MEASURE warmMs=" + warmMs);
            assertEquals(0, code, output);
            assertTrue(posts.get() >= 1, output);
            assertTrue(output.contains("elapsedMs"), output);
            assertTrue(warmMs <= 1000, "warmMs=" + warmMs);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int catalog = WorkbenchAgentCommands.run(
                    new String[]{"ui-catalog", temp.toString()},
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            String catalogOut = stdout.toString(StandardCharsets.UTF_8);
            assertEquals(0, catalog, catalogOut + stderr);
            assertTrue(catalogOut.contains("\"elapsedMs\""), catalogOut);

            LiveBridgeFile.delete(temp);
            Path session = PickleballLocalLayout.cliSessionState(temp);
            Files.createDirectories(session.getParent());
            Files.writeString(session,
                    "{\"url\":\"http://127.0.0.1:" + port + "\",\"token\":\"token-warm\",\"pid\":"
                            + ProcessHandle.current().pid() + ",\"mode\":\"cli-session\"}\n");
            Path stepFile = temp.resolve("step.txt");
            Files.writeString(stepFile, "Then the \"Save\" button is visible\n");
            ByteArrayOutputStream stepOut = new ByteArrayOutputStream();
            ByteArrayOutputStream stepErr = new ByteArrayOutputStream();
            int step = WorkbenchSessionCommands.run(
                    new String[]{"execute-step", "--text-file=" + stepFile, temp.toString()},
                    new PrintStream(stepOut, true, StandardCharsets.UTF_8),
                    new PrintStream(stepErr, true, StandardCharsets.UTF_8)
            );
            String stepText = stepOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, step, stepText + stepErr);
            assertTrue(stepText.contains("elapsedMs"), stepText);
            String log = Files.readString(temp.resolve(".pickleball/logs/step-timing.jsonl"));
            assertTrue(log.contains("\"id\":\"c1\""), log);
            assertTrue(log.contains("\"elapsedMs\""), log);
            assertTrue(log.contains("\"at\":"), log);
        } finally {
            server.stop(0);
        }
    }
}
