package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchPointerContractTest {
    @Test
    void consumerPointersAreShortIdenticalAndNameTheCliSession() throws Exception {
        Path agents = Path.of("maven-consumer-project/AGENTS.md");
        Path copilot = Path.of("maven-consumer-project/.github/copilot-instructions.md");
        String left = Files.readString(agents).strip();
        String right = Files.readString(copilot).strip();
        assertEquals(left, right);

        List<String> nonblank = left.lines().filter(line -> !line.isBlank()).toList();
        assertTrue(nonblank.size() >= 3 && nonblank.size() <= 8, "pointer must be 3-8 nonblank lines");
        String first = nonblank.getFirst();
        assertTrue(first.contains("PickleballWorkbenchLauncher"));
        assertTrue(first.contains("export-guidance"));
        assertTrue(first.contains(".pickleball/AGENT-GUIDE.md"));
        assertTrue(first.contains("classpathScope=test"));

        String lowered = left.toLowerCase(Locale.ROOT);
        assertTrue(lowered.contains("hint"));
        assertTrue(lowered.contains("discover"));
        assertTrue(lowered.contains("confirm"));
        assertTrue(lowered.contains("isolate"));
        assertTrue(lowered.contains("execute-step"));
        assertTrue(lowered.contains("do not start the gui"));
        assertTrue(lowered.contains("exec.args"));

        assertFalse(lowered.contains("register mcp"));
        assertFalse(lowered.contains("ide mcp"));
        assertFalse(lowered.contains("stdio"));
        assertFalse(lowered.contains("intellij"));
        assertFalse(lowered.contains("copilot plugin"));
        assertFalse(lowered.contains("do not isolate"));
        assertFalse(lowered.contains("do not maven-exec isolate"));
        assertFalse(lowered.contains("must") && lowered.contains("chrome_headless"));
    }
}
