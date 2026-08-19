package tools.dscode.workbench;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchApplicationTest {

    @Test
    void helpListsSynchronizationWorkerAndLiveCommands() {
        Output output = run("--help");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("sync <project>"));
        assertTrue(output.stdout().contains("worker-check <project>"));
        assertTrue(output.stdout().contains("live-check <project>"));
        assertEquals("", output.stderr());
    }

    @Test
    void developmentVersionIsAvailableFromClasses() {
        Output output = run("--version");

        assertEquals(0, output.exitCode());
        assertEquals("Pickleball Workbench development" + System.lineSeparator(), output.stdout());
        assertEquals("", output.stderr());
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
