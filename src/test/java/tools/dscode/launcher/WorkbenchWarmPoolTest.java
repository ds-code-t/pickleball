package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.WarmPool;
import tools.dscode.control.protocol.speed.WarmProcess;
import tools.dscode.control.protocol.speed.WarmSlot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWarmPoolTest {
    @TempDir
    Path project;

    @AfterEach
    void clearHooks() {
        WorkbenchSpeed.testSlotStarter = null;
        WorkbenchSpeed.clearDirect();
        System.clearProperty(WorkbenchSpeed.POOL_PROPERTY);
        System.clearProperty(WorkbenchSpeed.QUEUE_PROPERTY);
        System.clearProperty(WarmPoolLog.PROPERTY);
    }

    @Test
    void poolMaxAndQueueUseTheSameKeyOrder() {
        assertEquals(2, WorkbenchSpeed.poolMax(key -> "9", key -> null));
        assertEquals(2, WorkbenchSpeed.poolMax(key -> "nope", key -> null));
        assertEquals(3, WorkbenchSpeed.poolMax(key -> WorkbenchSpeed.POOL_PROPERTY.equals(key) ? "3" : null, key -> "8"));
        assertEquals(4, WorkbenchSpeed.poolMax(key -> null, key -> WorkbenchSpeed.POOL_ENV.equals(key) ? "4" : null));
        assertEquals(0, WorkbenchSpeed.queueSeconds(key -> null, key -> null));
        assertEquals(12, WorkbenchSpeed.queueSeconds(
                key -> WorkbenchSpeed.QUEUE_PROPERTY.equals(key) ? "12" : null,
                key -> null
        ));
        WorkbenchSpeed.warmWait(7);
        try {
            assertEquals(7, WorkbenchSpeed.queueSeconds());
        } finally {
            WorkbenchSpeed.warmWait(null);
        }
    }

    @Test
    void atCapWithNoWaitRefusesAndDoesNotStartAnotherSlot() throws Exception {
        System.setProperty(WorkbenchSpeed.POOL_PROPERTY, "1");
        Stub first = stub(true, "run-a");
        try {
            writeCache();
            writeSlot(1, first, "run-a");
            AtomicInteger started = new AtomicInteger();
            WorkbenchSpeed.testSlotStarter = index -> {
                started.incrementAndGet();
                return false;
            };
            Attempt attempt = run("run-b");
            assertEquals(WorkbenchSpeed.DirectKind.REJECTED, attempt.kind);
            assertEquals(2, attempt.exit);
            assertTrue(attempt.err.contains("all 1 warm JVMs are busy"), attempt.err);
            assertTrue(attempt.err.contains("run-a"), attempt.err);
            assertTrue(attempt.err.contains("--warm-wait"), attempt.err);
            assertFalse(attempt.timing.contains("fresh-jvm"), attempt.timing);
            assertEquals(0, started.get());
            assertTrue(WarmProcess.alive(first.pid));
        } finally {
            first.close();
        }
    }

    @Test
    void atCapWithWaitTakesTheSlotWhenItGoesIdle() throws Exception {
        System.setProperty(WorkbenchSpeed.POOL_PROPERTY, "1");
        AtomicBoolean busy = new AtomicBoolean(true);
        Stub first = stub(true, "run-a", busy);
        Path slot = WarmSlot.directory(state(), 1);
        try {
            writeCache();
            writeSlot(1, first, "run-a");
            WorkbenchSpeed.warmWait(5);
            Thread flip = new Thread(() -> {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                busy.set(false);
                WarmSlot.clearBusy(slot);
            });
            flip.setDaemon(true);
            flip.start();
            Attempt attempt = run("run-b");
            assertEquals(WorkbenchSpeed.DirectKind.COMPLETED, attempt.kind, attempt.err);
            assertEquals(0, attempt.exit);
            assertTrue(attempt.timing.contains("action=queue"), attempt.timing);
            assertTrue(WarmProcess.alive(first.pid));
        } finally {
            WorkbenchSpeed.warmWait(null);
            first.close();
        }
    }

    @Test
    void busySlotStaysUpWhileASecondSlotIsStarted() throws Exception {
        System.setProperty(WorkbenchSpeed.POOL_PROPERTY, "2");
        Stub busy = stub(true, "run-a");
        Stub idle = stub(false, "");
        try {
            writeCache();
            writeSlot(1, busy, "run-a");
            WorkbenchSpeed.testSlotStarter = index -> {
                assertEquals(2, index);
                try {
                    writeSlot(index, idle, "");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return true;
            };
            Attempt attempt = run("run-b");
            assertEquals(WorkbenchSpeed.DirectKind.COMPLETED, attempt.kind, attempt.err);
            assertEquals(0, attempt.exit);
            assertTrue(attempt.timing.contains("action=start"), attempt.timing);
            assertTrue(WarmProcess.alive(busy.pid));
            assertTrue(Files.isRegularFile(WarmSlot.handleFile(WarmSlot.directory(state(), 1))));
        } finally {
            busy.close();
            idle.close();
        }
    }

    @Test
    void sameRunIdIsRefused() throws Exception {
        Stub busy = stub(true, "run-a");
        try {
            writeCache();
            writeSlot(1, busy, "run-a");
            Attempt attempt = run("run-a");
            assertEquals(WorkbenchSpeed.DirectKind.REJECTED, attempt.kind);
            assertTrue(attempt.err.contains("already running"), attempt.err);
        } finally {
            busy.close();
        }
    }

    @Test
    void releaseIdleLeavesABusySlotAndStopRunIdKillsOnlyThatRun() throws Exception {
        Stub busy = stub(true, "run-a");
        Stub other = stub(true, "run-b");
        try {
            writeSlot(1, busy, "run-a");
            writeSlot(2, other, "run-b");
            WorkbenchSpeed.release(project);
            assertTrue(WarmProcess.alive(busy.pid));
            assertTrue(WarmProcess.alive(other.pid));
            WorkbenchSpeed.releaseRun(project, "run-b");
            assertTrue(WarmProcess.alive(busy.pid));
            assertFalse(busyProcess(other));
            assertTrue(Files.isRegularFile(WarmSlot.handleFile(WarmSlot.directory(state(), 1))));
        } finally {
            busy.close();
            other.close();
        }
    }

    @Test
    void staleBusySlotIsMarkedRetireAndIsNotKilled() throws Exception {
        System.setProperty(WorkbenchSpeed.POOL_PROPERTY, "2");
        Stub busy = stub(true, "run-a");
        Stub fresh = stub(false, "");
        try {
            writeCache();
            writeActive("gen-new");
            writeSlot(1, busy, "run-a", "gen-old");
            WorkbenchSpeed.testSlotStarter = index -> {
                try {
                    writeSlot(index, fresh, "", "gen-new");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return true;
            };
            Attempt attempt = run("run-b");
            assertEquals(WorkbenchSpeed.DirectKind.COMPLETED, attempt.kind, attempt.err);
            assertTrue(WarmSlot.retirePending(WarmSlot.directory(state(), 1)));
            assertTrue(WarmProcess.alive(busy.pid));
        } finally {
            busy.close();
            fresh.close();
        }
    }

    @Test
    void warmPoolLogRotatesAtTheCap() throws Exception {
        Path state = state();
        Files.createDirectories(state);
        String line = "x".repeat(1024);
        for (int i = 0; i < 1100; i++) WarmPoolLog.append(state, line);
        Path log = state.resolve("logs").resolve("warm-pool.log");
        Path rotated = state.resolve("logs").resolve("warm-pool.log.1");
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.isRegularFile(rotated));
        assertTrue(Files.size(log) < WarmPoolLog.MAX_BYTES + 4096);
        System.setProperty(WarmPoolLog.PROPERTY, "false");
        long before = Files.size(log);
        WarmPoolLog.append(state, "should-skip");
        assertEquals(before, Files.size(log));
    }

    private boolean busyProcess(Stub stub) {
        return stub.process != null && stub.process.isAlive();
    }

    private Attempt run(String runId) throws Exception {
        WorkbenchSpeed.enableDirect();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WorkbenchSpeed.DirectAttempt attempt = WorkbenchSpeed.tryDirect(
                project,
                List.of("-Dpkb_run_id=" + runId),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                null
        );
        Path timing = project.resolve(".pickleball").resolve("runs").resolve(runId).resolve("launch-timing.txt");
        String text = Files.isRegularFile(timing) ? Files.readString(timing) : "";
        return new Attempt(attempt.kind(), attempt.exit(), text, err.toString(StandardCharsets.UTF_8));
    }

    private void writeCache() throws Exception {
        Path classes = project.resolve("target/test-classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("marker.txt"), "x");
        ClasspathCache.store(
                state(),
                InputFingerprint.lockHash(project),
                List.of(classes.toString())
        );
    }

    private void writeActive(String hash) throws Exception {
        Path warm = state().resolve("warm");
        Files.createDirectories(warm);
        Path tests = warm.resolve("gen-" + hash).resolve("test-classes");
        Files.createDirectories(tests);
        Files.writeString(warm.resolve("active-generation.txt"), hash + "\n" + tests + "\n" + tests + "\n");
    }

    private void writeSlot(int index, Stub stub, String runId) throws Exception {
        writeSlot(index, stub, runId, "");
    }

    private void writeSlot(int index, Stub stub, String runId, String generation) throws Exception {
        Path dir = WarmSlot.directory(state(), index);
        WarmSlot.write(dir, new WarmSlot.Handle(stub.pid, stub.port, generation, 0L, InputFingerprint.watchHash(project)));
        if (runId != null && !runId.isBlank()) WarmSlot.markBusy(dir, runId);
    }

    private Path state() throws Exception {
        Path dir = PickleballLocalLayout.workbenchStateRoot(project);
        Files.createDirectories(dir);
        return dir;
    }

    private Stub stub(boolean busy, String runId) throws Exception {
        return stub(busy, runId, null);
    }

    private Stub stub(boolean busy, String runId, AtomicBoolean live) throws Exception {
        Process hold = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "tools.dscode.launcher.WorkbenchWarmMain",
                "hold"
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> speak(server, busy, runId, live), "warm-pool-stub");
        thread.setDaemon(true);
        thread.start();
        return new Stub(hold, server, server.getLocalPort(), hold.pid(), thread);
    }

    private static void speak(ServerSocket server, boolean busy, String runId, AtomicBoolean live) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String verb = reader.readLine();
                boolean nowBusy = live == null ? busy : live.get();
                if ("PING".equals(verb)) {
                    writer.println(nowBusy ? "PONG busy " + runId : "PONG idle");
                    continue;
                }
                if (!"RUN".equals(verb)) continue;
                String line;
                while ((line = reader.readLine()) != null && !".".equals(line)) {
                    // dash-D
                }
                reader.readLine();
                reader.readLine();
                if (nowBusy) {
                    writer.println("EXIT 2");
                    writer.println("END");
                } else {
                    writer.println("STARTED");
                    writer.println("EXIT 0");
                    writer.println("END");
                }
            } catch (Exception ignored) {
                if (server.isClosed()) return;
            }
        }
    }

    private record Attempt(WorkbenchSpeed.DirectKind kind, int exit, String timing, String err) {
    }

    private static final class Stub implements AutoCloseable {
        final Process process;
        final ServerSocket server;
        final int port;
        final long pid;
        final Thread thread;

        Stub(Process process, ServerSocket server, int port, long pid, Thread thread) {
            this.process = process;
            this.server = server;
            this.port = port;
            this.pid = pid;
            this.thread = thread;
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (Exception ignored) {
                // Closed.
            }
            process.destroyForcibly();
        }
    }
}
