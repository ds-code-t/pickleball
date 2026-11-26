package tools.dscode.store;

import tools.dscode.common.exceptions.PickleballException;
import tools.dscode.store.sql.SqliteOpen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Copies a failed run's severity, scope, and signature into {@code run_attributes}.
 * {@code exception_clusters} is the SQL view over those rows. No model is trained.
 */
public final class ExceptionCluster {
    private static final ThreadLocal<Noted> NOTED = new ThreadLocal<>();

    private ExceptionCluster() {
    }

    public static void note(Throwable failure) {
        if (failure == null) return;
        StoreRunContext.Frame frame = StoreRunContext.current();
        Path project = frame == null ? null : frame.project();
        String step = frame == null ? null : frame.stepExecId();
        String runId = null;
        try {
            if (project != null && step != null) runId = StoreExecution.runOfStep(project, step);
        } catch (SQLException ignored) {
            runId = null;
        }
        String environment = environment(project, runId);
        boolean usage = entityUsage(project, step);
        String scope = resolve(failure, environment, usage);
        String severity = failure instanceof PickleballException pickled ? pickled.severity() : PickleballException.ERROR;
        String property = System.getProperty("pkb_environment");
        String writeEnvironment = attribute(project, runId, "environment");
        if ((writeEnvironment == null || writeEnvironment.isBlank()) && property != null && !property.isBlank()) {
            writeEnvironment = property.trim();
        } else {
            writeEnvironment = null;
        }
        NOTED.set(new Noted(signature(failure), severity, scope, writeEnvironment));
    }

    public static void clear() {
        NOTED.remove();
    }

    /** Class name plus the first stack frame whose class is outside {@code tools.dscode}. */
    public static String signature(Throwable failure) {
        String type = failure == null ? "Exception" : failure.getClass().getSimpleName();
        if (failure == null) return type;
        for (StackTraceElement element : failure.getStackTrace()) {
            String className = element.getClassName();
            if (className != null && className.startsWith("tools.dscode.")) continue;
            return type + " " + className + "." + element.getMethodName();
        }
        return type;
    }

    public static String resolve(Throwable failure, String environment, boolean entityUsage) {
        if (failure instanceof PickleballException pickled && pickled.scopeChosen()) return pickled.scope();
        if (environment != null && !environment.isBlank()) return "environmental";
        if (entityUsage) return "entity";
        return "universal";
    }

    public static void markEnvironment(String text) {
        StoreRunContext.Frame frame = StoreRunContext.current();
        if (frame == null) {
            throw new IllegalStateException(
                    "error: MARK ENVIRONMENT needs a tracked step\nfix: set pkb_store_track=true");
        }
        try {
            String runId = StoreExecution.runOfStep(frame.project(), frame.stepExecId());
            if (runId == null || runId.isBlank()) {
                throw new IllegalStateException(
                        "error: MARK ENVIRONMENT needs a tracked step\nfix: set pkb_store_track=true");
            }
            try (StoreDatabase database = StoreDatabase.open(frame.project())) {
                put(database.connection(), runId, "environment", text == null ? "" : text);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "error: MARK ENVIRONMENT could not be stored\nfix: store hydrate", failure);
        }
    }

    public static void write(Connection connection, String runId) throws SQLException {
        Noted noted = NOTED.get();
        if (noted == null || connection == null || runId == null || runId.isBlank()) return;
        if (noted.environment() != null) put(connection, runId, "environment", noted.environment());
        put(connection, runId, "exception.severity", noted.severity());
        put(connection, runId, "exception.scope", noted.scope());
        put(connection, runId, "exception.signature", noted.signature());
    }

    private static void put(Connection connection, String runId, String name, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_attributes(run_id, name, value) VALUES (?, ?, ?)
                ON CONFLICT(run_id, name) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, runId);
            statement.setString(2, name);
            statement.setString(3, value == null ? "" : value);
            statement.executeUpdate();
        }
    }

    private static String environment(Path project, String runId) {
        String stored = attribute(project, runId, "environment");
        if (stored != null && !stored.isBlank()) return stored;
        String property = System.getProperty("pkb_environment");
        return property == null ? "" : property.trim();
    }

    private static String attribute(Path project, String runId, String name) {
        if (project == null || runId == null || runId.isBlank()) return null;
        Path file = StorePaths.runState(project);
        if (!Files.isRegularFile(file)) return null;
        try (Connection connection = SqliteOpen.open(file);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM run_attributes WHERE run_id = ? AND name = ?")) {
            statement.setString(1, runId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static boolean entityUsage(Path project, String stepExecId) {
        if (project == null || stepExecId == null || stepExecId.isBlank()) return false;
        Path file = StorePaths.runState(project);
        if (!Files.isRegularFile(file)) return false;
        try (Connection connection = SqliteOpen.open(file);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM entity_usages WHERE step_exec_id = ?")) {
            statement.setString(1, stepExecId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    private record Noted(String signature, String severity, String scope, String environment) {
    }
}
