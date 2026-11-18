package tools.dscode.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Git commit and feature-file path on a run row. A blank value is filled.
 * A value already stored is kept. Feature paths from later scenarios in the
 * same run are appended. This does not start a server.
 */
public final class RunProvenance {
    private static final ConcurrentHashMap<String, String> COMMITS = new ConcurrentHashMap<>();

    private RunProvenance() {
    }

    public static void fill(Connection connection, Path project, String runId, String scenarioKey) throws SQLException {
        if (connection == null || runId == null || runId.isBlank()) return;
        String added = featureOf(scenarioKey);
        String existingCommit = "";
        String existingFeature = "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT git_commit, feature_path FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return;
                existingCommit = rows.getString(1) == null ? "" : rows.getString(1);
                existingFeature = rows.getString(2) == null ? "" : rows.getString(2);
            }
        }
        String feature = merge(existingFeature, added);
        String commit = existingCommit;
        if (commit.isBlank()) commit = commitOf(project);
        if (commit.equals(existingCommit) && feature.equals(existingFeature)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET git_commit = ?, feature_path = ? WHERE run_id = ?")) {
            statement.setString(1, commit);
            statement.setString(2, feature);
            statement.setString(3, runId);
            statement.executeUpdate();
        }
    }

    /** Blank commits on existing rows. A stored commit is not replaced. */
    public static int fillBlanks(Connection connection, Path project) throws SQLException {
        String commit = commitOf(project);
        int updated = 0;
        if (!commit.isBlank()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE runs SET git_commit = ? WHERE git_commit = ''")) {
                statement.setString(1, commit);
                updated += statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runs SET feature_path = (
                    SELECT CASE WHEN instr(e.scenario_key, '|') > 0
                        THEN substr(e.scenario_key, 1, instr(e.scenario_key, '|') - 1)
                        ELSE e.scenario_key END
                    FROM scenario_executions e
                    WHERE e.run_id = runs.run_id AND e.scenario_key <> ''
                    ORDER BY e.started_at
                    LIMIT 1
                )
                WHERE feature_path = ''
                  AND EXISTS (
                    SELECT 1 FROM scenario_executions e
                    WHERE e.run_id = runs.run_id AND e.scenario_key <> '')
                """)) {
            updated += statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET feature_path = '' WHERE feature_path IN ('memory', 'scenario')")) {
            statement.executeUpdate();
        }
        return updated;
    }

    public static String featureOf(String scenarioKey) {
        if (scenarioKey == null || scenarioKey.isBlank()) return "";
        String path = scenarioKey.trim().replace('\\', '/');
        int bar = path.indexOf('|');
        if (bar >= 0) path = path.substring(0, bar).trim();
        if (path.isBlank() || "memory".equals(path) || "scenario".equals(path)) return "";
        if (!path.contains("/") && !path.endsWith(".feature")) return "";
        return path;
    }

    static String merge(String existing, String added) {
        String left = existing == null ? "" : existing.trim();
        String right = added == null ? "" : added.trim();
        if (right.isEmpty()) return left;
        if (left.isEmpty()) return right;
        for (String part : left.split("\n")) {
            if (part.equals(right)) return left;
        }
        return left + "\n" + right;
    }

    private static String commitOf(Path project) {
        if (project == null) return "";
        String key = project.toAbsolutePath().normalize().toString();
        return COMMITS.computeIfAbsent(key, ignored -> {
            StoreGit.Snapshot git = StoreGit.capture(project);
            return git.commit() == null ? "" : git.commit();
        });
    }
}
