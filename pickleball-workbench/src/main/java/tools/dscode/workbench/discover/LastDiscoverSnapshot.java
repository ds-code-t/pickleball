package tools.dscode.workbench.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the last Workbench Discover snapshot so isolate and workers can find
 * that run's workspace. Launch arguments are only {@code pkb_workspace} and
 * selection keys. There is no compact replay and no sealed override.
 */
public final class LastDiscoverSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String MISSING_SNAPSHOT_MESSAGE =
            "Workbench CLI isolate failed: no prior Discover snapshot. "
                    + "Run Workbench discover first. Isolate will not silently re-resolve from project defaults.";

    private LastDiscoverSnapshot() {
    }

    public record Snapshot(String runId, String runProfile, Map<String, String> runVars) {
        public Snapshot {
            runVars = runVars == null ? Map.of() : Map.copyOf(runVars);
        }

        public boolean present() {
            return (runVars != null && !runVars.isEmpty())
                    || (runProfile != null && !runProfile.isBlank());
        }
    }

    public static Path file(Path projectRoot) {
        return PickleballLocalLayout.lastDiscoverSnapshot(projectRoot);
    }

    public static Snapshot read(Path projectRoot) {
        Path file = file(projectRoot);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode root = JSON.readTree(file.toFile());
            LinkedHashMap<String, String> runVars = new LinkedHashMap<>();
            JsonNode vars = root.get("runVars");
            if (vars != null && vars.isObject()) {
                vars.fields().forEachRemaining(entry ->
                        runVars.put(entry.getKey(), entry.getValue().isNull() ? "" : entry.getValue().asText())
                );
            }
            return new Snapshot(
                    text(root, "runId"),
                    text(root, "runProfile"),
                    runVars
            );
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Discover snapshot: " + file, failure);
        }
    }

    public static Snapshot require(Path projectRoot) {
        Snapshot snapshot = read(projectRoot);
        if (snapshot == null || !snapshot.present()) {
            throw new IllegalStateException(MISSING_SNAPSHOT_MESSAGE);
        }
        return snapshot;
    }

    public static Map<String, String> workerSystemProperties(Path projectRoot, String tags, String name) {
        return workerSystemProperties(projectRoot, tags, name, null);
    }

    public static Map<String, String> workerSystemProperties(
            Path projectRoot,
            String tags,
            String name,
            String example
    ) {
        return workerSystemProperties(projectRoot, tags, name, example, null, null);
    }

    public static Map<String, String> workerSystemProperties(
            Path projectRoot,
            String tags,
            String name,
            String example,
            List<String> cliRunVars,
            PrintStream err
    ) {
        Snapshot snapshot = require(projectRoot);
        if (snapshot.runId() == null || snapshot.runId().isBlank()) {
            throw new IllegalStateException(
                    "Discover snapshot has no run id, so there is no workspace to launch.");
        }
        Path workspace = Workspace.directory(projectRoot, snapshot.runId());
        Map<String, String> selection = new LinkedHashMap<>();
        putSelection(selection, "pkb_tags", tags);
        putSelection(selection, "pkb_name", name);
        putSelection(selection, "pkb_example", example);
        Workspace.applyLaunchEdits(workspace, selection, cliRunVars, err);
        return Workspace.launchProperties(workspace, selection);
    }

    public static Map<String, String> workerSystemPropertiesIfPresent(Path projectRoot) {
        Snapshot snapshot = read(projectRoot);
        if (snapshot == null || !snapshot.present() || snapshot.runId() == null || snapshot.runId().isBlank()) {
            return Map.of();
        }
        return Workspace.launchProperties(Workspace.directory(projectRoot, snapshot.runId()), Map.of());
    }

    private static void putSelection(Map<String, String> selection, String key, String value) {
        if (value != null && !value.isBlank()) selection.put(key, value.trim());
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }
}
