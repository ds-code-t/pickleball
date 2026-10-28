package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;
import tools.dscode.testengine.WorkspaceSeed;
import tools.dscode.testengine.fixtures.WorkspaceSeedHooked;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDiscoverPlannerTest {
    @TempDir
    Path tempDir;

    @Test
    void seedKeepsANamedBrowserAndDoesNotStampEstimatedParallel() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources.resolve("configs"));
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=SAUCE_CHROME\n");
        Files.writeString(resources.resolve("configs/GRID_CHROME.yaml"), "unused: true\n");
        System.setProperty(WorkspaceSeed.SEED_PROPERTY, "true");
        try {
            WorkspaceSeedHooked runner = new WorkspaceSeedHooked();
            runnerValues(runner).put(PKB_props.PKB_BROWSER, "SAUCE_CHROME");
            Path workspace = tempDir.resolve("workspace");
            WorkspaceSeed.writeCaptured(workspace, runner, false);
            Map<String, String> captured = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
            assertEquals("SAUCE_CHROME", captured.get(PKB_props.PKB_BROWSER));
            assertEquals("auto", captured.get(PKB_props.PKB_PARALLEL));
            assertFalse(Integer.toString(ParallelCountEstimator.estimate()).equals(captured.get(PKB_props.PKB_PARALLEL)));
            assertFalse("80".equals(captured.get(PKB_props.PKB_PARALLEL)));
            assertFalse(captured.containsValue("GRID_CHROME"));
            assertEquals("SAUCE_CHROME", WorkspaceSeed.namedProjectBrowser(tempDir));
            assertEquals("SAUCE_CHROME", WorkspaceSeed.browserOrHeadless(" SAUCE_CHROME "));
            assertEquals("CHROME_HEADLESS", WorkspaceSeed.browserOrHeadless(""));
            assertEquals("CHROME_HEADLESS", WorkspaceSeed.browserOrHeadless("   "));
        } finally {
            System.clearProperty(WorkspaceSeed.SEED_PROPERTY);
            clearRunner();
        }
    }

    @Test
    void workspaceEditReplacesRetentionWithoutBuildingDiscoverRunVars() throws Exception {
        Path workspace = tools.dscode.control.protocol.Workspace.directory(tempDir, "run-1");
        tools.dscode.control.protocol.Workspace.writeRunVars(workspace, Map.of(
                PKB_props.PKB_BROWSER, "chrome",
                PKB_props.PKB_PARALLEL, "auto",
                PKB_props.PKB_REPORT_RETENTION, "failed"
        ));
        tools.dscode.control.protocol.Workspace.applyLaunchEdits(
                workspace, new LinkedHashMap<>(), List.of("pkb_reportretention=all"), new StringBuilder()
        );
        Map<String, String> all = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
        assertEquals("all", all.get(PKB_props.PKB_REPORT_RETENTION));
        assertFalse(all.containsKey("pkb_runvars"));
        assertEquals("auto", all.get(PKB_props.PKB_PARALLEL));
        assertEquals("chrome", all.get(PKB_props.PKB_BROWSER));
        tools.dscode.control.protocol.Workspace.applyLaunchEdits(
                workspace, new LinkedHashMap<>(), List.of("pkb_reportretention=none"), new StringBuilder()
        );
        Map<String, String> none = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
        assertEquals("none", none.get(PKB_props.PKB_REPORT_RETENTION));
        assertFalse("failed".equals(none.get(PKB_props.PKB_REPORT_RETENTION)));
        assertEquals("auto", none.get(PKB_props.PKB_PARALLEL));
    }

    @Test
    void isolateDoesNotForceParallelAndKeepsTheWorkspaceBrowser() throws Exception {
        Path workspace = tools.dscode.control.protocol.Workspace.directory(tempDir, "run-1");
        Map<String, String> retained = new LinkedHashMap<>();
        retained.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        retained.put(PKB_props.PKB_PARALLEL, "12");
        retained.put(PKB_props.PKB_GLUE, "com.example.pickleball");
        retained.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
        tools.dscode.control.protocol.Workspace.writeRunVars(workspace, retained);

        Map<String, String> selection = new LinkedHashMap<>();
        selection.put(PKB_props.PKB_TAGS, "@failing");
        selection.put(PKB_props.PKB_NAME, "Broken scenario");
        Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(workspace, selection);
        Map<String, String> stored = tools.dscode.control.protocol.Workspace.readRunVars(workspace);

        assertEquals("CHROME_HEADLESS", stored.get(PKB_props.PKB_BROWSER));
        assertEquals("12", stored.get(PKB_props.PKB_PARALLEL));
        assertEquals("com.example.pickleball", stored.get(PKB_props.PKB_GLUE));
        assertEquals("@failing", launch.get(PKB_props.PKB_TAGS));
        assertEquals("Broken scenario", launch.get(PKB_props.PKB_NAME));
        assertFalse(launch.containsKey(PKB_props.PKB_PARALLEL));
        assertFalse(launch.containsKey("pkb_runvars"));
        assertFalse(launch.containsKey("pkb_run_profile"));
    }

    @Test
    void selectionExampleStaysOnTheLaunchAndParallelStaysInTheWorkspace() throws Exception {
        Path workspace = tools.dscode.control.protocol.Workspace.directory(tempDir, "run-1");
        tools.dscode.control.protocol.Workspace.writeRunVars(workspace, Map.of(
                PKB_props.PKB_BROWSER, "CHROME_HEADLESS",
                PKB_props.PKB_PARALLEL, "12",
                PKB_props.PKB_GLUE, "com.example.pickleball"
        ));
        Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(
                workspace,
                Map.of(PKB_props.PKB_EXAMPLE, "1 2 5 3.4 7-11", PKB_props.PKB_TAGS, "@failing")
        );

        assertEquals("1 2 5 3.4 7-11", launch.get(PKB_props.PKB_EXAMPLE));
        assertEquals("@failing", launch.get(PKB_props.PKB_TAGS));
        assertFalse(tools.dscode.control.protocol.Workspace.readRunVars(workspace).containsKey(PKB_props.PKB_EXAMPLE));
        assertFalse(tools.dscode.control.protocol.Workspace.readRunVars(workspace).containsKey("pkb_runvars"));
        assertEquals("12", tools.dscode.control.protocol.Workspace.readRunVars(workspace).get(PKB_props.PKB_PARALLEL));
        assertFalse(launch.containsKey(PKB_props.PKB_PARALLEL));
    }

    @Test
    void workspaceKeepsRetentionUnlessAnEditReplacesIt() throws Exception {
        Path workspace = tools.dscode.control.protocol.Workspace.directory(tempDir, "run-1");
        Map<String, String> retained = new LinkedHashMap<>();
        retained.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        retained.put(PKB_props.PKB_PARALLEL, "12");
        retained.put(PKB_props.PKB_REPORT_RETENTION, "all");
        tools.dscode.control.protocol.Workspace.writeRunVars(workspace, retained);

        assertEquals("all", tools.dscode.control.protocol.Workspace.readRunVars(workspace).get(PKB_props.PKB_REPORT_RETENTION));
        assertEquals("12", tools.dscode.control.protocol.Workspace.readRunVars(workspace).get(PKB_props.PKB_PARALLEL));
        tools.dscode.control.protocol.Workspace.applyLaunchEdits(
                workspace, new LinkedHashMap<>(), List.of("pkb_reportretention=none"), new StringBuilder()
        );
        Map<String, String> stored = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
        assertEquals("none", stored.get(PKB_props.PKB_REPORT_RETENTION));
        assertEquals("12", stored.get(PKB_props.PKB_PARALLEL));
    }

    @Test
    void confirmSelectionOverlaysTagsAndKeepsTheWorkspaceParallel() throws Exception {
        Path workspace = tools.dscode.control.protocol.Workspace.directory(tempDir, "run-1");
        tools.dscode.control.protocol.Workspace.writeRunVars(workspace, Map.of(
                PKB_props.PKB_BROWSER, "GRID_CHROME",
                PKB_props.PKB_PARALLEL, "8",
                PKB_props.PKB_REPORTING_MODE, "diagnostic",
                PKB_props.PKB_REPORT_RETENTION, "all"
        ));
        Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(
                workspace, Map.of(PKB_props.PKB_TAGS, "@one")
        );
        Map<String, String> stored = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
        assertEquals("GRID_CHROME", stored.get(PKB_props.PKB_BROWSER));
        assertEquals("8", stored.get(PKB_props.PKB_PARALLEL));
        assertEquals("all", stored.get(PKB_props.PKB_REPORT_RETENTION));
        assertEquals("@one", launch.get(PKB_props.PKB_TAGS));
        assertFalse(launch.containsKey("pkb_runvars"));
    }

    @Test
    void missingSnapshotFailsClearly() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LastDiscoverSnapshot.require(tempDir)
        );
        assertTrue(failure.getMessage().contains("No prior Discover snapshot"));
        assertFalse(failure.getMessage().toLowerCase().contains("mcp"));
    }

    private static void clearRunner() throws Exception {
        Field instance = PickleballRunner.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> runnerValues(PickleballRunner runner) throws Exception {
        Field values = PickleballRunner.class.getDeclaredField("values");
        values.setAccessible(true);
        return (Map<String, String>) values.get(runner);
    }
}
