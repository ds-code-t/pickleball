package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.control.protocol.speed.ClasspathCache;
import tools.dscode.control.protocol.speed.InputFingerprint;
import tools.dscode.control.protocol.speed.WarmProcess;
import tools.dscode.testengine.PKB_props;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Report-6 bugs 2, 3, and 17. Direct/warm, sequential, and the launcher entry
 * ({@code java -cp} and {@code mvnw} both call {@link WorkbenchAgentCommands})
 * share one stdout contract.
 */
class WorkbenchLaunchOutputContractTest {
    private static final String MARKER_LINE = "# pkb-console The%20one worker-line";
    private static final String FAILED_INDEX = """
            {
              "runId": "%s",
              "outcome": "FAILED",
              "counts": { "total": 1, "passed": 0, "failed": 1 },
              "steps": { "executed": 2, "passed": 1, "failed": 1 }
            }
            """;

    @TempDir
    Path root;

    @BeforeEach
    void captureSeedWithoutAConsumerRunner() {
        WorkspaceLaunch.testCapture = workspace -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, "");
            values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
            values.put(PKB_props.PKB_FEATURES, "classpath:features");
            Workspace.writeRunVars(workspace, values);
        };
    }

    @AfterEach
    void clearDirectFlag() {
        WorkbenchSpeed.clearDirect();
        System.clearProperty(WorkbenchSpeed.MODE_PROPERTY);
        WorkspaceLaunch.testCapture = null;
    }

    @Test
    void consoleMarkerFilterDropsTapLinesAndKeepsOtherLines() {
        String filtered = WorkbenchRunReports.filterConsoleMarkers(MARKER_LINE + "\nplain-worker-line\n");
        assertFalse(filtered.contains("# pkb-console"), filtered);
        assertTrue(filtered.contains("plain-worker-line"), filtered);
    }

    @Test
    @Timeout(25)
    void directPathDoesNotEchoConsoleMarkersOrCallATestFailureAWarmFault() throws Exception {
        Path project = root.resolve("direct");
        AtomicBoolean wrapper = new AtomicBoolean(false);
        try (WarmFake warm = WarmFake.open(project)) {
            WorkbenchSpeed.enableDirect();
            System.setProperty(WorkbenchSpeed.MODE_PROPERTY, "direct");
            Capture capture = discover(project, "run-direct", false, (p, command, out, err) -> {
                wrapper.set(true);
                return 1;
            });
            assertFalse(wrapper.get(), "direct/warm was not used\n" + capture);
            assertContract(capture, "run-direct", false);
        }
    }

    @Test
    void quietKeepsTheSummaryAndDropsTheRest() throws Exception {
        Path project = root.resolve("quiet");
        Capture capture = discover(project, "run-quiet", true, (p, command, out, err) -> {
            out.println(MARKER_LINE);
            out.println("plain-worker-line");
            writeFailedIndex(p, "run-quiet");
            return 1;
        });
        assertContract(capture, "run-quiet", true);
        String saved = Files.readString(AgentCoordination.runDirectory(project, "run-quiet")
                .resolve("console").resolve("The_one.txt"));
        assertTrue(saved.contains("worker-line"), saved);
    }

    @Test
    void directSequentialAndLauncherEntryShareTheFilteredContract() throws Exception {
        Path directProject = root.resolve("parity-direct");
        Path sequentialProject = root.resolve("parity-sequential");
        Capture direct;
        try (WarmFake warm = WarmFake.open(directProject)) {
            WorkbenchSpeed.enableDirect();
            System.setProperty(WorkbenchSpeed.MODE_PROPERTY, "direct");
            direct = discover(directProject, "run-parity", false, (p, command, out, err) -> {
                throw new AssertionError("sequential wrapper ran on the direct project");
            });
        }
        WorkbenchSpeed.clearDirect();
        System.setProperty(WorkbenchSpeed.MODE_PROPERTY, "sequential");
        Capture sequential = discover(sequentialProject, "run-parity", false, (p, command, out, err) -> {
            out.println(MARKER_LINE);
            out.println("plain-worker-line");
            err.println("error: warm launch exited 1");
            err.println("fix: rerun with -Dpkb_workbench_speed=sequential to use the wrapper.");
            writeFailedIndex(p, "run-parity");
            return 1;
        });
        assertContract(direct, "run-parity", false);
        assertContract(sequential, "run-parity", false);
        assertEquals(reportOf(direct.out), reportOf(sequential.out), direct + "\n---\n" + sequential);
    }

    private static void assertContract(Capture capture, String runId, boolean quiet) {
        assertFalse(capture.out.contains("# pkb-console"), capture.toString());
        assertFalse(capture.out.contains("plain-worker-line"), capture.toString());
        assertFalse(capture.err.contains("warm launch exited"), capture.toString());
        assertFalse(capture.err.contains("pkb_workbench_speed=sequential"), capture.toString());
        assertTrue(capture.out.contains("summary status=FAILED"), capture.toString());
        assertFalse(capture.out.contains("summary status=LAUNCH_ERROR"), capture.toString());
        if (quiet) {
            assertFalse(capture.out.contains("console=.pickleball/runs/" + runId + "/console/"), capture.toString());
        } else {
            assertTrue(capture.out.contains("console=.pickleball/runs/" + runId + "/console/"), capture.toString());
        }
        assertEquals(1, capture.exit);
    }

    /** Summary plus the console pointer. Pre-launch echoes are not the report. */
    private static String reportOf(String stdout) {
        StringBuilder report = new StringBuilder();
        for (String line : stdout.split("\\R")) {
            if (line.startsWith("summary ") || line.startsWith("console=")) {
                report.append(line).append('\n');
            }
        }
        return report.toString();
    }

    private Capture discover(Path project, String runId, boolean quiet, WorkbenchAgentCommands.MavenRunner maven) throws Exception {
        if (!Files.isRegularFile(project.resolve("pom.xml"))) writeProject(project);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String[] args = quiet
                ? new String[]{"discover", project.toString(), "--quiet", "--run-id=" + runId, "--tags=@smoke"}
                : new String[]{"discover", project.toString(), "--run-id=" + runId, "--tags=@smoke"};
        int exit = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                maven
        );
        return new Capture(
                exit,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static void writeProject(Path project) throws Exception {
        Files.createDirectories(project.resolve("src/test/resources/features"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = project.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
        Files.writeString(project.resolve("pom.xml"), "<project><artifactId>contract</artifactId></project>\n");
        Files.writeString(project.resolve("src/test/resources/features/one.feature"), """
                @smoke
                Feature: One
                  Scenario: The one
                    Given one
                """);
    }

    private static void writeFailedIndex(Path project, String runId) {
        try {
            Path index = AgentCoordination.runDirectory(project, runId).resolve("diagnostic").resolve("run-index.json");
            Files.createDirectories(index.getParent());
            Files.writeString(index, FAILED_INDEX.formatted(runId));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Capture(int exit, String out, String err) {
        @Override
        public String toString() {
            return "exit=" + exit + "\nSTDOUT:\n" + out + "\nSTDERR:\n" + err;
        }
    }

    /** Speaks the warm-JVM RUN protocol and records an ordinary failed run. */
    private static final class WarmFake implements AutoCloseable {
        private final Process sleeper;
        private final ServerSocket server;
        private final Thread thread;

        private WarmFake(Process sleeper, ServerSocket server, Thread thread) {
            this.sleeper = sleeper;
            this.server = server;
            this.thread = thread;
        }

        static WarmFake open(Path project) throws Exception {
            writeProject(project);
            Path classes = project.resolve("target/test-classes");
            Files.createDirectories(classes);
            ClasspathCache.store(
                    PickleballLocalLayout.workbenchStateRoot(project),
                    InputFingerprint.lockHash(project),
                    List.of(classes.toString())
            );
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Process sleeper = new ProcessBuilder("sleep", "60").start();
            WarmProcess.write(
                    PickleballLocalLayout.workbenchStateRoot(project),
                    new WarmProcess.Handle(sleeper.pid(), server.getLocalPort(), InputFingerprint.watchHash(project))
            );
            Thread thread = new Thread(() -> serve(server), "warm-fake");
            thread.setDaemon(true);
            thread.start();
            return new WarmFake(sleeper, server, thread);
        }

        private static void serve(ServerSocket server) {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    if (!handle(socket)) continue;
                } catch (Exception failure) {
                    if (server.isClosed()) return;
                }
            }
        }

        /** True when this connection was a RUN. A probe that is not a RUN is ignored. */
        private static boolean handle(Socket socket) throws Exception {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String verb = reader.readLine();
            if (!"RUN".equals(verb)) return false;
            String runId = null;
            String line;
            while ((line = reader.readLine()) != null && !".".equals(line)) {
                String prefix = "-Dpkb_run_id=";
                if (line.startsWith(prefix)) runId = line.substring(prefix.length());
            }
            String project = reader.readLine();
            reader.readLine();
            if (project != null && runId != null && !runId.isBlank()) {
                writeFailedIndex(Path.of(project), runId);
            }
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("EXIT 1");
            writer.println(MARKER_LINE);
            writer.println("plain-worker-line");
            writer.println("END");
            return true;
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(2000);
            sleeper.destroyForcibly();
        }
    }
}
