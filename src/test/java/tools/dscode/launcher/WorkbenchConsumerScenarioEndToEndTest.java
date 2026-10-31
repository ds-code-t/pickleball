package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.SessionDescriptorWriter;
import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real {@code mvnw} in {@code maven-consumer-project}. Not a shell stub.
 * Requires {@code tools.dscode:pickleball:2.1.18} in the local Maven repository.
 */
class WorkbenchConsumerScenarioEndToEndTest {
    private static final Path PROJECT = Path.of("maven-consumer-project").toAbsolutePath().normalize();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void discoverRunsARealScenarioAndRecordsThePack() throws Exception {
        assertPublished();
        syncConsumer();
        String runId = "wb-probe";
        deleteRun(runId);
        try {
            Run discover = launchExec(
                    "discover", PROJECT.toString(),
                    "--tags=@workbench-cli-probe",
                    "--run-id=" + runId,
                    "--runvar=pkb_browser=CHROME_HEADLESS"
            );
            assertEquals(0, discover.exit, discover.text());
            assertEquals("com.example.pickleball",
                    Workspace.readRunVars(Workspace.directory(PROJECT, runId)).get("pkb_glue"));
            assertTrue(discover.out.contains("summary status=PASSED"), discover.text());
            Path run = AgentCoordination.runDirectory(PROJECT, runId);
            String record = Files.readString(run.resolve("record.json"));
            assertTrue(record.contains("PASSED"), record);
            assertTrue(Files.isRegularFile(run.resolve("workbench").resolve("summary.json")), discover.text());
            String summary = Files.readString(run.resolve("workbench").resolve("summary.json"));
            assertTrue(summary.contains("PASSED"), summary);
            Path index = findIndex(runId);
            String indexText = Files.readString(index);
            assertTrue(indexText.contains(runId), index.toString() + "\n" + indexText);
            assertTrue(indexText.contains("PASSED") || indexText.contains("passed"), indexText);

            Run isolate = launchExec("isolate", PROJECT.toString(), "--run-id=" + runId, "--tags=@workbench-cli-probe");
            assertEquals(0, isolate.exit, isolate.text());
            Path descriptor = SessionDescriptorWriter.file(PROJECT, runId);
            assertTrue(Files.isRegularFile(descriptor), isolate.text());
            String descriptorText = Files.readString(descriptor);
            assertTrue(descriptorText.contains("session-descriptor"), descriptorText);
            assertTrue(descriptorText.contains(runId), descriptorText);
            String after = Files.readString(run.resolve("record.json"));
            assertTrue(after.contains("sessionDescriptor"), after);
        } finally {
            try {
                launchExec("stop", PROJECT.toString(), "--run-id=" + runId);
            } catch (Exception ignored) {
                // The session may already be gone.
            }
            deleteRun(runId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void realMavenFailureIgnoreStillFailsTheWorkbench() throws Exception {
        assertPublished();
        syncConsumer();
        String runId = "wb-ignore";
        Path mavenConfig = PROJECT.resolve(".mvn/maven.config");
        byte[] previousConfig = Files.isRegularFile(mavenConfig) ? Files.readAllBytes(mavenConfig) : null;
        deleteRun(runId);
        try {
            Files.createDirectories(mavenConfig.getParent());
            Files.writeString(mavenConfig, "-Dmaven.test.failure.ignore=true\n");
            Run discover = launchExec(
                    "discover", PROJECT.toString(),
                    "--tags=@workbench-cli-fail",
                    "--run-id=" + runId,
                    "--runvar=pkb_browser=CHROME_HEADLESS"
            );
            assertEquals(1, discover.exit, discover.text());
            assertTrue(discover.out.contains("summary status=FAILED"), discover.text());
            assertFalse(discover.out.contains("summary status=PASSED"), discover.text());
            Path timing = AgentCoordination.runDirectory(PROJECT, runId).resolve("launch-timing.txt");
            String timingText = Files.readString(timing);
            assertTrue(timingText.contains("stage=maven-done"), timingText);
            assertTrue(timingText.contains("exit=0"), timingText);
            String record = Files.readString(AgentCoordination.runDirectory(PROJECT, runId).resolve("record.json"));
            assertTrue(record.contains("FAILED"), record);
            assertFalse(record.contains("\"status\" : \"PASSED\"") || record.contains("\"status\": \"PASSED\""), record);
        } finally {
            if (previousConfig == null) Files.deleteIfExists(mavenConfig);
            else Files.write(mavenConfig, previousConfig);
            deleteRun(runId);
        }
    }

    private static void assertPublished() {
        Path published = Path.of(System.getProperty("user.home"),
                ".m2", "repository", "tools", "dscode", "pickleball", "2.1.18", "pickleball-2.1.18.jar");
        assertTrue(Files.isRegularFile(published),
                "publishToMavenLocal tools.dscode:pickleball:2.1.18 before this test: " + published);
        assertTrue(Files.isRegularFile(PROJECT.resolve("mvnw")));
    }

    private static Path findIndex(String runId) throws Exception {
        List<Path> roots = List.of(
                AgentCoordination.runDirectory(PROJECT, runId).resolve("diagnostic"),
                PROJECT.resolve("reports").resolve("diagnostic-runs").resolve(runId)
        );
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> found = walk.filter(path -> path.getFileName() != null
                        && "run-index.json".equals(path.getFileName().toString())).toList();
                if (!found.isEmpty()) return found.getFirst();
            }
        }
        throw new AssertionError("run-index.json was not written for " + runId);
    }

    private static void deleteRun(String runId) throws Exception {
        Path run = PROJECT.resolve(".pickleball").resolve("runs").resolve(runId);
        if (Files.exists(run)) {
            try (Stream<Path> walk = Files.walk(run)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        Path shared = PROJECT.resolve("reports").resolve("diagnostic-runs").resolve(runId);
        if (Files.exists(shared)) {
            try (Stream<Path> walk = Files.walk(shared)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /**
     * {@code sync} writes the consumer test classpath. Discover then seeds the
     * workspace from that classpath, so {@code PickleballTests} hooks are captured.
     */
    private static void syncConsumer() throws Exception {
        Run sync = launchJar("sync", PROJECT.toString());
        assertEquals(0, sync.exit, sync.text());
    }

    private static Run launchExec(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(PROJECT.resolve("mvnw").toString());
        command.add("org.codehaus.mojo:exec-maven-plugin:3.5.0:java");
        command.add("-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher");
        command.add("-Dexec.classpathScope=test");
        command.add("-Dexec.args=" + String.join(" ", args));
        return fork(command);
    }

    private static Run launchJar(String... args) throws Exception {
        Path published = Path.of(System.getProperty("user.home"),
                ".m2", "repository", "tools", "dscode", "pickleball", "2.1.18", "pickleball-2.1.18.jar");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-jar");
        command.add(published.toString());
        command.addAll(List.of(args));
        return fork(command);
    }

    private static Run fork(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(PROJECT.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(12, TimeUnit.MINUTES);
        if (!finished) process.destroyForcibly();
        stdout.join(5_000);
        stderr.join(5_000);
        return new Run(
                finished ? process.exitValue() : -1,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private static Thread pump(java.io.InputStream input, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(target);
            } catch (Exception ignored) {
                // The process ending closes the pipe.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private record Run(int exit, String out, String err) {
        String text() {
            return out + "\n" + err;
        }
    }
}
