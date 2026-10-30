package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PickleballRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dan's approved follow-ups to the 2.1.17 report (2.1.18). */
class Release218RecommendationsTest {

    @Test
    void missingRetainedProfileWarnsAndKeepsTheMavenExit(@TempDir Path project) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                0, "discover", false, "run-x", null);
        assertEquals(0, exit);
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("WARNING: Workbench finished but run-catalog.json has no retained pkb_run_profile."), text);
        assertTrue(text.contains("pkb_reportingmode=diagnostic"), text);

        int failed = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
                3, "discover", false, "run-x", null);
        assertEquals(3, failed);
    }

    @Test
    void seedCaptureWarnsWhenItFallsBackToDefaultDynamicSuite() throws Exception {
        Class<?> fallback = Class.forName("tools.dscode.testengine.DynamicSuiteBootstrap$DefaultDynamicSuite");
        assertTrue(DynamicSuiteBootstrap.isDefaultSuite(fallback));
        String warning = WorkspaceLaunch.defaultSuiteWarning(fallback);
        assertNotNull(warning);
        assertTrue(warning.startsWith("WARNING:"), warning);
        assertTrue(warning.contains("DefaultDynamicSuite"), warning);
        assertTrue(warning.contains("mvn test-compile"), warning);
        assertTrue(warning.contains("workspace-seed --reseed"), warning);
        assertNull(WorkspaceLaunch.defaultSuiteWarning(PickleballRunner.EmptyHooks.class));
        assertNull(WorkspaceLaunch.defaultSuiteWarning(null));
    }
}
