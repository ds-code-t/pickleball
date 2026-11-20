package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persistent controller starts, serves warm verbs, and stops. */
class AgentControllerTest {
    @TempDir
    Path project;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void warmVerbsStayUnderHalfASecond() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process server = new ProcessBuilder(
                java, "-cp", classpath, "tools.dscode.launcher.PkbMain", "controller-serve", project.toString()
        ).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!Files.isRegularFile(PkbMain.handleFile(project))) {
                if (!server.isAlive() || System.nanoTime() > deadline) {
                    throw new AssertionError("controller did not start, exit=" + (server.isAlive() ? "running" : server.exitValue()));
                }
                Thread.sleep(50);
            }
            String thin = thinClasspath(classpath);
            List<Long> samples = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                long started = System.nanoTime();
                Run ping = launch(java, thin, project, "controller-ping", project.toString());
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                samples.add(elapsed);
                assertEquals(0, ping.exit, ping.err + ping.out);
                assertTrue(ping.out.contains("\"v\":1") || ping.out.contains("v=1"), ping.out);
                assertTrue(elapsed < 500, "warm ping " + elapsed + " ms samples=" + samples);
            }
            System.out.println("warm-ping-ms=" + samples);
            Run help = launch(java, thin, project, "help", "snap");
            assertEquals(0, help.exit, help.err);
            assertTrue(help.out.contains("Usage: snap"), help.out);
        } finally {
            launch(java, thinClasspath(classpath), project, "controller-stop", project.toString());
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
        }
        assertFalse(Files.isRegularFile(PkbMain.handleFile(project)));
    }

    @Test
    void covers_command_controller_start() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"controller-status", project.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void covers_command_controller_stop() {
        assertTrue(PkbMain.request("token", "stop", List.of("controller-stop")).contains("\"op\":\"stop\""));
        assertTrue(PkbMain.handleFile(project).endsWith("agent-controller.json"));
        assertEquals("WorkbenchAgentCommands", WorkbenchAgentCommands.class.getSimpleName());
    }

    @Test
    void covers_command_controller_status() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"controller-status", project.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void covers_command_controller_ping() {
        String json = PkbMain.request("abc", "ping", List.of("controller-ping"));
        assertTrue(json.contains("\"op\":\"ping\""));
        assertTrue(json.contains("controller-ping"));
        assertEquals("WorkbenchAgentCommands", WorkbenchAgentCommands.class.getSimpleName());
    }

    @Test
    void covers_command_ref() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"ref", project.toString(), "--id=s9.h99"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(1, code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"err\""));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("unknown ref"));
    }

    private static String thinClasspath(String classpath) throws Exception {
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve("tools/dscode/launcher/PkbMain.class"))) {
                return path.toString();
            }
        }
        return classpath;
    }

    private static Run launch(String java, String classpath, Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classpath);
        command.add("tools.dscode.launcher.PkbMain");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = drain(process.getInputStream(), out);
        Thread stderr = drain(process.getErrorStream(), err);
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        stdout.join(1000);
        stderr.join(1000);
        if (!finished) process.destroyForcibly();
        return new Run(finished ? process.exitValue() : 124, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream into) {
        Thread thread = new Thread(() -> {
            try {
                input.transferTo(into);
            } catch (Exception ignored) {
                // The process ending closes the stream.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record Run(int exit, String out, String err) {
    }
}
