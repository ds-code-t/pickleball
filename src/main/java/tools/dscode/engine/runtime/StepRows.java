package tools.dscode.engine.runtime;

import tools.dscode.store.StoreConfig;
import tools.dscode.store.StorePaths;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads the step ceiling and flag columns. A missing database is an empty
 * lookup. This class does not create a store and does not hydrate.
 */
public final class StepRows {
    private StepRows() {
    }

    public record Stored(
            Integer maxIterations,
            Integer timeoutSeconds,
            boolean retry,
            boolean ignoreFailures,
            boolean logFailuresContinue,
            boolean runIfFinished,
            boolean alwaysRun,
            boolean runIfPassing,
            boolean runIfFailed,
            boolean runIfHardFailed,
            boolean runIfSoftFailed,
            String untilMode
    ) {
    }

    /** Text flags, with a stored row overlaid when one matches the line. */
    public static StepFlags.Parsed lookup(Path project, String path, int line, String text) {
        StepFlags.Parsed parsed = StepFlags.parse(text);
        Stored row = find(project, path, line);
        if (row == null) return parsed;
        return parsed.overlayFlags(
                        row.ignoreFailures(),
                        row.logFailuresContinue(),
                        row.runIfFinished(),
                        row.alwaysRun(),
                        row.runIfPassing(),
                        row.runIfFailed(),
                        row.runIfHardFailed(),
                        row.runIfSoftFailed(),
                        row.retry())
                .withCeilings(row.maxIterations(), row.timeoutSeconds(), row.untilMode());
    }

    public static Stored find(Path project, String path, int line) {
        if (project == null || line <= 0 || path == null || path.isBlank()) return null;
        if (!StoreConfig.enabled(project)) return null;
        if (!Files.isRegularFile(StorePaths.mainDatabase(project))) return null;
        String file = fileName(path);
        try (SqlSession session = SqlConnections.borrow(project, Binding.main());
             PreparedStatement statement = session.connection().prepareStatement("""
                     SELECT max_iterations, timeout_seconds, retry, ignore_failures, log_failures_continue,
                            run_if_finished, always_run, run_if_passing, run_if_failed, run_if_hard_failed,
                            run_if_soft_failed, until_mode, file_path
                     FROM steps
                     WHERE line = ?
                       AND (file_path = ? OR replace(file_path, '\\', '/') LIKE ?)
                     """)) {
            statement.setInt(1, line);
            statement.setString(2, normalize(path));
            statement.setString(3, "%/" + file);
            try (ResultSet rows = statement.executeQuery()) {
                Stored match = null;
                while (rows.next()) {
                    Stored candidate = read(rows);
                    String stored = rows.getString(13);
                    if (stored != null && normalize(stored).equals(normalize(path))) return candidate;
                    if (match == null) match = candidate;
                }
                return match;
            }
        } catch (SQLException | java.io.IOException failure) {
            return null;
        }
    }

    private static Stored read(ResultSet rows) throws SQLException {
        return new Stored(
                integer(rows, 1),
                integer(rows, 2),
                rows.getInt(3) != 0,
                rows.getInt(4) != 0,
                rows.getInt(5) != 0,
                rows.getInt(6) != 0,
                rows.getInt(7) != 0,
                rows.getInt(8) != 0,
                rows.getInt(9) != 0,
                rows.getInt(10) != 0,
                rows.getInt(11) != 0,
                rows.getString(12));
    }

    private static Integer integer(ResultSet rows, int index) throws SQLException {
        int value = rows.getInt(index);
        return rows.wasNull() ? null : value;
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/');
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        while (value.startsWith("./")) value = value.substring(2);
        return value;
    }

    private static String fileName(String path) {
        String value = normalize(path);
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }
}
