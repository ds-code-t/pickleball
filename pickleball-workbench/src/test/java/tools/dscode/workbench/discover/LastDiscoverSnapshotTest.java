package tools.dscode.workbench.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void isolateLaunchPassesWorkspaceAndSelectionWithoutForcingParallel() throws Exception {
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
        Path workspace = Workspace.directory(tempDir, "run-1");
        Workspace.writeRunVars(workspace, Map.of(
                "pkb_browser", "CHROME_HEADLESS",
                "pkb_glue", "com.example",
                "pkb_parallel", "12"
        ));

        Map<String, String> properties = LastDiscoverSnapshot.workerSystemProperties(
                tempDir, "@broken", "Failing scenario"
        );

        assertEquals(workspace.toAbsolutePath().normalize().toString(), properties.get("pkb_workspace"));
        assertEquals("@broken", properties.get("pkb_tags"));
        assertEquals("Failing scenario", properties.get("pkb_name"));
        assertFalse(properties.containsKey("pkb_parallel"));
        assertFalse(properties.containsKey("pkb_runvars"));
        assertFalse(properties.containsKey("pkb_browser"));
        assertEquals("12", Workspace.readRunVars(workspace).get("pkb_parallel"));
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(workspace).get("pkb_browser"));
    }

    @Test
    void isolateRunVarOverridesEnvironmentAndWarnsOnConflict() throws Exception {
        Path file = LastDiscoverSnapshot.file(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=12",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_parallel": "12"
                  }
                }
                """);
        Path workspace = Workspace.directory(tempDir, "run-1");
        Workspace.writeRunVars(workspace, Map.of(
                "pkb_browser", "CHROME_HEADLESS",
                "pkb_parallel", "12"
        ));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> properties = LastDiscoverSnapshot.workerSystemProperties(
                tempDir,
                null,
                null,
                null,
                List.of("pkb_environment=DEV", "browser=EDGE"),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        Map<String, String> stored = Workspace.readRunVars(workspace);
        assertEquals("DEV", stored.get("pkb_environment"));
        assertEquals("EDGE", stored.get("pkb_browser"));
        assertEquals("12", stored.get("pkb_parallel"));
        assertFalse(properties.containsKey("pkb_parallel"));
        assertFalse(properties.containsKey("pkb_runvars"));
        assertFalse(properties.containsKey("pkb_browser"));
        String warnings = err.toString(StandardCharsets.UTF_8);
        assertTrue(warnings.contains("WARNING: --runvar pkb_browser replaces CHROME_HEADLESS with EDGE"), warnings);
    }

    @Test
    void multiFileFeatureListRoundTripsThroughTheWorkerRunVars() {
        String features = "wd-file:C:/proj/first.feature,wd-file:C:/proj/second.feature";
        String compact = LastDiscoverSnapshot.serializeCompact(Map.of(
                "pkb_features", features,
                "pkb_tags", "@smoke"
        ));
        assertEquals(features, LastDiscoverSnapshot.parseCompact(compact).get("pkb_features"));
        assertEquals("@smoke", LastDiscoverSnapshot.parseCompact(compact).get("pkb_tags"));
    }

    @Test
    void isolateReplayPutsExampleInRunVarsAndKeepsParallelOne() throws Exception {
        Path file = LastDiscoverSnapshot.file(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "source": "workbench-discover",
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=12",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_parallel": "12"
                  }
                }
                """);

        Map<String, String> properties = LastDiscoverSnapshot.workerSystemProperties(
                tempDir, "@broken", "Failing scenario", "1 2 5 3.4 7-11"
        );
        assertEquals("1 2 5 3.4 7-11", properties.get("pkb_example"));
        assertFalse(properties.containsKey("pkb_parallel"));
        assertTrue(properties.containsKey("pkb_workspace"));
        assertFalse(properties.containsKey("pkb_runvars"));

        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "source": "workbench-sealed",
                  "runId": "run-1",
                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=12",
                  "runVars": {
                    "pkb_browser": "CHROME_HEADLESS",
                    "pkb_parallel": "12"
                  },
                  "sealed": true
                }
                """);
        Map<String, String> sealed = LastDiscoverSnapshot.workerSystemProperties(
                tempDir, "@broken", null, "1.1"
        );
        assertTrue(sealed.containsKey("pkb_workspace"));
        assertFalse(sealed.containsKey("pkb_overriderunvars"));
        assertFalse(sealed.containsKey("pkb_runvars"));
        assertEquals("1.1", sealed.get("pkb_example"));
        assertFalse(sealed.containsKey("pkb_parallel"));
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

        assertTrue(properties.containsKey("pkb_workspace"));
        assertEquals("@broken", properties.get("pkb_tags"));
        assertFalse(properties.containsKey("pkb_overriderunvars"));
        assertFalse(properties.containsKey("pkb_runvars"));
        assertFalse(properties.containsKey("pkb_parallel"));
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
        assertTrue(properties.containsKey("pkb_workspace"));
        assertFalse(properties.containsKey("pkb_runvars"));
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
        assertFalse(properties.containsKey("pkb_overriderunvars"));
        assertFalse(properties.containsKey("pkb_runvars"));
        assertTrue(properties.isEmpty());
    }
}
