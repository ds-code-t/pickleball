package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Run history beside diagnostic reports. This store does not read or write
 * {@code reports/diagnostic-runs} except when {@code --from} copies a summary
 * out of an existing {@code run-index.json}.
 */
public final class StoreHistory {
    public static final int DEFAULT_DAYS = 30;
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private StoreHistory() {
    }

    public record Run(
            String id,
            String startedAt,
            String finishedAt,
            String status,
            String scenario,
            int passed,
            int failed,
            String note
    ) {
    }

    public record LogLine(long id, String loggedAt, String entity, String message) {
    }

    public record Draft(
            String id,
            String startedAt,
            String status,
            String scenario,
            int passed,
            int failed,
            String note,
            String entity,
            String message
    ) {
    }

    public record RetainResult(int runs, int removed) {
        public String line() {
            return "retained runs=" + runs + " removed=" + removed;
        }
    }

    public record Link(String type, String id) {
    }

    public record LinkRow(long logId, String type, String id) {
    }

    public static Run record(Path project, Draft draft) throws SQLException {
        return record(project, draft, List.of());
    }

    public static Run record(Path project, Draft draft, List<Link> links) throws SQLException {
        StoreSecrets.rejectAssignment(project, draft.note());
        StoreSecrets.rejectAssignment(project, draft.message());
        String id = requireId(draft.id());
        String started = stamp(draft.startedAt());
        String status = blank(draft.status()) ? "recorded" : draft.status().trim();
        String scenario = draft.scenario() == null ? "" : draft.scenario();
        String note = draft.note() == null ? "" : draft.note();
        String entity = blank(draft.entity()) ? "run" : draft.entity().trim();
        String finished = Instant.now().toString();
        List<Link> structured = links == null ? List.of() : List.copyOf(links);
        String message = draft.message();
        if (blank(message) && !structured.isEmpty()) message = "linked";
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO runs(run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(run_id) DO UPDATE SET
                            finished_at = excluded.finished_at,
                            status = excluded.status,
                            scenario = excluded.scenario,
                            passed = excluded.passed,
                            failed = excluded.failed,
                            note = excluded.note,
                            sandbox = excluded.sandbox
                        """)) {
                    statement.setString(1, id);
                    statement.setString(2, started);
                    statement.setString(3, finished);
                    statement.setString(4, status);
                    statement.setString(5, scenario);
                    statement.setInt(6, draft.passed());
                    statement.setInt(7, draft.failed());
                    statement.setString(8, note);
                    statement.setString(9, StoreScope.current().sandboxColumn());
                    statement.executeUpdate();
                }
                if (!blank(message)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO run_logs(run_id, logged_at, entity, message, sandbox) VALUES (?, ?, ?, ?, ?)")) {
                        statement.setString(1, id);
                        statement.setString(2, finished);
                        statement.setString(3, entity);
                        statement.setString(4, message);
                        statement.setString(5, StoreScope.current().sandboxColumn());
                        statement.executeUpdate();
                    }
                    long logId;
                    try (PreparedStatement statement = connection.prepareStatement("SELECT last_insert_rowid()");
                         ResultSet rows = statement.executeQuery()) {
                        logId = rows.next() ? rows.getLong(1) : 0L;
                    }
                    insertLinks(connection, logId, structured);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        StoreLog.log(project, "history", id, "record status=" + status);
        for (Link link : structured) {
            StoreLog.log(project, "track", link.type() + "#" + link.id(), "log-link");
        }
        return new Run(id, started, finished, status, scenario, draft.passed(), draft.failed(), note);
    }

    public static List<LinkRow> links(Path project, String id) throws SQLException {
        requireId(id);
        if (!StorePaths.ready(project)) return List.of();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT l.log_id, l.entity_type, l.entity_id
                     FROM log_entity_links l
                     JOIN run_logs r ON r.id = l.log_id
                     WHERE r.run_id = ?
                     ORDER BY l.log_id, l.entity_type, l.entity_id
                     """)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                List<LinkRow> found = new ArrayList<>();
                while (rows.next()) found.add(new LinkRow(rows.getLong(1), rows.getString(2), rows.getString(3)));
                return found;
            }
        }
    }

    private static void insertLinks(Connection connection, long logId, List<Link> links) throws SQLException {
        if (logId <= 0 || links.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO log_entity_links(log_id, entity_type, entity_id, sandbox) VALUES (?, ?, ?, ?)
                """)) {
            for (Link link : links) {
                if (link == null || !TYPE.matcher(link.type() == null ? "" : link.type()).matches()
                        || !ID.matcher(link.id() == null ? "" : link.id()).matches()) {
                    throw new IllegalArgumentException("history link must be Type#id");
                }
                statement.setLong(1, logId);
                statement.setString(2, link.type());
                statement.setString(3, link.id());
                statement.setString(4, StoreScope.current().sandboxColumn());
                statement.executeUpdate();
            }
        }
    }

    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    public static Draft fromDiagnostic(Path project, String relative) throws IOException {
        String path = StorePaths.normalizeRelative(relative);
        Path file = project.resolve(path);
        if (Files.isDirectory(file)) file = file.resolve("run-index.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("diagnostic run index was not found: " + path);
        }
        JsonNode node = JSON.readTree(Files.readAllBytes(file));
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("diagnostic run index is not a JSON object: " + path);
        }
        String id = text(node, "runId");
        if (blank(id)) id = file.getParent() == null ? "run" : file.getParent().getFileName().toString();
        String scenario = "";
        JsonNode scenarios = node.get("scenarios");
        if (scenarios != null && scenarios.isArray() && !scenarios.isEmpty()) {
            scenario = text(scenarios.get(0), "name");
        }
        JsonNode counts = node.get("counts");
        return new Draft(
                id,
                text(node, "startedAt"),
                text(node, "outcome"),
                scenario,
                counts == null ? 0 : counts.path("passed").asInt(0),
                counts == null ? 0 : counts.path("failed").asInt(0),
                "diagnostic:" + path,
                "run",
                null);
    }

    public static List<Run> list(Path project) throws SQLException {
        if (!StorePaths.ready(project)) return List.of();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note FROM runs ORDER BY started_at, run_id");
             ResultSet rows = statement.executeQuery()) {
            List<Run> runs = new ArrayList<>();
            while (rows.next()) runs.add(readRun(rows));
            return runs;
        }
    }

    public static Run find(Path project, String id) throws SQLException {
        requireId(id);
        if (!StorePaths.ready(project)) return null;
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note FROM runs WHERE run_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readRun(rows) : null;
            }
        }
    }

    public static List<LogLine> logs(Path project, String id) throws SQLException {
        requireId(id);
        if (!StorePaths.ready(project)) return List.of();
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT id, logged_at, entity, message FROM run_logs WHERE run_id = ? ORDER BY id")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                List<LogLine> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(new LogLine(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
                return lines;
            }
        }
    }

    public static RetainResult retain(Path project, int days, int maxBytes) throws SQLException {
        if (days < 0) throw new IllegalArgumentException("history days must be zero or greater");
        if (maxBytes < 0) throw new IllegalArgumentException("history max bytes must be zero or greater");
        if (!StorePaths.ready(project)) return new RetainResult(0, 0);
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int removed = 0;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                List<Run> runs = load(connection);
                for (Run run : runs) {
                    if (started(run).isBefore(cutoff)) {
                        delete(connection, run.id());
                        removed++;
                    }
                }
                while (bytes(connection) > maxBytes) {
                    List<Run> left = load(connection);
                    if (left.isEmpty()) break;
                    delete(connection, left.get(0).id());
                    removed++;
                }
                int trimmed = StoreChanges.trim(connection, StoreChanges.days());
                connection.commit();
                int remaining = load(connection).size();
                StoreLog.log(project, "history", "runs", "retain removed=" + removed);
                StoreLog.log(project, "changes", "*", "retain removed=" + trimmed);
                return new RetainResult(remaining, removed);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public static int days() {
        return positive(setting("pkb_store_history_days", "PKB_STORE_HISTORY_DAYS"), DEFAULT_DAYS);
    }

    public static int maxBytes() {
        return positive(setting("pkb_store_history_max_bytes", "PKB_STORE_HISTORY_MAX_BYTES"), DEFAULT_MAX_BYTES);
    }

    private static List<Run> load(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note FROM runs ORDER BY started_at, run_id");
             ResultSet rows = statement.executeQuery()) {
            List<Run> runs = new ArrayList<>();
            while (rows.next()) runs.add(readRun(rows));
            return runs;
        }
    }

    private static void delete(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM runs WHERE run_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static int bytes(Connection connection) throws SQLException {
        int total = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT note, scenario FROM runs");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                total += utf8(rows.getString(1));
                total += utf8(rows.getString(2));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT message FROM run_logs");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) total += utf8(rows.getString(1));
        }
        return total;
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Instant started(Run run) {
        try {
            return Instant.parse(run.startedAt());
        } catch (RuntimeException failure) {
            return Instant.EPOCH;
        }
    }

    private static Run readRun(ResultSet rows) throws SQLException {
        return new Run(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5),
                rows.getInt(6),
                rows.getInt(7),
                rows.getString(8));
    }

    private static String stamp(String raw) {
        if (blank(raw)) return Instant.now().toString();
        try {
            return Instant.parse(raw.trim()).toString();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("history started time must be ISO-8601: " + raw);
        }
    }

    private static String requireId(String id) {
        if (id == null || !RUN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("history run id must match [A-Za-z0-9][A-Za-z0-9._-]*");
        }
        return id;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int positive(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        return System.getenv(env);
    }
}
