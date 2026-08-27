package tools.dscode.workbench;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchApplicationTest {

    @Test
    void helpListsSynchronizationWorkerLiveMcpAndUiCommands() {
        Output output = run("--help");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("sync <project>"));
        assertTrue(output.stdout().contains("status <project>"));
        assertTrue(output.stdout().contains("worker-check <project>"));
        assertTrue(output.stdout().contains("live-check <project>"));
        assertTrue(output.stdout().contains("mcp <project>"));
        assertTrue(output.stdout().contains("ui <project>"));
        assertTrue(output.stdout().contains("isolate <project>"));
        assertTrue(output.stdout().contains("PickleballWorkbenchLauncher"));
        assertEquals("", output.stderr());
    }

    @Test
    void helpAliasesPrintTheSameCommandList() {
        Output dashed = run("--help");
        Output shortFlag = run("-h");
        Output word = run("help");

        assertEquals(0, shortFlag.exitCode());
        assertEquals(0, word.exitCode());
        assertEquals(dashed.stdout(), shortFlag.stdout());
        assertEquals(dashed.stdout(), word.stdout());
        assertEquals("", shortFlag.stderr());
        assertEquals("", word.stderr());
    }

    @Test
    void statusRequiresExactlyOneProject() {
        Output output = run("status");

        assertEquals(1, output.exitCode());
        assertEquals("", output.stdout());
        assertTrue(output.stderr().contains("Usage: pickleball-workbench status <project>"));
    }

    @Test
    void developmentVersionIsAvailableFromClasses() {
        Output output = run("--version");

        assertEquals(0, output.exitCode());
        assertEquals("Pickleball Workbench development" + System.lineSeparator(), output.stdout());
        assertEquals("", output.stderr());
    }

    @Test
    void versionAliasesPrintTheSameVersion() {
        Output dashed = run("--version");
        Output shortFlag = run("-V");
        Output word = run("version");

        assertEquals(0, shortFlag.exitCode());
        assertEquals(0, word.exitCode());
        assertEquals(dashed.stdout(), shortFlag.stdout());
        assertEquals(dashed.stdout(), word.stdout());
        assertEquals("", shortFlag.stderr());
        assertEquals("", word.stderr());
    }

    @Test
    void isolateWithoutSnapshotFailsClearlyWithoutSuggestingIdeMcp() {
        Output output = run("isolate", Path.of("").toAbsolutePath().normalize().toString());

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("Workbench CLI isolate failed"));
        assertTrue(output.stderr().contains("no prior Discover snapshot")
                || output.stderr().contains("No prior Discover snapshot"));
        assertFalse(output.stderr().toLowerCase().contains("register mcp"));
        assertFalse(output.stderr().toLowerCase().contains("intellij"));
    }

    @Test
    void isolateRequiresAProject() {
        Output output = run("isolate");

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("Usage: pickleball-workbench isolate <project>"));
    }

    @Test
    void unknownCommandsFailClearly() {
        Output output = run("unknown");

        assertEquals(2, output.exitCode());
        assertEquals("", output.stdout());
        assertTrue(output.stderr().contains("Unknown Workbench command: unknown"));
    }

    @Test
    void commandsRequireExactlyOneProject() {
        Output output = run("live-check");

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("Usage: pickleball-workbench live-check <project>"));
    }

    @Test
    void uiRequiresExactlyOneProjectBeforeLaunchingSwing() {
        Output output = run("ui");

        assertEquals(1, output.exitCode());
        assertEquals("", output.stdout());
        assertTrue(output.stderr().contains("Usage: pickleball-workbench ui <project>"));
    }

    @Test
    void mcpRequiresExactlyOneProjectBeforeTouchingProtocolStreams() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = WorkbenchApplication.runMcpProcess(new String[]{"mcp"}, out, err);
        }

        assertEquals(1, exitCode);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("Usage: pickleball-workbench mcp <project>"));
    }

    private static Output run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = WorkbenchApplication.run(args, out, err);
        }
        return new Output(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private record Output(int exitCode, String stdout, String stderr) { }
}
