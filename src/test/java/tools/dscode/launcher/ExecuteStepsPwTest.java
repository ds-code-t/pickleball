package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightConnection;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteStepsPwTest {
    @TempDir
    Path temp;

    @Test
    void pwLineRunsPwCommandAndGherkinStaysOnTheRunner() throws Exception {
        String previous = System.getProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        try {
            Path file = temp.resolve("steps.txt");
            Files.writeString(file, """
                    pw page title
                    Given the pkb-r4 marker sentence has no glue
                    """);
            Capture capture = agent("execute-steps", temp.toString(), "--file=" + file, "--run-id=r4-pw");
            String all = capture.out + capture.err;
            assertTrue(capture.code != 0, all);
            assertTrue(all.contains("\"stopped\":false"), all);
            assertTrue(all.contains("no CDP endpoint for Playwright")
                    || all.contains("Playwright could not attach")
                    || all.contains("pw page title failed"), all);
            assertTrue(all.contains("step is undefined"), all);
            assertTrue(all.contains("pkb-r4 marker sentence has no glue"), all);
            assertFalse(all.contains("pw needs an area"), all);
            assertTrue(page("execute-steps").contains("doctor runs in this process"));
            assertTrue(page("execute-steps").contains("PwCommand"));
            assertTrue(page("net-route").contains("sends the request body"));
        } finally {
            if (previous == null) System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
            else System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, previous);
        }
    }

    @Test
    void executeStepPwDoesNotRequireAGherkinSession() throws Exception {
        String previous = System.getProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        try {
            Path file = temp.resolve("one.txt");
            Files.writeString(file, "pw page title");
            Capture capture = session("execute-step", temp.toString(), "--text-file=" + file, "--run-id=r4-step");
            String all = capture.out + capture.err;
            assertEquals(1, capture.code, all);
            assertTrue(all.contains("no CDP endpoint for Playwright")
                    || all.contains("Playwright could not attach")
                    || all.contains("pw page title failed"), all);
            assertFalse(all.contains("missing gherkin"), all);
            assertFalse(all.contains("Workbench execute-step failed"), all);
            assertFalse(all.contains("step is undefined"), all);
        } finally {
            if (previous == null) System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
            else System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, previous);
        }
    }

    private static String page(String command) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchCliHelp.answer(
                new String[]{"help", command},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, command);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private static Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Capture session(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
