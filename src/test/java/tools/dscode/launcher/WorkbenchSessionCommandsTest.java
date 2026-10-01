package tools.dscode.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSessionCommandsTest {
    @TempDir
    Path project;

    @AfterEach
    void clearCoordination() {
        AgentCoordination.clearCurrent();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void isolateDoesNotBlockOnStdinWhenSessionAlreadyHealthy() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicBoolean started = new AtomicBoolean();
            Output output = run(
                    new String[]{"isolate", project.toString()},
                    launch -> {
                        started.set(true);
                        throw new AssertionError("should not start a second session");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK SESSION already-running"));
            assertTrue(output.stdout.contains("Only one agent drives an open Workbench window"));
            assertTrue(output.stdout.contains("stays headless"));
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
                    new String[]{"session-start", project.toString(), "--example=1", "2", "5"},
                    launch -> {
                        assertEquals("1 2 5", launch.example());
                        FakeSession session = FakeSession.start(launch.project(), "cli-session", launch.sessionFile());
                        started.set(session);
                        Process process = startLongLivedChild();
                        child.set(process);
                        return process;
                    }
            );
            assertEquals(0, output.exitCode, () -> output.stderr + output.stdout);
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
    void watchedWindowIsPreferredAndStopDoesNotKillIt() throws Exception {
        try (FakeSession cli = FakeSession.start(project);
             FakeSession ui = FakeSession.start(project, "ui-attach", PickleballLocalLayout.attachFile(project))) {
            AtomicReference<String> hit = new AtomicReference<>();
            ui.onCommand = body -> hit.set(body);
            Output play = run(
                    new String[]{"play", project.toString(), "--ack-only"},
                    launch -> {
                        throw new AssertionError("play must not start a session");
                    }
            );
            assertEquals(0, play.exitCode, play.stderr + play.stdout);
            assertTrue(play.stdout.contains("ACK "));
            assertTrue(hit.get() != null && hit.get().contains("\"op\":\"play\""));
            assertTrue(hit.get().contains(ui.url));

            Process child = startLongLivedChild();
            ui.setPid(child.pid());
            Output stop = run(
                    new String[]{"stop", project.toString()},
                    launch -> {
                        throw new AssertionError("stop must not start a session");
                    }
            );
            assertEquals(0, stop.exitCode, stop.stderr + stop.stdout);
            assertTrue(stop.stdout.contains("playback-stopped"));
            assertTrue(child.isAlive());
            child.destroyForcibly();
            assertTrue(cli.pidAlive());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void openScenarioPostsTheSharedCommandAndDoesNotStartASession() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicReference<String> hit = new AtomicReference<>();
            ignored.onCommand = hit::set;
            Output output = run(
                    new String[]{
                            "open-scenario", project.toString(),
                            "--feature=shop.feature", "--name=Buy", "--example=2.2", "--ack-only"
                    },
                    launch -> {
                        throw new AssertionError("open-scenario must not start a session");
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stdout.contains("ACK "));
            assertTrue(hit.get().contains("\"op\":\"open-scenario\""));
            assertTrue(hit.get().contains("shop.feature"));
            assertTrue(hit.get().contains("2.2"));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void eachSessionCommandPostsToTheOpenSessionAndDoesNotStartOne() throws Exception {
        String[][] commands = {
                {"example", "--example=2.2"},
                {"play"},
                {"from-here", "--from-here=4"},
                {"pause"},
                {"insert-step", "--text=When stay"},
                {"update-step", "--text=When stay"},
                {"diagnostic-run", "--run=run-42"},
                {"save"},
                {"refresh"},
                {"session-sync"},
                {"worker-start"},
                {"worker-restart"},
                {"worker-stop"}
        };
        try (FakeSession session = FakeSession.start(project)) {
            for (String[] command : commands) {
                AtomicReference<String> hit = new AtomicReference<>();
                session.onCommand = hit::set;
                List<String> args = new ArrayList<>();
                args.add(command[0]);
                args.add(project.toString());
                for (int index = 1; index < command.length; index++) args.add(command[index]);
                args.add("--ack-only");
                Output output = run(
                        args.toArray(String[]::new),
                        launch -> {
                            throw new AssertionError(command[0] + " must not start a session");
                        }
                );
                assertEquals(0, output.exitCode, command[0] + " " + output.stderr + output.stdout);
                assertTrue(output.stdout.contains("ACK "), command[0]);
                assertTrue(hit.get() != null && hit.get().contains("\"op\":\"" + command[0] + "\""), hit.get());
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void executeStepAckOnlyExitsWithoutWaitingForDone() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            Output output = run(
                    new String[]{"execute-step", project.toString(), "--text=Given stay", "--ack-only"},
                    launch -> {
                        throw new AssertionError("execute-step must not start a session process");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK "));
            assertFalse(output.stdout.contains("DONE "));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoHeadlessIsolatesDoNotShareASessionFile() throws Exception {
        List<Path> files = new ArrayList<>();
        List<FakeSession> sessions = new ArrayList<>();
        List<Process> children = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                Output output = run(
                        new String[]{"isolate", project.toString(), "--run-id=run-" + index, "--agent=agent-" + index},
                        launch -> {
                            files.add(launch.sessionFile());
                            assertTrue(launch.sessionFile().toString().contains("run-" + (files.size() - 1)));
                            FakeSession session = FakeSession.start(
                                    launch.project(), "cli-session", launch.sessionFile()
                            );
                            sessions.add(session);
                            Process process = startLongLivedChild();
                            children.add(process);
                            return process;
                        }
                );
                assertEquals(0, output.exitCode, output.stderr + output.stdout);
                assertTrue(output.stdout.contains("ACK SESSION pid="));
                assertFalse(output.stdout.contains("already-running"));
            }
            assertEquals(2, files.size());
            assertFalse(files.get(0).equals(files.get(1)));
        } finally {
            for (Process process : children) process.destroyForcibly();
            for (FakeSession session : sessions) session.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void runIdDoesNotFallThroughToAnOpenWindow() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicBoolean started = new AtomicBoolean();
            Output output = run(
                    new String[]{"play", project.toString(), "--run-id=run-private", "--ack-only"},
                    launch -> {
                        started.set(true);
                        throw new AssertionError("a named run must not start or drive the open window");
                    }
            );
            assertEquals(1, output.exitCode, output.stderr + output.stdout);
            assertFalse(started.get());
            assertTrue(output.stderr.contains("No healthy Workbench session"));
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

    /**
     * Placeholder controller process. Unix {@code sleep} is not on Windows PATH;
     * session-start must return without waiting for this child to exit.
     */
    private static Process startLongLivedChild() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", "ping", "-n", "31", "127.0.0.1").start();
        }
        return new ProcessBuilder("sleep", "30").start();
    }

    private static final class FakeSession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private final String url;
        private Process child;
        private volatile java.util.function.Consumer<String> onCommand;
        private volatile long pid = ProcessHandle.current().pid();

        private FakeSession(HttpServer http, Path stateFile, String url) {
            this.http = http;
            this.stateFile = stateFile;
            this.url = url;
        }

        static FakeSession start(Path project) throws IOException {
            return start(project, "cli-session", PickleballLocalLayout.cliSessionState(project));
        }

        static FakeSession start(Path project, String mode, Path stateFile) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeSession[] box = new FakeSession[1];
            http.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
            http.createContext("/commands", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                FakeSession session = box[0];
                if (session != null && session.onCommand != null) {
                    session.onCommand.accept(body + " " + session.url);
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    write(exchange, 200, "{\"ack\":true,\"id\":\"step-held\",\"status\":\"QUEUED\"}");
                    return;
                }
                write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"STILL_WORKING\"}");
            });
            http.start();
            Files.createDirectories(stateFile.getParent());
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            FakeSession session = new FakeSession(http, stateFile, url);
            box[0] = session;
            session.writeState(project, mode);
            return session;
        }

        void setPid(long pid) {
            this.pid = pid;
            try {
                String text = Files.readString(stateFile).replaceAll("\"pid\"\\s*:\\s*\\d+", "\"pid\": " + pid);
                Files.writeString(stateFile, text);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        boolean pidAlive() {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        private void writeState(Path project, String mode) throws IOException {
            Files.writeString(stateFile, """
                    {
                      "url": "%s",
                      "token": "test-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "%s"
                    }
                    """.formatted(url, pid, jsonEscape(project.toString()), mode));
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
