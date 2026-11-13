package tools.dscode.dalton;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Shared database edit. Writes {@code raw_content} and {@code content_hash},
 * sets {@code db_edited}, leaves {@code last_synced_hash}, and re-projects
 * parsed rows in the same transaction. Gherkin is validated before commit.
 */
public final class DaltonContent {
    public static final String STRUCTURED_EDIT =
            "Structured edits are not supported. Edit the feature text with `dalton feature put <path>`. "
                    + "Scenario and step rows are a projection of that text and cannot be updated directly.";

    private DaltonContent() {
    }

    public static void rejectStructuredEdit() {
        throw new IllegalArgumentException(STRUCTURED_EDIT);
    }

    /** A raw change that did not go through this edit path. */
    public static boolean untracked(DaltonFiles.Row row) {
        if (row == null || row.edited()) return false;
        String content = row.contentHash() == null ? "" : row.contentHash();
        String synced = row.lastSyncedHash() == null ? "" : row.lastSyncedHash();
        return !content.equals(synced);
    }

    public static String untrackedMessage(String path) {
        return "untracked DB edit on " + path;
    }

    public static DaltonFiles.Row put(Path project, String relativePath, byte[] raw) throws SQLException {
        String path = DaltonPaths.normalizeRelative(relativePath);
        String sourceType = DaltonSources.sourceType(path);
        byte[] body = raw == null ? new byte[0] : raw;
        String now = Instant.now().toString();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                DaltonFiles.Row written = write(connection, project, path, sourceType, body, now);
                connection.commit();
                DaltonLog.log(project, capability(path, sourceType), path,
                        "put edited=1 bytes=" + body.length);
                return written;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Caller owns the transaction. Validation runs before any write so a bad
     * feature or entity rolls back to the previous rows.
     */
    public static DaltonFiles.Row write(
            Connection connection,
            Path project,
            String path,
            String sourceType,
            byte[] raw,
            String now
    ) throws SQLException {
        String normalized = DaltonPaths.normalizeRelative(path);
        byte[] body = raw == null ? new byte[0] : raw;
        validate(project, normalized, sourceType, body);
        DaltonFiles.Row existing = DaltonFiles.find(connection, normalized);
        String synced = existing == null || existing.lastSyncedHash() == null ? "" : existing.lastSyncedHash();
        String hydratedAt = now == null ? Instant.now().toString() : now;
        DaltonFiles.Row row = new DaltonFiles.Row(
                normalized,
                DaltonHashes.sha256(body),
                body,
                System.currentTimeMillis(),
                sourceType,
                hydratedAt,
                synced,
                true
        );
        DaltonFiles.upsert(connection, row);
        DaltonGherkin.store(connection, project, normalized, sourceType, body);
        if (DaltonEntities.isRecord(normalized, sourceType)) {
            DaltonEntities.store(connection, project, normalized, body);
        }
        return row;
    }

    private static void validate(Path project, String path, String sourceType, byte[] raw) {
        if (DaltonGherkin.isGherkin(path, sourceType) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
            if (DaltonGherkin.parse(raw) == null) {
                throw new IllegalArgumentException("Gherkin is not valid: " + path);
            }
        }
        if (DaltonEntities.isRecord(path, sourceType)) {
            DaltonEntities.parseRecord(project, raw);
        }
    }

    private static String capability(String path, String sourceType) {
        if (DaltonEntities.isRecord(path, sourceType)) return "entity";
        if (DaltonGherkin.isGherkin(path, sourceType) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
            return "feature";
        }
        return "content";
    }

    /**
     * Removes a stored file row and its projections. Does not create a database.
     * Returns whether a row or a disk file was removed.
     */
    public static boolean delete(Path project, String relativePath) throws SQLException, java.io.IOException {
        String path = DaltonPaths.normalizeRelative(relativePath);
        boolean removed = false;
        if (java.nio.file.Files.isRegularFile(DaltonPaths.database(project))) {
            try (DaltonDatabase database = DaltonDatabase.open(project)) {
                Connection connection = database.connection();
                connection.setAutoCommit(false);
                try {
                    for (String sql : java.util.List.of(
                            "DELETE FROM steps WHERE file_path = ?",
                            "DELETE FROM scenarios WHERE file_path = ?",
                            "DELETE FROM feature_docs WHERE file_path = ?",
                            "DELETE FROM entities WHERE source_file = ?",
                            "DELETE FROM files WHERE path = ?"
                    )) {
                        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setString(1, path);
                            if (statement.executeUpdate() > 0) removed = true;
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        }
        Path disk = project.resolve(path);
        if (java.nio.file.Files.isRegularFile(disk)) {
            java.nio.file.Files.delete(disk);
            removed = true;
        }
        if (removed) {
            DaltonLog.log(project, "edit", path, "delete");
        }
        return removed;
    }
}

