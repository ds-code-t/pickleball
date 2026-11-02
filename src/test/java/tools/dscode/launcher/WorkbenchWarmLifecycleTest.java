package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
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
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stopKillsTheWarmJvmAndItsChild() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(warm.pid());
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
            warm.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void idleTimeoutKillsTheWarmJvmAndItsChild() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 2);
        try {
            List<ProcessHandle> children = awaitTree(warm.pid());
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            warm.destroyForcibly();
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
            List<ProcessHandle> children = awaitTree(warm.pid());
            parent.destroyForcibly();
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            awaitDead(warm.pid(), children);
            assertFalse(Files.isRegularFile(WarmProcess.file(state)));
        } finally {
            parent.destroyForcibly();
            if (warm != null) warm.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void sessionThatExitsBeforeHealthyKillsTheWarmJvm() throws Exception {
        Path state = state();
        Process warm = startWarm(state, ProcessHandle.current().pid(), 900);
        try {
            List<ProcessHandle> children = awaitTree(warm.pid());
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
            warm.destroyForcibly();
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
            List<ProcessHandle> children = awaitTree(warm.pid());
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
            warm.destroyForcibly();
        }
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

    private static List<ProcessHandle> awaitTree(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (WarmProcess.alive(pid)) {
                List<ProcessHandle> children = descendants(pid);
                if (!children.isEmpty()) return children;
            }
            Thread.sleep(50);
        }
        fail("warm pid " + pid + " did not start a child; alive=" + WarmProcess.alive(pid));
        return List.of();
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
