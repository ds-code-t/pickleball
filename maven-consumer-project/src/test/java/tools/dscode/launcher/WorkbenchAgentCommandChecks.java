package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import tools.dscode.common.reporting.diagnostic.AgentDiscoverPlanner;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class WorkbenchAgentCommandChecks {
    @Test
    void launcherNormalizesAgentAndHostCommands() {
        String[] ui = PickleballWorkbenchLauncher.normalizedArguments(new String[0]);
        String[] mcp = PickleballWorkbenchLauncher.normalizedArguments(new String[]{"mcp"});
        String[] isolate = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"isolate", "--tags=@one"}
        );
        String[] exportGuidance = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"export-guidance", ".pickleball"}
        );

        assertEquals("ui", ui[0]);
        assertEquals("mcp", mcp[0]);
        assertEquals("isolate", isolate[0]);
        assertEquals("--tags", isolate[2]);
        assertEquals("@one", isolate[3]);
        assertEquals("export-guidance", exportGuidance[0]);
        assertEquals(".pickleball", exportGuidance[1]);
    }

    @Test
    void isolateReplayDoesNotReinheritOptionalDefaults() {
        LinkedHashMap<String, String> retained = new LinkedHashMap<>();
        retained.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
        retained.put(PKB_props.PKB_PARALLEL, "10");
        retained.put(PKB_props.PKB_GLUE, "com.example.pickleball");
        retained.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");

        String isolate = AgentDiscoverPlanner.isolateRunVars(retained, "@broken", null);
        assertTrue(isolate.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(isolate.contains("pkb_parallel=1"));
        assertTrue(isolate.contains("pkb_glue=com.example.pickleball"));
        assertFalse(isolate.contains("pkb_run_profile="));
        assertFalse(isolate.contains("@all"));
        assertFalse(isolate.contains("pretty"));
    }

    @Test
    void confirmReplaysSnapshot() {
        Map<String, String> retained = Map.of(
                PKB_props.PKB_BROWSER, "GRID_CHROME",
                PKB_props.PKB_PARALLEL, "6"
        );
        String confirm = AgentDiscoverPlanner.confirmRunVars(retained, "@one", null);
        assertTrue(confirm.contains("pkb_browser=GRID_CHROME"));
        assertTrue(confirm.contains("pkb_parallel=6"));
        assertTrue(confirm.contains("pkb_tags=@one"));
    }
}
