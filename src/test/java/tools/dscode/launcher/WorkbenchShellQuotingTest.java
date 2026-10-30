package tools.dscode.launcher;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchShellQuotingTest {
    @Test
    void powershellQuotingKeepsWindowsPathsApostrophesAndNonAscii() throws Exception {
        List<String> arguments = List.of(
                "C:\\Users\\Dan\\My Projects\\caf\u00e9.feature",
                "Bob's \"QA\"",
                "--tags=@a and @b",
                "wd-file:C:\\Program Files\\pickleball\\\u65e5"
        );
        for (String argument : arguments) {
            String quoted = WorkbenchShellQuoting.powershellLiteral(argument);
            assertTrue(quoted.startsWith("'") && quoted.endsWith("'"), quoted);
            assertEquals(argument, WorkbenchShellQuoting.parsePowershellLiteral(quoted));
        }
        List<String> command = WorkbenchShellQuoting.powershellEcho(arguments);
        assertEquals("-Command", command.get(3));
        String script = command.get(4);
        assertTrue(script.contains("'C:\\Users\\Dan\\My Projects\\caf\u00e9.feature'"), script);
        assertTrue(script.contains("'Bob''s \"QA\"'"), script);
        assertTrue(script.contains("\u65e5"), script);

        Assumptions.assumeTrue(pwshAvailable(), "pwsh is not on PATH");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LANG", "C.UTF-8");
        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "pwsh did not exit");
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), err);
        List<String> lines = out.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        assertEquals(arguments, lines, out);
    }

    private static boolean pwshAvailable() {
        try {
            Process process = new ProcessBuilder("pwsh", "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
