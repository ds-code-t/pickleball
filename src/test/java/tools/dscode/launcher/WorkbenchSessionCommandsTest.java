package tools.dscode.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlProtocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSessionCommandsTest {
    @TempDir
    Path project;

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void isolateDoesNotBlockOnStdinWhenSessionAlreadyHealthy() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicBoolean started = new AtomicBoolean();
            Output output = run(
                    new String[]{"isolate", project.toString()},
                    (proj, tags, name, log) -> {
                        started.set(true);
                        throw new AssertionError("should not start a second session");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK SESSION already-running"));
            assertFalse(started.get());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sessionStartDetachesAndDoesNotWaitForTheControllerProcess() throws Exception {
        AtomicReference<FakeSession> started = new AtomicReference<>();
        AtomicReference<Process> child = new AtomicReference<>();
        try {
            Output output = run(
                    new String[]{"session-start", project.toString()},
                    (proj, tags, name, log) -> {
                        FakeSession session = FakeSession.start(proj);
                        started.set(session);
                        Process process = new ProcessBuilder("sleep", "30").start();
                        child.set(process);
                        return process;
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK SESSION pid="));
            assertFalse(output.stdout.contains("Workbench isolate worker:"));
        } finally {
            Process process = child.get();
            if (process != null) process.destroyForcibly();
            FakeSession session = started.get();
            if (session != null) session.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void executeStepAckOnlyExitsWithoutWaitingForDone() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            Output output = run(
                    new String[]{"execute-step", project.toString(), "--text=Given stay", "--ack-only"},
                    (proj, tags, name, log) -> {
                        throw new AssertionError("execute-step must not start a session process");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK "));
            assertFalse(output.stdout.contains("DONE "));
        }
    }

    private static Output run(String[] args, WorkbenchSessionCommands.DetachedStarter starter) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                starter
        );
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }

    private static final class FakeSession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private Process child;

        private FakeSession(HttpServer http, Path stateFile) {
            this.http = http;
            this.stateFile = stateFile;
        }

        static FakeSession start(Path project) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
            http.createContext("/commands", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().readAllBytes();
                    write(exchange, 200, "{\"ack\":true,\"id\":\"step-held\",\"status\":\"QUEUED\"}");
                    return;
                }
                write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"STILL_WORKING\"}");
            });
            http.start();
            Path stateFile = project.resolve(ControlProtocol.CLI_SESSION_STATE_RELATIVE);
            Files.createDirectories(stateFile.getParent());
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            Files.writeString(stateFile, """
                    {
                      "url": "%s",
                      "token": "test-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "cli-session"
                    }
                    """.formatted(url, ProcessHandle.current().pid(), jsonEscape(project.toString())));
            return new FakeSession(http, stateFile);
        }

        @Override
        public void close() {
            http.stop(0);
            if (child != null) child.destroyForcibly();
            try {
                Files.deleteIfExists(stateFile);
            } catch (IOException ignored) {
            }
        }

        private static String jsonEscape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
