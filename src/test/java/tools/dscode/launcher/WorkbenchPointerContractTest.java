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
        assertFalse(lowered.contains("only controls"));
        assertTrue(lowered.contains("open-scenario"));
        assertTrue(lowered.contains("mvnw"));
        assertTrue(lowered.contains("machine-wide"));
    }

    @Test
    void agentGuidesDriveAnOpenWindowAndUseTheProjectWrapper() throws Exception {
        List<Path> guides = List.of(
                Path.of("docs/consumer-agent-guide.md"),
                Path.of("docs/ai-run-configuration.md"),
                Path.of("docs/pickleball-workbench.md"),
                Path.of("docs/consumer-project.md"),
                Path.of("AGENTS.md"),
                Path.of("pickleball-workbench/AGENTS.md")
        );
        for (Path guide : guides) {
            String text = Files.readString(guide);
            String lowered = text.toLowerCase(Locale.ROOT);
            assertTrue(lowered.contains("open-scenario"), guide.toString());
            assertTrue(lowered.contains("do not start the gui"), guide.toString());
            assertTrue(lowered.contains("mvnw"), guide.toString());
            assertTrue(lowered.contains("machine-wide"), guide.toString());
            assertTrue(lowered.contains("already open"), guide.toString());
            assertTrue(lowered.contains("only one agent"), guide.toString());
            assertTrue(lowered.contains("own run id"), guide.toString());
            assertTrue(lowered.contains("agent-log"), guide.toString());
            assertTrue(lowered.contains("short log"), guide.toString());
            assertFalse(lowered.contains("only controls"), guide.toString());
        }
        String chooserSource = Files.readString(Path.of("docs/consumer-agent-guide.md"));
        int chooserStart = chooserSource.indexOf("## Tool chooser");
        int liveStart = chooserSource.indexOf("### Live isolation loop");
        String chooser = chooserSource.substring(chooserStart, liveStart);
        assertTrue(chooser.contains("-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher"));
        assertTrue(chooser.contains("-Dexec.args=discover"));
        assertTrue(chooser.contains("-Dexec.args=isolate"));
        assertTrue(chooser.contains("execute-step"));
        assertFalse(chooser.contains("PickleballWorkbenchLauncher discover"));
        assertFalse(chooser.toLowerCase(Locale.ROOT).contains("do not isolate"));
        assertFalse(chooser.contains("attach.json"));
        assertFalse(chooser.contains("ui ."));
    }
}
