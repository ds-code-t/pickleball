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

    @Test
    void sealedSnapshotReplaysOverrideNotRunProfileOrRunvars() throws Exception {
        Path file = LastDiscoverSnapshot.file(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "source": "workbench-sealed",
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_glue=com.example, pkb_parallel=12",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_glue": "com.example",
                    "pkb_parallel": "12"
                  },
                  "sealed": true
                }
                """);

        Map<String, String> properties = LastDiscoverSnapshot.workerSystemProperties(
                tempDir, "@broken", "Failing scenario"
        );

        assertEquals(1, properties.size());
        assertTrue(properties.containsKey("pkb_overriderunvars"));
        assertFalse(properties.containsKey("pkb_runvars"));
        String compact = properties.get("pkb_overriderunvars");
        assertTrue(compact.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(compact.contains("pkb_parallel=1"));
        assertTrue(compact.contains("pkb_tags=@broken"));
        assertFalse(compact.contains("pkb_run_profile="));
    }

    @Test
    void absentSealedFlagStillReplaysRunvars() throws Exception {
        Path file = LastDiscoverSnapshot.file(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "source": "workbench-discover",
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_glue=com.example",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_glue": "com.example"
                  }
                }
                """);

        Map<String, String> properties = LastDiscoverSnapshot.workerSystemPropertiesIfPresent(tempDir);
        assertTrue(properties.containsKey("pkb_runvars"));
        assertFalse(properties.containsKey("pkb_overriderunvars"));
    }

    @Test
    void writeSealedMarksSnapshotForOverrideReplay() {
        LastDiscoverSnapshot.writeSealed(tempDir, Map.of(
                "pkb_glue", "com.example",
                "pkb_features", "classpath:features",
                "pkb_browser", "firefox"
        ));
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(tempDir);
        assertTrue(snapshot.sealed());
        Map<String, String> properties = LastDiscoverSnapshot.workerSystemPropertiesIfPresent(tempDir);
        assertTrue(properties.containsKey("pkb_overriderunvars"));
        assertFalse(properties.containsKey("pkb_runvars"));
        assertTrue(properties.get("pkb_overriderunvars").contains("pkb_browser=firefox"));
    }
}
