package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows-safe controller liveness, classpath, and the in-process hop fallback. */
class ControllerReliabilityTest {
    @TempDir
    Path project;

    @Test
    void currentProcessIsAliveAndAMissingPidIsNot() {
        assertTrue(PkbMain.processAlive(ProcessHandle.current().pid()));
        assertFalse(PkbMain.processAlive(0));
        assertFalse(PkbMain.processAlive(Long.MAX_VALUE - 1));
    }

    @Test
    void livePidWithAClosedPortIsNotTheController() throws Exception {
        long pid = ProcessHandle.current().pid();
        writeHandle(pid, 1);
        long started = System.nanoTime();
        assertFalse(PkbMain.controllerUp(project));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < 2000, "closed port blocked for " + elapsed + " ms");
    }

    @Test
    void livePidWithAnOpenPortIsTheController() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            writeHandle(ProcessHandle.current().pid(), server.getLocalPort());
            assertTrue(PkbMain.controllerUp(project));
        }
        assertFalse(PkbMain.controllerUp(project));
    }

    @Test
    void deadPidIsNotTheControllerEvenWhenTheFileRemains() throws Exception {
        writeHandle(Long.MAX_VALUE - 1, 9);
        assertFalse(PkbMain.controllerUp(project));
        assertTrue(Files.isRegularFile(PkbMain.handleFile(project)));
    }

    @Test
    void downControllerReturnsImmediately() throws Exception {
        long started = System.nanoTime();
        int doctor = PkbMain.run(new String[]{"doctor", project.toString()});
        int act = PkbMain.run(new String[]{"act", project.toString()});
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(PkbMain.HOP_MISS, doctor);
        assertEquals(PkbMain.HOP_MISS, act);
        assertTrue(elapsed < 2000, "down controller blocked for " + elapsed + " ms");
        OptionalInt hopped = PickleballWorkbenchLauncher.controllerHop(new String[]{"doctor", project.toString()});
        assertTrue(hopped.isEmpty());
    }

    @Test
    void inProcessDoctorStillRunsWhenTheHopMisses() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"doctor", project.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("chrome="), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cacheBeatsAWrapperClasspathThatLacksPkbMain() throws Exception {
        Path classes = pkbMainClasses();
        Path versioned = project.resolve(".pickleball/v/3.0.0/workbench");
        Files.createDirectories(versioned);
        Files.writeString(project.resolve(".pickleball/current.json"), """
                {
                  "pickleballVersion": "3.0.0",
                  "complete": true,
                  "updatedAt": "2026-11-21T00:00:00Z"
                }
                """);
        Files.writeString(versioned.resolve("classpath.cache"), "\"" + classes + "\"\n");
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(".pickleball/workbench/classpath.cache"), "\"/not/a/pickleball/maven-launcher.jar\"\n");
        String chosen = PkbMain.controllerClasspath(project, "/opt/maven/boot/plexus-classworlds.jar");
        assertEquals(classes.toString(), chosen);
        assertTrue(PkbMain.classpathHasPkbMain(chosen));
        assertFalse(chosen.contains("plexus-classworlds"));
    }

    @Test
    void loaderClasspathIsUsedWhenTheCacheAndTheWrapperLackPkbMain() throws Exception {
        Files.createDirectories(project.resolve(".pickleball/workbench"));
        Files.writeString(project.resolve(".pickleball/workbench/classpath.cache"), "\"maven-launcher.jar\"\n");
        String chosen = PkbMain.controllerClasspath(project, "maven-launcher.jar");
        assertTrue(PkbMain.classpathHasPkbMain(chosen), chosen);
        assertFalse(chosen.equals("maven-launcher.jar"), chosen);
        assertTrue(chosen.contains(pkbMainClasses().toString()) || PkbMain.entryHasPkbMain(Path.of(chosen.split(java.io.File.pathSeparator)[0])), chosen);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void controllerStartUsesAClasspathThatContainsPkbMain() throws Exception {
        String classpath = System.getProperty("java.class.path");
        assertTrue(PkbMain.classpathHasPkbMain(classpath), "test classpath has no PkbMain");
        Path cache = project.resolve(".pickleball/workbench/classpath.cache");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int started = PkbMain.run(new String[]{"controller-start", project.toString()});
            assertEquals(0, started, err.toString(StandardCharsets.UTF_8) + "\n" + log());
            assertTrue(PkbMain.controllerUp(project));
            int ping = PkbMain.run(new String[]{"controller-ping", project.toString()});
            assertEquals(0, ping, err.toString(StandardCharsets.UTF_8) + out.toString(StandardCharsets.UTF_8));
            int stopped = PkbMain.run(new String[]{"controller-stop", project.toString()});
            assertEquals(0, stopped, err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            try {
                PkbMain.run(new String[]{"controller-stop", project.toString()});
            } catch (Exception ignored) {
                // Already stopped.
            }
        }
        assertFalse(PkbMain.controllerUp(project));
    }

    private void writeHandle(long pid, int port) throws Exception {
        Path file = PkbMain.handleFile(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"pid\":" + pid + ",\"port\":" + port + ",\"token\":\"t\"}\n");
    }

    private String log() throws Exception {
        Path log = project.resolve(".pickleball/agent-controller.log");
        return Files.isRegularFile(log) ? Files.readString(log) : "";
    }

    private static Path pkbMainClasses() {
        String classpath = System.getProperty("java.class.path");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (PkbMain.entryHasPkbMain(path) && Files.isDirectory(path)) return path.toAbsolutePath().normalize();
        }
        throw new AssertionError("PkbMain.class is not a directory on java.class.path");
    }
}
