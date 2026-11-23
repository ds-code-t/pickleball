package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.speed.ProcessTree;
import tools.dscode.control.protocol.speed.WarmProcess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WorkbenchWarmLifecycleTest {
    @TempDir
    Path project;

    @Test
    void idleTimeoutUsesTheWorkerStartKeyOrder() {
        assertEquals(900, WarmProcess.DEFAULT_IDLE_SECONDS);
        assertEquals(12, WorkbenchSpeed.idleSeconds(
                key -> WorkbenchSpeed.IDLE_PROPERTY.equals(key) ? "12" : null,
                key -> "99"
        ));
        assertEquals(15, WorkbenchSpeed.idleSeconds(
                key -> null,
                key -> WorkbenchSpeed.IDLE_ENV.equals(key) ? "15" : null
        ));
        assertEquals(30, WorkbenchSpeed.idleSeconds(
                key -> WorkbenchSpeed.IDLE_RUNVAR.equals(key) ? "30" : null,
                key -> null
        ));
        assertEquals(45, WorkbenchSpeed.idleSeconds(
                key -> "pkb_runvars".equals(key)
                        ? "pkb_browser=CHROME_HEADLESS, pkb_workbench_warm_idle_timeout=45"
                        : null,
                key -> null
        ));
        assertEquals(900, WorkbenchSpeed.idleSeconds(idleProperty("nope"), key -> null));
        assertEquals(900, WorkbenchSpeed.idleSeconds(idleProperty("0"), key -> null));
        assertEquals(900, WorkbenchSpeed.idleSeconds(idleProperty("-4"), key -> null));
        assertEquals(900, WorkbenchSpeed.idleSeconds(key -> " ", key -> " "));
        assertEquals(8, WorkbenchSpeed.idleSeconds(
                key -> WorkbenchSpeed.IDLE_PROPERTY.equals(key) ? " 8 " : null,
                key -> null
        ));
    }

    @Test
    void idleDoesNotFireDuringARunAndParentDeathDoes() {
        long now = 10_000;
        assertFalse(WorkbenchWarmMain.exitDue(0, true, 1, 0, now));
        assertFalse(WorkbenchWarmMain.exitDue(0, false, 5_000, now - 1_000, now));
        assertTrue(WorkbenchWarmMain.exitDue(0, false, 5_000, now - 5_000, now));
        assertFalse(WorkbenchWarmMain.exitDue(ProcessHandle.current().pid(), true, 1, 0, now));
        assertTrue(WorkbenchWarmMain.exitDue(ProcessHandle.current().pid(), false, 5_000, now - 5_000, now));
        assertTrue(WorkbenchWarmMain.exitDue(1_000_000_001L, true, 1, now, now));
        assertTrue(WorkbenchWarmMain.exitDue(0, true, 1, now, now, true));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deletedStateFileExitsTheWarmJvm() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            assertTrue(Files.isRegularFile(WarmProcess.file(state)));
            assertEquals(warm.pid(), WarmProcess.rememberedPid(state));
            Files.delete(WarmProcess.file(state));
            awaitDead(warm.pid(), children);
        } finally {
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void releaseKillsTheRememberedPidWhenTheHandleFileIsGone() throws Exception {
        Path state = state();
        Process hold = startHold(state);
        try {
            awaitAlive(hold.pid());
            Files.writeString(WarmProcess.pidFile(state), hold.pid() + "\n");
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
            WarmProcess.release(state);
            assertTrue(hold.waitFor(10, TimeUnit.SECONDS), "stop must kill the remembered pid");
            assertFalse(WarmProcess.alive(hold.pid()));
            assertFalse(Files.isRegularFile(WarmProcess.pidFile(state)));
        } finally {
            hold.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stopKillsTheWarmJvmAndItsChild() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = WorkbenchSessionCommands.run(
                    new String[]{"stop", project.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    launch -> {
                        throw new AssertionError("stop must not start a session");
                    }
            );
            assertEquals(0, code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("already-stopped"));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void idleTimeoutKillsTheWarmJvmAndItsChild() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 2);
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shortLivedCliExitLeavesTheWarmJvm() throws Exception {
        Path state = state();
        Process warm = startWarm(state, 0, 900);
        Process cli = null;
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            cli = startHold(state);
            awaitAlive(cli.pid());
            ProcessTree.kill(cli.pid());
            assertTrue(cli.waitFor(10, TimeUnit.SECONDS), "the one-shot CLI did not exit");
            Thread.sleep(1000);
            assertTrue(WarmProcess.alive(warm.pid()), "a one-shot CLI must not be the warm owner");
            assertTrue(alive(children.getFirst()), "the warm child must survive the CLI");
            assertTrue(Files.isRegularFile(WarmProcess.file(state)));
            int code = WorkbenchSessionCommands.run(
                    new String[]{"stop", project.toString()},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    launch -> {
                        throw new AssertionError("stop must not start a session");
                    }
            );
            assertEquals(0, code);
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            if (cli != null && cli.isAlive()) ProcessTree.kill(cli.pid());
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void longLivedOwnerClaimDeathKillsTheWarmJvm() throws Exception {
        Path state = state();
        Process owner = startHold(state);
        Process warm = null;
        try {
            awaitAlive(owner.pid());
            warm = startWarm(state, 0, 900);
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            WarmProcess.claim(state, owner.pid());
            assertEquals(owner.pid(), WarmProcess.liveOwner(state));
            owner.destroyForcibly();
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            owner.destroyForcibly();
            stopWarm(warm, state);
            WarmProcess.deleteOwner(state);
        }
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void parentDeathKillsTheWarmJvmAndItsChild() throws Exception {
        Path state = state();
        Process parent = startHold(state);
        Process warm = null;
        try {
            awaitAlive(parent.pid());
            warm = startWarm(state, parent.pid(), 900);
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            parent.destroyForcibly();
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            parent.destroyForcibly();
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sessionThatExitsBeforeHealthyKillsTheWarmJvm() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            WorkbenchSessionCommandsTest.seedWorkspace(project, "warm-died");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = WorkbenchSessionCommands.run(
                    new String[]{"session-start", project.toString(), "--run-id=warm-died"},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    launch -> exitedJava()
            );
            assertEquals(1, code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            stopWarm(warm, state);
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void sessionStartTimeoutKillsTheWarmJvm() throws Exception {
        Path state = state();
        String property = SessionHealthWait.START_TIMEOUT_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, "1");
        AtomicReference<Process> session = new AtomicReference<>();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(state, warm.pid());
            WorkbenchSessionCommandsTest.seedWorkspace(project, "warm-timeout");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = WorkbenchSessionCommands.run(
                    new String[]{"session-start", project.toString(), "--run-id=warm-timeout"},
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    launch -> {
                        Process hold = startHold(state);
                        session.set(hold);
                        return hold;
                    }
            );
            assertEquals(1, code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
            Process hold = session.get();
            if (hold != null) hold.destroyForcibly();
            stopWarm(warm, state);
        }
    }

    /** Ends a warm JVM and waits until its stderr redirect can be removed. */
    private static void stopWarm(Process warm, Path state) throws Exception {
        if (warm != null && warm.isAlive()) {
            ProcessTree.kill(warm.pid());
            warm.waitFor(5, TimeUnit.SECONDS);
        }
        if (state == null) return;
        Path log = state.resolve("warm-test.log");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Files.deleteIfExists(log);
                return;
            } catch (IOException failure) {
                last = failure;
                Thread.sleep(50);
            }
        }
        throw new IOException("warm stderr redirect stayed locked: " + log, last);
    }

    private Path state() throws IOException {
        Path dir = PickleballLocalLayout.workbenchStateRoot(project);
        Files.createDirectories(dir);
        return dir;
    }

    private static Process startWarm(Path state, long parentPid, long idleSeconds) throws IOException {
        Path log = state.resolve("warm-test.log");
        ProcessBuilder builder = new ProcessBuilder(
                javaBin(),
                "-Dpkb_warm_test_child=true",
                "-cp",
                "@" + classpathFile(state).toAbsolutePath().normalize(),
                "tools.dscode.launcher.WorkbenchWarmMain",
                state.toAbsolutePath().normalize().toString(),
                "watch-test",
                Long.toString(parentPid),
                Long.toString(idleSeconds)
        );
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(log.toFile());
        return builder.start();
    }

    private static Process startHold(Path state) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                javaBin(),
                "-cp",
                "@" + classpathFile(state).toAbsolutePath().normalize(),
                "tools.dscode.launcher.WorkbenchWarmMain",
                "hold"
        );
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static Process exitedJava() throws IOException {
        Process process = new ProcessBuilder(javaBin(), "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return process;
    }

    private static Path classpathFile(Path state) throws IOException {
        Path file = state.resolve("warm-test-cp.txt");
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, System.getProperty("java.class.path", ""), StandardCharsets.UTF_8);
        }
        return file;
    }

    private static String javaBin() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static List<ProcessHandle> awaitTree(Path state, long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (WarmProcess.alive(pid) && Files.isRegularFile(WarmProcess.file(state))) {
                List<ProcessHandle> children = descendants(pid);
                if (!children.isEmpty()) return children;
            }
            Thread.sleep(50);
        }
        String listing = directoryListing(state);
        String log = warmLog(state);
        fail("warm pid " + pid + " did not publish a handle and a child; alive="
                + WarmProcess.alive(pid) + " files=" + listing + " log=" + log);
        return List.of();
    }

    private static String directoryListing(Path state) {
        try (var stream = Files.list(state)) {
            return stream.map(path -> path.getFileName().toString()).toList().toString();
        } catch (IOException failure) {
            return failure.getMessage();
        }
    }

    private static String warmLog(Path state) {
        Path log = state.resolve("warm-test.log");
        if (!Files.isRegularFile(log)) return "";
        try {
            return Files.readString(log);
        } catch (IOException failure) {
            return failure.getMessage();
        }
    }

    private static void awaitAlive(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (WarmProcess.alive(pid)) return;
            Thread.sleep(50);
        }
        fail("pid " + pid + " did not stay alive");
    }

    private static void awaitDead(long pid, List<ProcessHandle> children) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            boolean childAlive = false;
            if (children != null) {
                for (ProcessHandle child : children) {
                    if (alive(child)) childAlive = true;
                }
            }
            if (!childAlive) {
                for (ProcessHandle child : descendants(pid)) {
                    if (alive(child)) childAlive = true;
                }
            }
            if (!WarmProcess.alive(pid) && !childAlive) return;
            Thread.sleep(50);
        }
        fail("warm pid " + pid + " still alive=" + WarmProcess.alive(pid));
    }

    private static List<ProcessHandle> descendants(long pid) {
        return ProcessHandle.of(pid).map(handle -> {
            try {
                return handle.descendants().toList();
            } catch (RuntimeException ignored) {
                return List.<ProcessHandle>of();
            }
        }).orElse(List.of());
    }

    private static boolean alive(ProcessHandle handle) {
        try {
            return handle != null && handle.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Function<String, String> idleProperty(String propertyValue) {
        return key -> WorkbenchSpeed.IDLE_PROPERTY.equals(key) ? propertyValue : null;
    }
}
