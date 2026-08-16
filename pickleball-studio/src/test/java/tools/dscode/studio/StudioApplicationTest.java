package tools.dscode.studio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void statusOpensWorkspace() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"status", tempDir.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, text);
        assertTrue(text.contains("Pickleball Studio foundation ready"), text);
        assertTrue(text.contains("Workspace:"), text);
    }

    @Test
    void invalidServePortReturnsUsageErrorWithoutStartingServer() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"serve", tempDir.toString(), "--port=not-a-number"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        String text = error.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode, text);
        assertTrue(text.startsWith("Invalid Studio MCP port:"), text);
    }

    @Test
    void invalidServeTokenReturnsUsageErrorWithoutStartingServer() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"serve", tempDir.toString(), "--token=bad"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        String text = error.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode, text);
        assertTrue(text.startsWith("Studio MCP token must contain"), text);
    }

    @Test
    void missingWorkspaceReturnsUsageError() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"status", tempDir.resolve("missing").toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        String text = error.toString(StandardCharsets.UTF_8);
        assertEquals(2, exitCode, text);
        assertTrue(text.startsWith("Workspace directory does not exist:"), text);
    }

    @Test
    void execRequiresACommand() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"exec", tempDir.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("studio exec"));
    }

    @Test
    void mavenRequiresArguments() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"maven", tempDir.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("studio maven"));
    }

    @Test
    void gradleRequiresArguments() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"gradle", tempDir.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("studio gradle"));
    }

    @Test
    void gradleModelRejectsNonGradleWorkspace() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = StudioApplication.run(
                new String[]{"gradle-model", tempDir.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("not a Gradle project"));
    }
}
