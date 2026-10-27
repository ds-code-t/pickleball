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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Reads the last Workbench Discover snapshot and replays it for isolate/worker start.
 * Ordinary snapshots become compact {@code pkb_runvars}. Snapshots marked sealed become
 * compact {@code pkb_overriderunvars}. Never {@code pkb_run_profile}.
 */
public final class LastDiscoverSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String MISSING_SNAPSHOT_MESSAGE =
            "Workbench CLI isolate failed: no prior Discover snapshot. "
                    + "Run Workbench discover first. Isolate will not silently re-resolve from project defaults.";

    private LastDiscoverSnapshot() {
    }

    public record Snapshot(String runId, String runProfile, Map<String, String> runVars, boolean sealed) {
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
                    runVars,
                    root.path("sealed").asBoolean(false)
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
        applyLaunchEdits(workspace, selection, cliRunVars, err);
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

    /**
     * Non-selection {@code --runvar} values are written into the workspace.
     * Selection keys stay on the launch command and do not rewrite the file.
     */
    private static void applyLaunchEdits(
            Path workspace,
            Map<String, String> selection,
            List<String> cliRunVars,
            PrintStream err
    ) {
        if (cliRunVars == null || cliRunVars.isEmpty()) return;
        boolean present = Workspace.exists(workspace);
        Map<String, String> values = present
                ? new LinkedHashMap<>(Workspace.readRunVars(workspace))
                : new LinkedHashMap<>();
        Map<String, String> original = Map.copyOf(values);
        applyCliRunVars(values, cliRunVars, err);
        for (String key : Workspace.SELECTION_KEYS) {
            if (!Objects.equals(original.get(key), values.get(key))) {
                String updated = values.get(key);
                if (updated != null && !updated.isBlank()) selection.put(key, updated);
                if (original.containsKey(key)) values.put(key, original.get(key));
                else values.remove(key);
            }
        }
        if (present) Workspace.writeRunVars(workspace, values);
    }

    public static String replay(Snapshot snapshot, boolean isolate, String tags, String name) {
        return replay(snapshot, isolate, tags, name, null);
    }

    public static String replay(Snapshot snapshot, boolean isolate, String tags, String name, String example) {
        return replay(snapshot, isolate, tags, name, example, null, null);
    }

    public static String replay(
            Snapshot snapshot,
            boolean isolate,
            String tags,
            String name,
            String example,
            List<String> cliRunVars,
            PrintStream err
    ) {
        Map<String, String> values = new TreeMap<>();
        if (snapshot.runVars() != null) values.putAll(snapshot.runVars());
        if (values.isEmpty() && snapshot.runProfile() != null && !snapshot.runProfile().isBlank()) {
            values.putAll(parseCompact(snapshot.runProfile()));
        }
        values.remove("pkb_run_profile");
        if (isolate) values.put("pkb_parallel", "1");
        if (tags != null && !tags.isBlank()) values.put("pkb_tags", tags.trim());
        if (name != null && !name.isBlank()) values.put("pkb_name", name.trim());
        if (example != null && !example.isBlank()) values.put("pkb_example", example.trim());
        applyCliRunVars(values, cliRunVars, err);
        return serializeCompact(values);
    }

    static void applyCliRunVars(Map<String, String> values, List<String> raw, PrintStream err) {
        if (values == null || raw == null) return;
        for (String item : raw) {
            if (item == null || item.isBlank()) continue;
            int equals = item.indexOf('=');
            if (equals <= 0) {
                if (err != null) err.println("WARNING: --runvar ignored, expected key=value: " + item);
                continue;
            }
            String key = item.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = item.substring(equals + 1).trim();
            if (!key.startsWith("pkb_")) key = "pkb_" + key;
            String existing = values.get(key);
            if (existing != null && !existing.equals(value) && err != null) {
                err.println("WARNING: --runvar " + key + " replaces " + existing + " with " + value);
            }
            values.put(key, value);
        }
    }

    public static Snapshot writeSealed(Path projectRoot, Map<String, String> runVars) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (runVars != null) {
            runVars.forEach((key, value) -> {
                if (key == null || key.isBlank()) return;
                if ("pkb_run_profile".equals(key)) return;
                copy.put(key, value == null ? "" : value);
            });
        }
        String runProfile = serializeCompact(copy);
        Snapshot snapshot = new Snapshot("", runProfile, copy, true);
        Path file = file(projectRoot);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schemaVersion", 1);
            body.put("source", "workbench-sealed");
            body.put("runId", "");
            body.put("catalogPath", "");
            body.put("runProfile", runProfile);
            body.put("runVars", copy);
            body.put("createdAt", java.time.Instant.now().toString());
            body.put("sealed", true);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), body);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write sealed Discover snapshot: " + file, failure);
        }
        return snapshot;
    }

    public static Map<String, String> parseCompact(String compact) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (compact == null || compact.isBlank()) return values;
        for (String assignment : compact.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)) {
            String item = assignment.trim();
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String key = item.substring(0, equals).trim();
            String value = unquote(item.substring(equals + 1).trim());
            if (!key.isBlank() && !"pkb_run_profile".equals(key)) values.put(key, value);
        }
        return values;
    }

    static String serializeCompact(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> !"pkb_run_profile".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!out.isEmpty()) out.append(", ");
                    out.append(entry.getKey()).append('=').append(quoteIfNeeded(entry.getValue()));
                });
        return out.toString();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value == null ? "" : value;
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) return value;
        String inner = value.substring(1, value.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int index = 0; index < inner.length(); index++) {
            char ch = inner.charAt(index);
            if (ch == '\\' && index + 1 < inner.length()) {
                char next = inner.charAt(index + 1);
                if (next == quote || next == '\\' || next == ',' || next == ';') {
                    out.append(next);
                    index++;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf(';') >= 0 || value.indexOf('"') >= 0
                || !value.equals(value.trim())) {
            StringBuilder escaped = new StringBuilder(value.length() + 8);
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                if (ch == '\\' || ch == '"' || ch == ',' || ch == ';') escaped.append('\\');
                escaped.append(ch);
            }
            return '"' + escaped.toString() + '"';
        }
        return value;
    }
}
