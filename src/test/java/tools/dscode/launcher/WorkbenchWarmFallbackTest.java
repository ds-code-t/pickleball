package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.speed.WarmProcess;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 10-minute warm-JVM socket timeout must not be treated as a missed start.
 * A stub speaks the protocol with a short handshake so the suite does not wait.
 */
class WorkbenchWarmFallbackTest {
    @TempDir
    Path temp;

    @Test
    void delayedExitWithoutStartedIsAMissAndWouldRelaunch() throws Exception {
        Attempt attempt = talk(temp.resolve("miss"), Mode.DELAYED_EXIT, 1_500, 400, null);
        assertEquals(WorkbenchSpeed.DirectKind.MISSED, attempt.result.kind());
        assertTrue(attempt.timing.contains("stage=fallback"), attempt.timing);
        assertTrue(attempt.timing.contains("fresh-jvm"), attempt.timing);
        assertFalse(Files.exists(attempt.handle), "a warm JVM that never acknowledged is released");
    }

    @Test
    void startedThenDelayedExitIsNotAFallback() throws Exception {
        Attempt attempt = talk(temp.resolve("ack"), Mode.STARTED_THEN_EXIT, 1_200, 400, null);
        assertEquals(WorkbenchSpeed.DirectKind.COMPLETED, attempt.result.kind());
        assertEquals(0, attempt.result.exit());
        assertTrue(attempt.out.toString(StandardCharsets.UTF_8).contains("warm-ok"), attempt.out.toString());
        assertFalse(attempt.timing.contains("stage=fallback"), attempt.timing);
        assertFalse(attempt.timing.contains("fresh-jvm"), attempt.timing);
        assertTrue(Files.isRegularFile(attempt.handle), "an acknowledged run must not kill the warm JVM");
    }

    @Test
    void closedPortMayStillFallBack() throws Exception {
        Attempt attempt = talk(temp.resolve("closed"), Mode.CLOSED, 0, 200, null);
        assertEquals(WorkbenchSpeed.DirectKind.MISSED, attempt.result.kind());
        assertTrue(attempt.timing.contains("stage=fallback"), attempt.timing);
        assertTrue(attempt.timing.contains("fresh-jvm"), attempt.timing);
    }

    @Test
    void startedThenDropDoesNotFallBack() throws Exception {
        Attempt attempt = talk(temp.resolve("drop"), Mode.STARTED_THEN_DROP, 0, 200, null);
        assertEquals(WorkbenchSpeed.DirectKind.INTERRUPTED, attempt.result.kind());
        assertEquals(1, attempt.result.exit());
        assertTrue(attempt.result.acknowledged());
        assertFalse(attempt.timing.contains("stage=fallback"), attempt.timing);
        assertFalse(attempt.timing.contains("fresh-jvm"), attempt.timing);
        assertTrue(attempt.err.toString(StandardCharsets.UTF_8).contains("connection dropped"), attempt.err.toString());
        assertTrue(Files.isRegularFile(attempt.handle));
    }

    @Test
    void runTimeoutAfterStartedIsNotAFallback() throws Exception {
        Attempt attempt = talk(temp.resolve("budget"), Mode.STARTED_THEN_EXIT, 1_500, 400, 400L);
        assertEquals(WorkbenchSpeed.DirectKind.TIMED_OUT, attempt.result.kind());
        assertTrue(attempt.result.acknowledged());
        assertFalse(attempt.timing.contains("stage=fallback"), attempt.timing);
        assertTrue(attempt.timing.contains("stage=direct"), attempt.timing);
        assertTrue(attempt.timing.contains("timeout"), attempt.timing);
        assertTrue(Files.isRegularFile(attempt.handle));
    }

    @Test
    void sequentialStillSkipsTheWarmJvm() {
        String previous = System.getProperty(WorkbenchSpeed.MODE_PROPERTY);
        try {
            System.setProperty(WorkbenchSpeed.MODE_PROPERTY, "sequential");
            WorkbenchSpeed.enableDirect();
            WorkbenchSpeed.DirectAttempt attempt = WorkbenchSpeed.tryDirect(
                    temp.resolve("sequential"),
                    List.of("-Dpkb_run_id=seq"),
                    new PrintStream(OutputStream.nullOutputStream()),
                    new PrintStream(OutputStream.nullOutputStream()),
                    null
            );
            assertEquals(WorkbenchSpeed.DirectKind.SKIPPED, attempt.kind());
        } finally {
            WorkbenchSpeed.clearDirect();
            if (previous == null) System.clearProperty(WorkbenchSpeed.MODE_PROPERTY);
            else System.setProperty(WorkbenchSpeed.MODE_PROPERTY, previous);
        }
    }

