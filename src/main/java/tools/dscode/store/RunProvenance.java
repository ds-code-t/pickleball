package tools.dscode.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Git commit and feature-file path on a run row. The commit is recorded only
 * when that run is written. A commit already stored is kept. A blank commit
 * on an older row stays blank. Feature paths from later scenarios in the same
 * run are appended. This does not start a server.
 */
public final class RunProvenance {
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

    /**
     * Feature paths on rows that already have a scenario key. Does not assign
     * a git commit. {@code project} is unused; the commit is resolved in {@link #fill}.
     */
    public static int fillBlanks(Connection connection, Path project) throws SQLException {
        if (connection == null) return 0;
        if (project == null) return 0;
        int updated = 0;
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

    /** Current HEAD for this write. Not cached: a later commit in this process is a new value. */
    private static String commitOf(Path project) {
        if (project == null) return "";
        StoreGit.Command commit = StoreGit.run(project, 5, "git", "rev-parse", "HEAD");
        if (commit == null || commit.timedOut() || commit.code() != 0 || commit.out() == null) return "";
        String value = commit.out().trim();
        int newline = value.indexOf('\n');
        if (newline >= 0) value = value.substring(0, newline).trim();
        return value;
    }
}
