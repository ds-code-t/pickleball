package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes the last Workbench Discover snapshot for isolate/confirm replay. */
public final class LastDiscoverSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    public static final String SOURCE = "workbench-discover";

    private LastDiscoverSnapshot() {
    }

    public record Snapshot(
            int schemaVersion,
            String source,
            String runId,
            String catalogPath,
            String runProfile,
            Map<String, String> runVars,
            String createdAt
    ) {
        public Snapshot {
            runVars = runVars == null ? Map.of() : Map.copyOf(runVars);
        }

        public boolean hasRunVars() {
            return runVars != null && !runVars.isEmpty();
        }
    }

    public static Path file(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve(ControlProtocol.LAST_DISCOVER_SNAPSHOT_RELATIVE);
    }

    public static Snapshot read(Path projectRoot) {
        Path file = file(projectRoot);
        if (!Files.isRegularFile(file)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JSON.readValue(file.toFile(), LinkedHashMap.class);
            return fromMap(raw);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Discover snapshot: " + file, failure);
        }
    }

    public static Snapshot require(Path projectRoot) {
        Snapshot snapshot = read(projectRoot);
        if (snapshot == null || !snapshot.hasRunVars() && isBlank(snapshot.runProfile())) {
            throw new IllegalStateException(AgentDiscoverPlanner.missingDiscoverSnapshotMessage());
        }
        return snapshot;
    }

    public static Map<String, String> retainedRunVars(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException(AgentDiscoverPlanner.missingDiscoverSnapshotMessage());
        }
        if (snapshot.hasRunVars()) return new LinkedHashMap<>(snapshot.runVars());
        if (!isBlank(snapshot.runProfile())) {
            return PKB_props.parseAssignments(snapshot.runProfile());
        }
        throw new IllegalStateException(AgentDiscoverPlanner.missingDiscoverSnapshotMessage());
    }

    public static Snapshot write(
            Path projectRoot,
            String runId,
            Path catalogPath,
            String runProfile
    ) throws IOException {
        LinkedHashMap<String, String> runVars = isBlank(runProfile)
                ? new LinkedHashMap<>()
                : PKB_props.parseAssignments(runProfile);
        Snapshot snapshot = new Snapshot(
                1,
                SOURCE,
                runId == null ? "" : runId,
                catalogPath == null ? "" : catalogPath.toString(),
                runProfile == null ? "" : runProfile,
                runVars,
                Instant.now().toString()
        );
        Path file = file(projectRoot);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), snapshotToMap(snapshot));
        return snapshot;
    }

    public static CatalogRun latestCatalogRun(Path projectRoot) throws IOException {
        return latestCatalogRun(projectRoot, null);
    }

    public static CatalogRun latestCatalogRun(Path projectRoot, String purpose) throws IOException {
        Path catalog = projectRoot.toAbsolutePath().normalize()
                .resolve("reports")
                .resolve("diagnostic-runs")
                .resolve("run-catalog.json");
        if (!Files.isRegularFile(catalog)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = JSON.readValue(catalog.toFile(), LinkedHashMap.class);
        Object runsValue = raw.get("runs");
        if (!(runsValue instanceof List<?> runs) || runs.isEmpty()) return null;
        CatalogRun matchedPurpose = null;
        CatalogRun first = null;
        for (Object item : runs) {
            if (!(item instanceof Map<?, ?> run)) continue;
            CatalogRun candidate = new CatalogRun(
                    catalog,
                    text(run.get("runId")),
                    text(run.get("runProfile")),
                    text(run.get("outcome")),
                    lineagePurpose(run.get("lineage"))
            );
            if (first == null) first = candidate;
            if (purpose != null && purpose.equals(candidate.purpose()) && matchedPurpose == null) {
                matchedPurpose = candidate;
            }
        }
        if (matchedPurpose != null) return matchedPurpose;
        return purpose == null ? first : null;
    }

    public record CatalogRun(Path catalog, String runId, String runProfile, String outcome, String purpose) {
        public CatalogRun(Path catalog, String runId, String runProfile, String outcome) {
            this(catalog, runId, runProfile, outcome, "");
        }
    }

    private static String lineagePurpose(Object lineage) {
        if (lineage instanceof Map<?, ?> map) {
            Object purpose = map.get("runPurpose");
            if (purpose == null) purpose = map.get("pkb_run_purpose");
            return text(purpose);
        }
        return "";
    }

    private static Snapshot fromMap(Map<String, Object> raw) {
        LinkedHashMap<String, String> runVars = new LinkedHashMap<>();
        Object vars = raw.get("runVars");
        if (vars instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null) runVars.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
            });
        }
        int schema = 1;
        Object schemaValue = raw.get("schemaVersion");
        if (schemaValue instanceof Number number) schema = number.intValue();
        return new Snapshot(
                schema,
                text(raw.get("source")),
                text(raw.get("runId")),
                text(raw.get("catalogPath")),
                text(raw.get("runProfile")),
                runVars,
                text(raw.get("createdAt"))
        );
    }

    private static Map<String, Object> snapshotToMap(Snapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", snapshot.schemaVersion());
        body.put("source", snapshot.source());
        body.put("runId", snapshot.runId());
        body.put("catalogPath", snapshot.catalogPath());
        body.put("runProfile", snapshot.runProfile());
        body.put("runVars", snapshot.runVars());
        body.put("createdAt", snapshot.createdAt());
        return body;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
