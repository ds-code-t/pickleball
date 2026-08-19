package tools.dscode.workbench;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchApplicationTest {

    @Test
    void helpIsAvailableWithoutAProject() {
        Output output = run("--help");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("Pickleball Workbench"));
        assertTrue(output.stdout().contains("--version"));
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
    void unimplementedCommandsFailClearly() {
        Output output = run("sync");

        assertEquals(2, output.exitCode());
        assertEquals("", output.stdout());
        assertTrue(output.stderr().contains("not available in the foundation phase: sync"));
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

    private record Output(int exitCode, String stdout, String stderr) {
    }
}
