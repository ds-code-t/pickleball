package tools.dscode.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Shared database edit. Writes {@code raw_content} and {@code content_hash},
 * sets {@code db_edited}, leaves {@code last_synced_hash}, and re-projects
 * parsed rows in the same transaction. Gherkin is validated before commit.
 */
public final class StoreContent {
    public static final String STRUCTURED_EDIT =
            "Structured edits are not supported. Edit the feature text with `store feature put <path>`. "
                    + "Scenario and step rows are a projection of that text and cannot be updated directly.";

    private StoreContent() {
    }

    public static void rejectStructuredEdit() {
        throw new IllegalArgumentException(STRUCTURED_EDIT);
    }

    /** A raw change that did not go through this edit path. */
    public static boolean untracked(StoreFiles.Row row) {
        if (row == null || row.edited()) return false;
        String content = row.contentHash() == null ? "" : row.contentHash();
        String synced = row.lastSyncedHash() == null ? "" : row.lastSyncedHash();
        return !content.equals(synced);
    }

    public static String untrackedMessage(String path) {
        return "untracked DB edit on " + path;
    }

    public static StoreFiles.Row put(Path project, String relativePath, byte[] raw) throws SQLException {
        String path = StorePaths.normalizeRelative(relativePath);
        String sourceType = StoreSources.sourceType(path);
        byte[] body = raw == null ? new byte[0] : raw;
        String now = Instant.now().toString();
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                StoreFiles.Row written = write(connection, project, path, sourceType, body, now);
                connection.commit();
                StoreLog.log(project, capability(path, sourceType), path,
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
    public static StoreFiles.Row write(
            Connection connection,
            Path project,
            String path,
            String sourceType,
            byte[] raw,
            String now
    ) throws SQLException {
        String normalized = StorePaths.normalizeRelative(path);
        byte[] body = raw == null ? new byte[0] : raw;
        validate(project, normalized, sourceType, body);
        StoreFiles.Row existing = StoreFiles.find(connection, normalized);
        if (existing != null && existing.binaryRef() && StoreHashes.sha256(body).equals(existing.contentHash())) {
            return existing;
        }
        String synced = existing == null || existing.lastSyncedHash() == null ? "" : existing.lastSyncedHash();
        String hydratedAt = now == null ? Instant.now().toString() : now;
        boolean wasReference = existing != null && (existing.binaryRef()
                || "binary".equals(existing.sourceType())
                || "binary-ref".equals(existing.sourceType()));
        String type = wasReference ? "binary" : sourceType;
        StoreFiles.Row row = new StoreFiles.Row(
                normalized,
                StoreHashes.sha256(body),
                body,
                System.currentTimeMillis(),
                type,
                hydratedAt,
                synced,
                true,
                StoreHashes.gitBlob(body),
                false,
                body.length,
                "db",
                ""
        );
        StoreFiles.upsert(connection, row);
        if (wasReference && existing.binaryRef()) {
            StoreLog.log(project, "refs", normalized, "owned bytes=" + body.length);
        }
        if (!"binary".equals(type)) {
            StoreGherkin.store(connection, project, normalized, sourceType, body);
            if (StoreEntities.isRecord(normalized, sourceType)) {
                StoreEntities.store(connection, project, normalized, body);
            }
        }
        if (CowSandbox.isCow(connection)) {
            String before = existing == null || existing.raw() == null
                    ? null : new String(existing.raw(), StandardCharsets.UTF_8);
            String after = new String(body, StandardCharsets.UTF_8);
            DeltaLog.append(connection, project, DeltaLog.PUT, "files", normalized, before, after, false);
        }
        return row;
    }

    private static void validate(Path project, String path, String sourceType, byte[] raw) {
        if (StoreGherkin.isGherkin(path, sourceType) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
            if (StoreGherkin.parse(raw) == null) {
                throw new IllegalArgumentException("Gherkin is not valid: " + path);
            }
        }
        if (StoreEntities.isRecord(path, sourceType)) {
            StoreEntities.parseRecord(project, raw);
        }
    }

    private static String capability(String path, String sourceType) {
        if (StoreEntities.isRecord(path, sourceType)) return "entity";
        if (StoreGherkin.isGherkin(path, sourceType) || path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
            return "feature";
        }
        return "content";
    }

    /**
     * Removes a stored file row and its projections. Does not create a database.
     * Returns whether a row or a disk file was removed.
     */
    public static boolean delete(Path project, String relativePath) throws SQLException, java.io.IOException {
        String path = StorePaths.normalizeRelative(relativePath);
        boolean removed = false;
        if (StorePaths.ready(project)) {
            try (StoreDatabase database = StoreDatabase.open(project)) {
                Connection connection = database.connection();
                if (CowSandbox.isCow(connection)) {
                    connection.setAutoCommit(false);
                    try {
                        boolean tombstone = CowSandbox.tombstoneFile(connection, project, path);
                        if (tombstone) {
                            removed = deleteProjections(connection, path) || true;
                        }
                        connection.commit();
                    } catch (SQLException | RuntimeException failure) {
                        connection.rollback();
                        throw failure;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                    if (removed) {
                        StoreLog.log(project, "edit", path, "delete tombstone");
                    }
                    return removed;
                }
                connection.setAutoCommit(false);
                try {
                    removed = deleteProjections(connection, path);
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
            StoreLog.log(project, "edit", path, "delete");
        }
        return removed;
    }

    private static boolean deleteProjections(Connection connection, String path) throws SQLException {
        boolean removed = false;
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
        return removed;
    }
}