    @Test
    void secondRunWhileBusyIsRejected() throws Exception {
        WorkbenchWarmMain.setBusy(true);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicReference<String> response = new AtomicReference<>();
            Thread client = new Thread(() -> {
                try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                    PrintWriter writer = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    writer.println("RUN");
                    writer.println(".");
                    writer.println(temp.resolve("busy-project").toString());
                    writer.println("0");
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line).append('\n');
                        if ("END".equals(line)) break;
                    }
                    response.set(body.toString());
                } catch (Exception failure) {
                    response.set(failure.toString());
                }
            }, "warm-busy-client");
            client.setDaemon(true);
            client.start();
            try (Socket accepted = server.accept()) {
                WorkbenchWarmMain.serve(accepted);
            }
            client.join(5_000);
            String body = response.get();
            assertTrue(body != null && body.startsWith("EXIT 2"), String.valueOf(body));
            assertFalse(body.contains("STARTED"), body);
        } finally {
            WorkbenchWarmMain.setBusy(false);
        }
    }

    private static Attempt talk(Path project, Mode mode, int delayMillis, int handshakeMillis, Long runTimeoutMillis) throws Exception {
        Files.createDirectories(project);
        String runId = "warm-" + project.getFileName();
        List<String> command = List.of("-Dpkb_run_id=" + runId);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        Path state = project.resolve("state");
        Files.createDirectories(state);
        int port;
        Thread serverThread = null;
        ServerSocket server = null;
        if (mode == Mode.CLOSED) {
            try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = closed.getLocalPort();
            }
        } else {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            port = server.getLocalPort();
            ServerSocket accept = server;
            serverThread = new Thread(() -> speak(accept, mode, delayMillis), "warm-stub");
            serverThread.setDaemon(true);
            serverThread.start();
        }
        Process ended = new ProcessBuilder("true").start();
        ended.waitFor();
        WarmProcess.write(state, new WarmProcess.Handle(ended.pid(), port, "watch"));
        WorkbenchSpeed.DirectAttempt result = WorkbenchSpeed.record(
                project,
                command,
                WorkbenchSpeed.exchange(
                        port,
                        command,
                        project,
                        0L,
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                        handshakeMillis,
                        runTimeoutMillis,
                        state
                ),
                0L
        );
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2_000);
        Path timing = project.resolve(".pickleball").resolve("runs").resolve(runId).resolve("launch-timing.txt");
        String text = Files.isRegularFile(timing) ? Files.readString(timing) : "";
        return new Attempt(result, text, outBytes, errBytes, WarmProcess.file(state));
    }

    private static void speak(ServerSocket server, Mode mode, int delayMillis) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(5_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String verb = reader.readLine();
            if (!"RUN".equals(verb)) return;
            String line;
            while ((line = reader.readLine()) != null && !".".equals(line)) {
                // dash-D tokens
            }
            reader.readLine();
            reader.readLine();
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            if (mode == Mode.DELAYED_EXIT) {
                Thread.sleep(delayMillis);
                writer.println("EXIT 0");
                writer.println("END");
                return;
            }
            writer.println("STARTED");
            if (mode == Mode.STARTED_THEN_DROP) {
                socket.close();
                return;
            }
            Thread.sleep(delayMillis);
            writer.println("EXIT 0");
            writer.println("warm-ok");
            writer.println("END");
        } catch (Exception ignored) {
            // The client assertion is the result, not the stub's exit.
        }
    }

    private enum Mode {
        DELAYED_EXIT,
        STARTED_THEN_EXIT,
        STARTED_THEN_DROP,
        CLOSED
    }

    private record Attempt(
            WorkbenchSpeed.DirectAttempt result,
            String timing,
            ByteArrayOutputStream out,
            ByteArrayOutputStream err,
            Path handle
    ) {
    }
}
