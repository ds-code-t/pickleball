package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import tools.dscode.testengine.PKB_props;

import java.nio.file.Files;
import java.nio.file.Path;

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

        String[] isolateName = PickleballWorkbenchLauncher.normalizedArguments(
                new String[]{"isolate", "--name=The", "failing", "scenario"}
        );
        assertEquals("The failing scenario", isolateName[3]);
    }

    @Test
    void isolateDoesNotForceParallelOrReinheritDefaults() throws Exception {
        Path project = Files.createTempDirectory("pkb-isolate-workspace-");
        try {
            Path workspace = tools.dscode.control.protocol.Workspace.directory(project, "run-1");
            java.util.LinkedHashMap<String, String> retained = new java.util.LinkedHashMap<>();
            retained.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
            retained.put(PKB_props.PKB_PARALLEL, "10");
            retained.put(PKB_props.PKB_GLUE, "com.example.pickleball");
            retained.put(PKB_props.PKB_REPORTING_MODE, "diagnostic");
            tools.dscode.control.protocol.Workspace.writeRunVars(workspace, retained);

            java.util.Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(
                    workspace,
                    java.util.Map.of(PKB_props.PKB_EXAMPLE, "1 2 5")
            );
            java.util.Map<String, String> stored = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
            assertEquals("CHROME_HEADLESS", stored.get(PKB_props.PKB_BROWSER));
            assertEquals("10", stored.get(PKB_props.PKB_PARALLEL));
            assertEquals("com.example.pickleball", stored.get(PKB_props.PKB_GLUE));
            assertEquals("1 2 5", launch.get(PKB_props.PKB_EXAMPLE));
            assertFalse(launch.containsKey(PKB_props.PKB_PARALLEL));
            assertFalse(launch.containsKey("pkb_runvars"));
            assertFalse(launch.containsKey("pkb_run_profile"));

            String[] forwarded = PickleballWorkbenchLauncher.normalizedArguments(
                    new String[]{"isolate", "--example=1", "2", "5"}
            );
            assertEquals("--example", forwarded[2]);
            assertEquals("1 2 5", forwarded[3]);
        } finally {
            deleteTree(project);
        }
    }

    @Test
    void confirmKeepsWorkspaceParallelAndPassesSelection() throws Exception {
        Path project = Files.createTempDirectory("pkb-confirm-workspace-");
        try {
            Path workspace = tools.dscode.control.protocol.Workspace.directory(project, "run-1");
            tools.dscode.control.protocol.Workspace.writeRunVars(workspace, java.util.Map.of(
                    PKB_props.PKB_BROWSER, "GRID_CHROME",
                    PKB_props.PKB_PARALLEL, "6"
            ));
            java.util.Map<String, String> launch = tools.dscode.control.protocol.Workspace.launchProperties(
                    workspace,
                    java.util.Map.of(PKB_props.PKB_TAGS, "@one", PKB_props.PKB_EXAMPLE, "1.1")
            );
            java.util.Map<String, String> stored = tools.dscode.control.protocol.Workspace.readRunVars(workspace);
            assertEquals("GRID_CHROME", stored.get(PKB_props.PKB_BROWSER));
            assertEquals("6", stored.get(PKB_props.PKB_PARALLEL));
            assertEquals("@one", launch.get(PKB_props.PKB_TAGS));
            assertEquals("1.1", launch.get(PKB_props.PKB_EXAMPLE));
        } finally {
            deleteTree(project);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
