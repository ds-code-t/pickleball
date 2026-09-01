package tools.dscode.workbench.discover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.ControlProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the last Workbench Discover snapshot and replays it as compact
 * {@code pkb_runvars} for isolate/worker start.
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
        return projectRoot.toAbsolutePath().normalize().resolve(ControlProtocol.LAST_DISCOVER_SNAPSHOT_RELATIVE);
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
        return Map.of("pkb_runvars", replay(require(projectRoot), true, tags, name));
    }

    public static Map<String, String> workerSystemPropertiesIfPresent(Path projectRoot) {
        Snapshot snapshot = read(projectRoot);
        if (snapshot == null || !snapshot.present()) return Map.of();
        return Map.of("pkb_runvars", replay(snapshot, true, null, null));
    }

    public static String replay(Snapshot snapshot, boolean isolate, String tags, String name) {
        Map<String, String> values = new TreeMap<>();
        if (snapshot.runVars() != null) values.putAll(snapshot.runVars());
        if (values.isEmpty() && snapshot.runProfile() != null && !snapshot.runProfile().isBlank()) {
            values.putAll(parseCompact(snapshot.runProfile()));
        }
        values.remove("pkb_run_profile");
        if (isolate) values.put("pkb_parallel", "1");
        if (tags != null && !tags.isBlank()) values.put("pkb_tags", tags.trim());
        if (name != null && !name.isBlank()) values.put("pkb_name", name.trim());
        return serializeCompact(values);
    }

    static Map<String, String> parseCompact(String compact) {
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
        if (value.length() < 2) return value == null ? "" : value;
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) return value;
        return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf(';') >= 0 || value.indexOf('"') >= 0
                || !value.equals(value.trim())) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return value;
    }
}
