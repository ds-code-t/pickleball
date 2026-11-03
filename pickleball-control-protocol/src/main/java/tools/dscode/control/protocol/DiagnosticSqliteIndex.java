package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Disposable FTS index over the JSON {@link DiagnosticQuery} already returns.
 * Delete {@code reports/diagnostic-runs/diagnostics.db} and rebuild. The files stay authoritative.
 * SQLite is loaded by reflection so this module stays free of compile dependencies.
 */
public final class DiagnosticSqliteIndex {
    public static final String FILE_NAME = "diagnostics.db";
    private static final String DRIVER = "org.sqlite.JDBC";

    private DiagnosticSqliteIndex() {
    }

    public static Path database(Path project) {
        return project.toAbsolutePath().normalize()
                .resolve("reports")
                .resolve("diagnostic-runs")
                .resolve(FILE_NAME);
    }

    /** @return inserted rows, or {@code -1} when SQLite is not on the classpath */
    public static int rebuild(Path project) {
        if (project == null || !driverPresent()) return -1;
        boolean previous = DiagnosticQuery.beginIndexBuild();
        try {
            return rebuildUnlocked(project);
        } finally {
            DiagnosticQuery.endIndexBuild(previous);
        }
    }

    /**
     * When the disposable index exists and a source file is strictly newer, rebuild it
     * before a search. A missing database stays missing so search can fall back to the files.
     *
     * @return true when a rebuild ran
     */
    public static boolean refreshIfStale(Path project) {
        if (project == null || !driverPresent()) return false;
        Path db = database(project);
        if (!Files.isRegularFile(db)) return false;
        try {
            if (!sourcesNewerThan(project, Files.getLastModifiedTime(db))) return false;
        } catch (IOException failure) {
            return false;
        }
        return rebuild(project) >= 0;
    }

    private static int rebuildUnlocked(Path project) {
        if (project == null || !driverPresent()) return -1;
        Path db = database(project);
        try {
            Files.createDirectories(db.getParent());
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-wal"));
            Files.deleteIfExists(Path.of(db + "-shm"));
            int inserted = 0;
            try (Connection connection = open(db)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE VIRTUAL TABLE record_fts USING fts5(kind, run_id, scenario_id, item_id, body)");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO record_fts(kind, run_id, scenario_id, item_id, body) VALUES (?,?,?,?,?)"
                )) {
                    for (Map<String, Object> request : sources()) {
                        DiagnosticQuery.Outcome outcome = DiagnosticQuery.query(project, request);
                        if (outcome.exit() != 0) continue;
                        Object rows = outcome.body().get("items");
                        if (!(rows instanceof List<?> items)) continue;
                        for (Object row : items) {
                            if (!(row instanceof Map<?, ?> map)) continue;
                            Map<String, Object> item = JsonLite.object(map);
                            if (item == null) continue;
                            insert.setString(1, text(item.get("kind")));
                            insert.setString(2, text(item.get("runId")));
                            insert.setString(3, text(item.get("scenarioId")));
                            insert.setString(4, text(item.get("id")));
                            insert.setString(5, JsonLite.write(item));
                            insert.addBatch();
                            inserted++;
                        }
                    }
                    insert.executeBatch();
                }
            }
            return inserted;
        } catch (Throwable failure) {
            try {
                Files.deleteIfExists(db);
            } catch (Throwable ignored) {
                // A half-written index is disposable.
            }
            return -1;
        }
    }

    /**
     * @return matching records stored in the index, or null when the index cannot be used
     *         (missing file, missing driver, or a MATCH syntax error). Null means the caller
     *         should read the JSON files.
     */
    public static List<Map<String, Object>> find(Path project, String needle, String runId, String kind) {
        if (project == null || needle == null || needle.isBlank() || !driverPresent()) return null;
        Path db = database(project);
        if (!Files.isRegularFile(db)) return null;
        String match = match(needle);
        if (match == null) return null;
        try (Connection connection = open(db);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT body, run_id, kind FROM record_fts WHERE record_fts MATCH ?"
             )) {
            statement.setString(1, match);
            List<Map<String, Object>> items = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (runId != null && !runId.equals(rows.getString(2))) continue;
                    if (kind != null && !"event".equals(kind) && !kind.equals(rows.getString(3))) continue;
                    Object parsed = JsonLite.parse(rows.getString(1));
                    Map<String, Object> item = JsonLite.object(parsed);
                    if (item != null) items.add(item);
                }
            }
            return items;
        } catch (Throwable failure) {
            return null;
        }
    }

    public static List<String> storedBodies(Path project) {
        if (project == null || !driverPresent() || !Files.isRegularFile(database(project))) return List.of();
        try (Connection connection = open(database(project));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT body FROM record_fts")) {
            List<String> bodies = new ArrayList<>();
            while (rows.next()) bodies.add(rows.getString(1));
            return bodies;
        } catch (Throwable failure) {
            return List.of();
        }
    }

    public static boolean driverPresent() {
        try {
            Class.forName(DRIVER);
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    private static boolean sourcesNewerThan(Path project, FileTime dbTime) throws IOException {
        Path runs = project.resolve("reports").resolve("diagnostic-runs");
        if (treeNewer(runs, dbTime, false)) return true;
        Path agent = project.resolve(".pickleball").resolve("agent-log");
        if (Files.isRegularFile(agent) && Files.getLastModifiedTime(agent).compareTo(dbTime) > 0) return true;
        Path rooted = project.resolve(".pickleball").resolve("runs");
        if (treeNewer(rooted, dbTime, true)) return true;
        Path workerLogs = project.resolve(".pickleball").resolve("workbench").resolve("logs");
        return treeNewer(workerLogs, dbTime, true);
    }

    /** {@code narrowed} keeps JSON, JSONL, and {@code worker-*.log}. The diagnostic-runs tree keeps every other file except the index itself. */
    private static boolean treeNewer(Path root, FileTime dbTime, boolean narrowed) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (skipIndexFile(path)) continue;
                if (narrowed && !indexSource(path)) continue;
                if (Files.getLastModifiedTime(path).compareTo(dbTime) > 0) return true;
            }
        }
        return false;
    }

    private static boolean skipIndexFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return FILE_NAME.equals(name) || (FILE_NAME + "-wal").equals(name) || (FILE_NAME + "-shm").equals(name);
    }

    private static boolean indexSource(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.startsWith("worker-") && name.endsWith(".log")) return true;
        return name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".jsonl.gz");
    }

    private static Connection open(Path db) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static List<Map<String, Object>> sources() {
        List<Map<String, Object>> requests = new ArrayList<>();
        requests.add(page("runs", null));
        requests.add(page("query", "scenario"));
        requests.add(page("query", "event"));
        requests.add(page("errors", null));
        requests.add(page("query", "property"));
        requests.add(page("query", "artifact"));
        requests.add(page("query", "log"));
        return requests;
    }

    private static Map<String, Object> page(String op, String kind) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op);
        request.put("unpaged", Boolean.TRUE);
        request.put("limit", 100_000);
        if (kind != null) request.put("kind", kind);
        return request;
    }

    private static String match(String needle) {
        String[] parts = needle.trim().split("\\s+");
        StringBuilder match = new StringBuilder();
        for (String part : parts) {
            String token = part.replace("\"", "").trim();
            if (token.isEmpty()) continue;
            if (!match.isEmpty()) match.append(" AND ");
            match.append("body : \"").append(token).append('"');
        }
        return match.isEmpty() ? null : match.toString();
    }

    private static String text(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        return "null".equals(text) ? "" : text.toLowerCase(Locale.ROOT).isEmpty() ? "" : text;
    }
}
