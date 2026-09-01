package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.parallelutilities.ParallelCountEstimator;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        assertTrue(plan.runVars().contains("pkb_tags=@smoke"));
        assertFalse(plan.runVars().contains("pkb_parallel=80"));
        assertFalse(plan.runVars().contains("pkb_run_profile="));
    }

    @Test
    void isolateReplaysRetainedProfileWithoutReinheritingDefaults() {
        LinkedHashMap<String, String> retained = new LinkedHashMap<>();
        retained.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        retained.put(PKB_props.PKB_PARALLEL, "12");
        retained.put(PKB_props.PKB_GLUE, "com.example.pickleball");
        retained.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
        retained.put(PKB_props.PKB_PLUGINS, "");

        String isolate = AgentDiscoverPlanner.isolateRunVars(retained, "@failing", "Broken scenario");

        assertTrue(isolate.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(isolate.contains("pkb_parallel=1"));
        assertTrue(isolate.contains("pkb_glue=com.example.pickleball"));
        assertTrue(isolate.contains("pkb_tags=@failing"));
        assertTrue(isolate.contains("pkb_name="));
        assertFalse(isolate.contains("pkb_parallel=12"));
        assertFalse(isolate.contains("pkb_run_profile="));
        assertFalse(isolate.contains("pretty"));
        assertFalse(isolate.contains("@all"));
    }

    @Test
    void confirmReplaysSnapshotAndOverlaysSelection() {
        Map<String, String> retained = Map.of(
                PKB_props.PKB_BROWSER, "GRID_CHROME",
                PKB_props.PKB_PARALLEL, "8",
                PKB_props.PKB_REPORTING_MODE, "diagnostic"
        );
        String confirm = AgentDiscoverPlanner.confirmRunVars(retained, "@one", null);
        assertTrue(confirm.contains("pkb_browser=GRID_CHROME"));
        assertTrue(confirm.contains("pkb_parallel=8"));
        assertTrue(confirm.contains("pkb_tags=@one"));
    }

    @Test
    void missingSnapshotFailsClearly() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> AgentDiscoverPlanner.isolateRunVars(Map.of(), null, null)
        );
        assertTrue(failure.getMessage().contains("No prior Discover snapshot"));
        assertFalse(failure.getMessage().toLowerCase().contains("mcp"));
    }
}
