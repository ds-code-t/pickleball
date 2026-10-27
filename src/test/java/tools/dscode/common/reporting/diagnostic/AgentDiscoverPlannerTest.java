package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;

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
    void discoverRunVarsUseBrowserLadderAndEstimatedParallel() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=SAUCE_CHROME\n");

        AgentDiscoverPlanner.Plan plan = AgentDiscoverPlanner.discover(tempDir, "@smoke", null);

        assertTrue(plan.runVars().contains("pkb_browser=SAUCE_CHROME"));
        assertTrue(plan.runVars().contains("pkb_parallel=" + ParallelCountEstimator.estimate()));
        assertTrue(plan.runVars().contains("pkb_reportingmode=diagnostic"));
        assertTrue(plan.runVars().contains("pkb_reportretention=failed"));
        assertTrue(plan.runVars().contains("pkb_compositereport=false"));
        assertTrue(plan.runVars().contains("pkb_scenarioreport=false"));
        assertTrue(plan.runVars().contains("pkb_tags=@smoke"));
        assertFalse(plan.runVars().contains("pkb_parallel=80"));
        assertFalse(plan.runVars().contains("pkb_run_profile="));
    }

    @Test
    void discoverRetentionOverlayReplacesTheFailedDefault() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);

        String all = AgentDiscoverPlanner.discover(tempDir, null, null, "all").runVars();
        String none = AgentDiscoverPlanner.discover(tempDir, null, null, "NONE").runVars();

        assertTrue(all.contains("pkb_reportretention=all"));
        assertFalse(all.contains("pkb_reportretention=failed"));
        assertTrue(none.contains("pkb_reportretention=none"));
        assertFalse(none.contains("pkb_reportretention=failed"));
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
        String discover = AgentDiscoverPlanner.discover(
                tempDir, "@smoke", null, null, "1 2 5 3.4 7-11"
        ).runVars();
        Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(
                workspace,
                Map.of(PKB_props.PKB_EXAMPLE, "1 2 5 3.4 7-11", PKB_props.PKB_TAGS, "@failing")
        );

        assertTrue(discover.contains("pkb_example=1 2 5 3.4 7-11"));
        assertTrue(discover.contains("pkb_tags=@smoke"));
        assertEquals("1 2 5 3.4 7-11", launch.get(PKB_props.PKB_EXAMPLE));
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
}
