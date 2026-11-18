package tools.dscode.control.protocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the store run history. The store file is the source of truth.
 * Diagnostic JSON is used only when this file has no row. SQLite is loaded by
 * reflection so the protocol stays free of compile dependencies.
 */
final class StoreHistoryReader {
    static final String WHERE = ".pickleball/store/runstate.db";
    private static final String DRIVER = "org.sqlite.JDBC";
    private static final int TEXT_LIMIT = 160;

    private StoreHistoryReader() {
    }

    static Path database(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("store").resolve("runstate.db");
    }

    static Map<String, Object> one(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank() || !available(project)) return null;
        try (Connection connection = open(project);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT run_id, status, passed, failed FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? run(rows) : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    static List<Map<String, Object>> runs(Path project) {
        if (!available(project)) return List.of();
        try (Connection connection = open(project);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT run_id, status, passed, failed FROM runs ORDER BY started_at, run_id");
             ResultSet rows = statement.executeQuery()) {
            List<Map<String, Object>> found = new ArrayList<>();
            while (rows.next()) found.add(run(rows));
            return found;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static List<Map<String, Object>> steps(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank() || !available(project)) return List.of();
        try (Connection connection = open(project);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.step_ord, s.step_text, s.status
                     FROM step_executions s
                     JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                     WHERE e.run_id = ?
                     ORDER BY s.step_ord, s.step_exec_id
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> found = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    int ord = rows.getInt(1);
                    item.put("kind", "step");
                    item.put("seq", ord);
                    item.put("id", Integer.toString(ord));
                    item.put("runId", runId);
                    item.put("status", rows.getString(3));
                    item.put("text", clip(rows.getString(2)));
                    item.put("where", WHERE);
                    found.add(item);
                }
                return found;
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static Map<String, Object> run(ResultSet rows) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        String status = rows.getString(2);
        item.put("kind", "run");
        item.put("runId", rows.getString(1));
        item.put("outcome", status);
        item.put("failed", status != null && "FAILED".equalsIgnoreCase(status));
        item.put("where", WHERE);
        return item;
    }

    private static boolean available(Path project) {
        return project != null && driver() && Files.isRegularFile(database(project));
    }

    private static boolean driver() {
        try {
            Class.forName(DRIVER);
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    private static Connection open(Path project) throws Exception {
        String path = database(project).toAbsolutePath().normalize().toString().replace('\\', '/');
        return DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
    }

    private static String clip(String value) {
        if (value == null) return "";
        String text = value.replace("\r", " ").replace("\n", " ").trim();
        if (text.length() <= TEXT_LIMIT) return text;
        return text.substring(0, TEXT_LIMIT);
    }
}
