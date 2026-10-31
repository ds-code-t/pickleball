package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.DynamicSuiteBootstrap;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dan's approved follow-ups to the 2.1.17 report (2.1.18). */
class Release218RecommendationsTest {

    @Test
    void missingRetainedProfileWarnsAndKeepsTheMavenExit(@TempDir Path project) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                0, "discover", false, "run-x", null);
        assertEquals(0, exit);
        String idle = err.toString(StandardCharsets.UTF_8);
        assertFalse(idle.contains(WorkbenchAgentCommands.NO_RETAINED_PROFILE_WARNING), idle);

        ByteArrayOutputStream failedErr = new ByteArrayOutputStream();
        int failed = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(failedErr, true, StandardCharsets.UTF_8),
                3, "discover", false, "run-x", null);
        assertEquals(3, failed);
        assertFalse(failedErr.toString(StandardCharsets.UTF_8).contains(WorkbenchAgentCommands.NO_RETAINED_PROFILE_WARNING));

        Path scenario = project.resolve(".pickleball").resolve("runs").resolve("run-x")
                .resolve("diagnostic").resolve("scenarios").resolve("one");
        Files.createDirectories(scenario);
        Files.writeString(scenario.resolve("summary.json"), "{\"outcome\":\"PASSED\"}\n");
        ByteArrayOutputStream ranErr = new ByteArrayOutputStream();
        int ran = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(ranErr, true, StandardCharsets.UTF_8),
                0, "discover", false, "run-x", null);
        assertEquals(0, ran);
        String text = ranErr.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("WARNING: Workbench finished but run-catalog.json has no retained pkb_run_profile."), text);
        assertTrue(text.contains("pkb_reportingmode=diagnostic"), text);

        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_GLUE, "com.example");
        values.put(PKB_props.PKB_FEATURES, "features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
        Workspace.writeRunVars(Workspace.directory(project, "run-x"), values);
        ByteArrayOutputStream diagnosticErr = new ByteArrayOutputStream();
        int diagnostic = WorkbenchAgentCommands.printCatalog(project,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(diagnosticErr, true, StandardCharsets.UTF_8),
                0, "discover", false, "run-x", null);
        assertEquals(0, diagnostic);
        assertFalse(diagnosticErr.toString(StandardCharsets.UTF_8).contains(WorkbenchAgentCommands.NO_RETAINED_PROFILE_WARNING));
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
