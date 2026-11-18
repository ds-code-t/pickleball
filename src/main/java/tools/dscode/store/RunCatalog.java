package tools.dscode.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run catalog is a view. The store is the source of truth for status and
 * step rows. Agent runs are listed here even when they have no diagnostic index.
 */
public final class RunCatalog {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private RunCatalog() {
    }

    public static Path file(Path project) {
        return project.toAbsolutePath().normalize()
                .resolve("reports").resolve("diagnostic-runs").resolve("run-catalog.json");
    }

    /** Inserts an agent run. A row the store already published keeps the store outcome. */
    public static void publishAgent(Path project, String runId, String outcome, String purpose) {
        publish(project, runId, outcome, purpose, null, null, false);
    }

    /** Copies the store row into the catalog. Store outcome wins. */
    public static void publishStore(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank()) return;
        try {
            StoreHistory.Run run = StoreHistory.find(project, runId);
            if (run == null) return;
            publish(project, runId, run.status(), null, run.passed(), run.failed(), true);
        } catch (RuntimeException | java.sql.SQLException ignored) {
            // A catalog write must not change the run.
        }
    }

    /**
     * After a diagnostic rebuild, put store rows and agent runs back.
     * No-op unless {@code runsRoot} is {@code <project>/reports/diagnostic-runs}.
     */
    public static void retain(Path runsRoot, List<Map<String, Object>> runs) {
        if (runsRoot == null || runs == null) return;
        Path project = projectOf(runsRoot);
        if (project == null) return;
        try {
            Path runsDir = project.resolve(".pickleball").resolve("runs");
            if (Files.isDirectory(runsDir)) {
                try (var children = Files.list(runsDir)) {
                    for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                        if (!Files.isRegularFile(child.resolve("record.json"))) continue;
                        String name = child.getFileName().toString();
                        if (find(runs, name) != null) continue;
                        Map<String, Object> record = readObject(child.resolve("record.json"));
                        String outcome = text(record.get("status"));
                        String purpose = text(record.get("why"));
                        runs.add(row(name, outcome == null ? "RUNNING" : outcome, purpose, null, null, "agent"));
                    }
                }
            }
            if (StorePaths.ready(project)) {
                for (StoreHistory.Run run : StoreHistory.list(project)) {
                    Map<String, Object> existing = find(runs, run.id());
                    if (existing == null) {
                        runs.add(row(run.id(), run.status(), null, run.passed(), run.failed(), "store"));
                    } else {
                        existing.put("outcome", run.status());
                        existing.put("source", "store");
                        existing.put("counts", counts(run.passed(), run.failed()));
                    }
                }
            }
        } catch (RuntimeException | IOException | java.sql.SQLException ignored) {
            // The diagnostic catalog write still proceeds.
        }
    }

    private static void publish(
            Path project,
            String runId,
            String outcome,
            String purpose,
            Integer passed,
            Integer failed,
            boolean fromStore
    ) {
        if (project == null || runId == null || runId.isBlank()) return;
        if (runId.indexOf('/') >= 0 || runId.indexOf('\\') >= 0 || runId.contains("..")) return;
        synchronized (LOCK) {
            try {
                Path catalog = file(project);
                Files.createDirectories(catalog.getParent());
                Map<String, Object> root = readObject(catalog);
                if (root.isEmpty()) root.put("schemaVersion", 1);
                List<Map<String, Object>> runs = runs(root);
                Map<String, Object> existing = find(runs, runId);
                if (existing != null && "store".equals(text(existing.get("source"))) && !fromStore) return;
                Map<String, Object> row = existing == null
                        ? row(runId, outcome, purpose, passed, failed, fromStore ? "store" : "agent")
                        : existing;
                if (existing == null) runs.add(row);
                else {
                    if (outcome != null && !outcome.isBlank()) row.put("outcome", outcome);
                    if (purpose != null && !purpose.isBlank()) row.put("purpose", purpose);
                    if (passed != null || failed != null) {
                        row.put("counts", counts(passed == null ? 0 : passed, failed == null ? 0 : failed));
                    }
                    row.put("source", fromStore ? "store" : text(row.get("source")) == null ? "agent" : row.get("source"));
                }
                root.put("schemaVersion", 1);
                root.put("updatedAt", Instant.now().toString());
                root.put("runs", runs);
                write(catalog, root);
            } catch (RuntimeException | IOException ignored) {
                // A catalog write must not change the run.
            }
        }
    }

    private static Map<String, Object> row(
            String runId, String outcome, String purpose, Integer passed, Integer failed, String source
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", runId);
        row.put("outcome", outcome == null || outcome.isBlank() ? "RUNNING" : outcome);
        row.put("startedAt", Instant.now().toString());
        if (purpose != null && !purpose.isBlank()) row.put("purpose", purpose);
        if (passed != null || failed != null) {
            row.put("counts", counts(passed == null ? 0 : passed, failed == null ? 0 : failed));
        }
        row.put("source", source);
        return row;
    }

    private static Map<String, Object> counts(int passed, int failed) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("passed", passed);
        counts.put("failed", failed);
        return counts;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Map<String, Object> root) {
        Object value = root.get("runs");
        List<Map<String, Object>> runs = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) runs.add((Map<String, Object>) map);
            }
        }
        root.put("runs", runs);
        return runs;
    }

    private static Map<String, Object> find(List<Map<String, Object>> runs, String runId) {
        for (Map<String, Object> run : runs) {
            if (runId.equals(text(run.get("runId")))) return run;
        }
        return null;
    }

    private static Map<String, Object> readObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            LinkedHashMap<String, Object> parsed = JSON.readValue(file.toFile(), OBJECT);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException | IOException ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static void write(Path file, Map<String, Object> root) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        JSON.writeValue(temp.toFile(), root);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path projectOf(Path runsRoot) {
        if (runsRoot.getFileName() == null || !"diagnostic-runs".equals(runsRoot.getFileName().toString())) return null;
        Path reports = runsRoot.getParent();
        if (reports == null || reports.getFileName() == null || !"reports".equals(reports.getFileName().toString())) {
            return null;
        }
        return reports.getParent();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
