package tools.dscode.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rows in the Store {@code files} table. Paths use {@code /}. */
public final class StoreFiles {
    private StoreFiles() {
    }

    public record Row(
            String path,
            String contentHash,
            byte[] raw,
            long mtime,
            String sourceType,
            String lastHydratedAt,
            String lastSyncedHash,
            boolean edited,
            String gitBlob,
            boolean binaryRef,
            long byteSize,
            String location,
            String gitCommit
    ) {
        public Row(
                String path,
                String contentHash,
                byte[] raw,
                long mtime,
                String sourceType,
                String lastHydratedAt,
                String lastSyncedHash,
                boolean edited,
                String gitBlob,
                boolean binaryRef,
                long byteSize
        ) {
            this(path, contentHash, raw, mtime, sourceType, lastHydratedAt, lastSyncedHash, edited,
                    gitBlob, binaryRef, byteSize, binaryRef ? path : "db", "");
        }

        public Row(
                String path,
                String contentHash,
                byte[] raw,
                long mtime,
                String sourceType,
                String lastHydratedAt,
                String lastSyncedHash,
                boolean edited
        ) {
            this(path, contentHash, raw, mtime, sourceType, lastHydratedAt, lastSyncedHash, edited,
                    null, false, raw == null ? 0L : raw.length);
        }

        public Row {
            if (location == null || location.isBlank()) location = "db";
            if (gitCommit == null) gitCommit = "";
        }
    }

    public static Map<String, Row> index(Connection connection) throws SQLException {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit FROM files");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Row row = read(result);
                rows.put(row.path(), row);
            }
        }
        return rows;
    }

    public static Row find(Connection connection, String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit FROM files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public static void upsert(Connection connection, Row row) throws SQLException {
        String norm = StorePaths.normPath(row.path());
        String folder = StorePaths.folderKey(row.path());
        int enabled = enabledFor(row);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit, enabled, norm_path, folder_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    raw_content = excluded.raw_content,
                    mtime = excluded.mtime,
                    source_type = excluded.source_type,
                    last_hydrated_at = excluded.last_hydrated_at,
                    last_synced_hash = excluded.last_synced_hash,
                    db_edited = excluded.db_edited,
                    git_blob = excluded.git_blob,
                    binary_ref = excluded.binary_ref,
                    byte_size = excluded.byte_size,
                    location = excluded.location,
                    git_commit = excluded.git_commit,
                    enabled = excluded.enabled,
                    norm_path = excluded.norm_path,
                    folder_key = excluded.folder_key
                """)) {
            statement.setString(1, row.path());
            statement.setString(2, row.contentHash());
            statement.setBytes(3, row.raw());
            statement.setLong(4, row.mtime());
            statement.setString(5, row.sourceType());
            statement.setString(6, row.lastHydratedAt());
            statement.setString(7, row.lastSyncedHash());
            statement.setInt(8, row.edited() ? 1 : 0);
            statement.setString(9, row.gitBlob());
            statement.setInt(10, row.binaryRef() ? 1 : 0);
            statement.setLong(11, row.byteSize());
            statement.setString(12, row.location());
            statement.setString(13, row.gitCommit());
            statement.setInt(14, enabled);
            statement.setString(15, norm);
            statement.setString(16, folder);
            statement.executeUpdate();
        }
        if (!folder.isEmpty()) {
            try (PreparedStatement folderRow = connection.prepareStatement("""
                    INSERT INTO folders(folder_key, norm_path, enabled) VALUES (?, ?, 1)
                    ON CONFLICT(folder_key) DO UPDATE SET norm_path = excluded.norm_path
                    """)) {
                folderRow.setString(1, folder);
                folderRow.setString(2, folder);
                folderRow.executeUpdate();
            }
        }
    }

    /** In-root rows default to enabled. A feature-line {@code @pkb-ignore} turns the file off. Out-of-root rows stay stored and disabled. */
    static int enabledFor(Row row) {
        if (row == null || !StorePaths.inFeatureOrGlueRoot(row.path())) return 0;
        if (row.raw() != null && StoreGherkin.isGherkin(row.path(), row.sourceType())) {
            StoreGherkin.Document document = StoreGherkin.parse(row.raw());
            if (document != null && StoreGherkin.containsIgnore(document.tags())) return 0;
        }
        return 1;
    }

    static Row read(ResultSet result) throws SQLException {
        byte[] raw = result.getBytes(3);
        long byteSize = result.getLong(11);
        if (result.wasNull()) byteSize = raw == null ? 0L : raw.length;
        String location = result.getString(12);
        String gitCommit = result.getString(13);
        if (result.wasNull()) gitCommit = "";
        return new Row(
                result.getString(1),
                result.getString(2),
                raw,
                result.getLong(4),
                result.getString(5),
                result.getString(6),
                result.getString(7),
                result.getInt(8) != 0,
                result.getString(9),
                result.getInt(10) != 0,
                byteSize,
                location,
                gitCommit
        );
    }
}
