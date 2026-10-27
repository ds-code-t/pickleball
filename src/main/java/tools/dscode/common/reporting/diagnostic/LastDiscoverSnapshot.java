package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.testengine.PKB_props;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes the last Workbench Discover snapshot for isolate/confirm replay.
 * Ordinary Discover snapshots replay as {@code pkb_runvars}. A snapshot marked sealed
 * is for the next worker launch as {@code pkb_overriderunvars}, never {@code pkb_run_profile}.
 */
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
            String createdAt,
            boolean sealed
    ) {
        public Snapshot {
            runVars = runVars == null ? Map.of() : Map.copyOf(runVars);
        }

        public boolean hasRunVars() {
            return runVars != null && !runVars.isEmpty();
        }
    }

    public static Path file(Path projectRoot) {
        return PickleballLocalLayout.lastDiscoverSnapshot(projectRoot);
    }

    public static Snapshot read(Path projectRoot) {
        return readFile(file(projectRoot));
    }

    /**
     * Prefers the agent snapshot, then that run's snapshot, then the shared file.
     * A later agent's discover must not replace an earlier agent's filters.
     */
    public static Snapshot readScoped(Path projectRoot, String agentId, String runId) {
        if (!isBlank(agentId)) {
            Snapshot agent = readFile(PickleballLocalLayout.lastDiscoverSnapshot(projectRoot, "agent-" + agentId));
            if (present(agent)) return agent;
        }
        if (!isBlank(runId)) {
            Snapshot run = readFile(PickleballLocalLayout.lastDiscoverSnapshot(projectRoot, "run-" + runId));
            if (present(run)) return run;
        }
        return read(projectRoot);
    }

    public static Snapshot require(Path projectRoot) {
        return requirePresent(read(projectRoot));
    }

    public static Snapshot requireScoped(Path projectRoot, String agentId, String runId) {
        return requirePresent(readScoped(projectRoot, agentId, runId));
    }

    private static Snapshot requirePresent(Snapshot snapshot) {
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
                Instant.now().toString(),
                false
        );
        Path file = file(projectRoot);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), snapshotToMap(snapshot));
        if (!isBlank(runId)) {
            writeCopy(PickleballLocalLayout.lastDiscoverSnapshot(projectRoot, "run-" + runId), snapshot);
        }
        return snapshot;
    }

    public static Snapshot write(
            Path projectRoot,
            String runId,
            Path catalogPath,
            String runProfile,
            String agentId
    ) throws IOException {
        Snapshot snapshot = write(projectRoot, runId, catalogPath, runProfile);
        if (!isBlank(agentId)) {
            writeCopy(PickleballLocalLayout.lastDiscoverSnapshot(projectRoot, "agent-" + agentId), snapshot);
        }
        return snapshot;
    }

    public static CatalogRun latestCatalogRun(Path projectRoot) throws IOException {
        return latestCatalogRun(projectRoot, null);
    }

    public static CatalogRun latestCatalogRun(Path projectRoot, String purpose) throws IOException {
        return catalogRun(projectRoot, purpose, null);
    }

    /** The current run's private run-index wins over a newer catalog row from another run. */
    public static CatalogRun catalogRun(Path projectRoot, String purpose, String runId) throws IOException {
        CatalogRun scoped = scopedRunIndex(projectRoot, runId);
        if (scoped != null) return scoped;
        List<CatalogRun> runs = catalogRuns(projectRoot);
        CatalogRun byId = null;
        CatalogRun byPurpose = null;
        CatalogRun first = null;
        for (CatalogRun candidate : runs) {
            if (first == null) first = candidate;
            if (!isBlank(runId) && runId.equals(candidate.runId())) byId = candidate;
            if (purpose != null && purpose.equals(candidate.purpose()) && byPurpose == null) byPurpose = candidate;
        }
        if (byId != null) return byId;
        if (byPurpose != null) return byPurpose;
        return purpose == null ? first : null;
    }

    /**
     * Profile and outcome for this run live in its private diagnostic index,
     * then in that same run's shared index. A blank profile does not count.
     */
    private static CatalogRun scopedRunIndex(Path projectRoot, String runId) throws IOException {
        if (projectRoot == null || isBlank(runId)) return null;
        String safeId;
        try {
            safeId = AgentCoordination.requireSafeId(runId, "run id");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        Path project = projectRoot.toAbsolutePath().normalize();
        CatalogRun privateIndex = catalogRunFromIndex(
                AgentCoordination.runDirectory(project, safeId)
                        .resolve("diagnostic")
                        .resolve(safeId)
                        .resolve("run-index.json"),
                safeId
        );
        if (privateIndex != null) return privateIndex;
        return catalogRunFromIndex(
                project.resolve("reports").resolve("diagnostic-runs").resolve(safeId).resolve("run-index.json"),
                safeId
        );
    }

    private static CatalogRun catalogRunFromIndex(Path index, String expectedRunId) throws IOException {
        if (index == null || !Files.isRegularFile(index)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = JSON.readValue(index.toFile(), LinkedHashMap.class);
        String profile = text(raw.get("runProfile"));
        if (profile.isBlank()) return null;
        String id = text(raw.get("runId"));
        if (id.isBlank()) id = expectedRunId;
        if (!expectedRunId.equals(id)) return null;
        return new CatalogRun(index, id, profile, text(raw.get("outcome")), lineagePurpose(raw.get("lineage")));
    }

    private static List<CatalogRun> catalogRuns(Path projectRoot) throws IOException {
        Path catalog = projectRoot.toAbsolutePath().normalize()
                .resolve("reports")
                .resolve("diagnostic-runs")
                .resolve("run-catalog.json");
        if (!Files.isRegularFile(catalog)) return List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = JSON.readValue(catalog.toFile(), LinkedHashMap.class);
        Object runsValue = raw.get("runs");
        if (!(runsValue instanceof List<?> runs) || runs.isEmpty()) return List.of();
        List<CatalogRun> parsed = new java.util.ArrayList<>();
        for (Object item : runs) {
            if (!(item instanceof Map<?, ?> run)) continue;
            parsed.add(new CatalogRun(
                    catalog,
                    text(run.get("runId")),
                    text(run.get("runProfile")),
                    text(run.get("outcome")),
                    lineagePurpose(run.get("lineage"))
            ));
        }
        return parsed;
    }

    public record CatalogRun(Path catalog, String runId, String runProfile, String outcome, String purpose) {
        public CatalogRun(Path catalog, String runId, String runProfile, String outcome) {
            this(catalog, runId, runProfile, outcome, "");
        }
    }

    private static boolean present(Snapshot snapshot) {
        return snapshot != null && (snapshot.hasRunVars() || !isBlank(snapshot.runProfile()));
    }

    private static Snapshot readFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JSON.readValue(file.toFile(), LinkedHashMap.class);
            return fromMap(raw);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read Discover snapshot: " + file, failure);
        }
    }

    private static void writeCopy(Path file, Snapshot snapshot) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        JSON.writeValue(file.toFile(), snapshotToMap(snapshot));
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
                text(raw.get("createdAt")),
                booleanValue(raw.get("sealed"))
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
        body.put("sealed", snapshot.sealed());
        return body;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value == null) return false;
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
