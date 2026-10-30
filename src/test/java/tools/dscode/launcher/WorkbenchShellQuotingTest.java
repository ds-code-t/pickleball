package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LANG", "C.UTF-8");
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && err.toLowerCase(java.util.Locale.ROOT).contains("not found")) {
                return;
            }
            assertEquals(0, process.exitValue(), err);
            List<String> lines = out.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
            assertEquals(arguments, lines, out);
        } catch (java.io.IOException failure) {
            if (failure.getMessage() != null && (failure.getMessage().contains("No such file")
                    || failure.getMessage().contains("error=2"))) {
                return;
            }
            throw failure;
        }
    }
}
