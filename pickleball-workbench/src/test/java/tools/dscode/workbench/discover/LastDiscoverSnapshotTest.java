package tools.dscode.workbench.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastDiscoverSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void missingSnapshotFailsWithoutSuggestingIdeMcp() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> LastDiscoverSnapshot.require(tempDir)
        );
        assertTrue(failure.getMessage().contains("Workbench CLI isolate failed"));
        assertFalse(failure.getMessage().toLowerCase().contains("register"));
    }

    @Test
    void isolateReplayUsesRetainedRunVarsAndForcesParallelOne() throws Exception {
        Path file = LastDiscoverSnapshot.file(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "source": "workbench-discover",
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_glue=com.example, pkb_parallel=12, pkb_plugins=, pkb_reportingmode=diagnostic",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_glue": "com.example",
                    "pkb_parallel": "12",
                    "pkb_plugins": "",
                    "pkb_reportingmode": "diagnostic"
                  }
                }
                """);

        Map<String, String> properties = LastDiscoverSnapshot.workerSystemProperties(
                tempDir, "@broken", "Failing scenario"
        );

        assertEquals(1, properties.size());
        String runVars = properties.get("pkb_runvars");
        assertTrue(runVars.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(runVars.contains("pkb_glue=com.example"));
        assertTrue(runVars.contains("pkb_parallel=1"));
        assertTrue(runVars.contains("pkb_tags=@broken"));
        assertFalse(runVars.contains("pkb_parallel=12"));
        assertFalse(runVars.contains("pkb_run_profile="));
        assertFalse(runVars.contains("pretty"));
        assertFalse(runVars.contains("@all"));
    }
}
