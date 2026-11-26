package tools.dscode.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;

/**
 * Projection of the source database: one commit pin, ordered deltas, and a
 * flattened head. Unchanged source rows stay in their own tables.
 * A run does not read this head yet.
 */
public final class UniversalEntity {
    public static final String HEAD_ID = "current";
    static final String META_CURSOR = "delta_cursor";

    private UniversalEntity() {
    }

    public record Pin(String commitSha, String committedAt, boolean dirty) {
    }

    /** One resolved row. A tombstone hides the parent. A missing parent has a null payload and is not a tombstone. */
    public record View(String table, String pk, String payload, boolean tombstone) {
        public boolean present() {
            return !tombstone && payload != null;
        }
    }

    /**
     * Reads {@code head_rows} when the head is fresh, then applies deltas after
     * {@code delta_cursor}. Does not write. A stale head is still resolved in memory.
     */
    public static View resolve(Connection connection, String table, String pk) throws SQLException {
        long cursor = cursor(connection);
        Hit hit = headHit(connection, table, pk);
        String payload;
        boolean tombstone;
        if (hit.found()) {
            tombstone = hit.tombstone();
            payload = tombstone ? null : hit.payload();
        } else {
            tombstone = false;
            payload = parentPayload(connection, table, pk);
        }
        for (DeltaLog.Delta delta : DeltaLog.after(connection, cursor)) {
            if (!table.equals(delta.table()) || !pk.equals(delta.pk())) continue;
            if (delta.after() == null) {
                tombstone = true;
                payload = null;
            } else {
                tombstone = false;
                payload = delta.after();
            }
        }
        return new View(table, pk, payload, tombstone);
    }

    /** Payload currently visible, including a stale tail. Null when the row is absent or a tombstone. */
    public static String currentPayload(Connection connection, String table, String pk) throws SQLException {
        View view = resolve(connection, table, pk);
        return view.present() ? view.payload() : null;
    }

    public static long cursor(Connection connection) throws SQLException {
        String raw = meta(connection, META_CURSOR);
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    public static Pin refreshPin(Connection connection, Path project) throws SQLException {
        StoreGit.Snapshot git = project == null
                ? StoreGit.Snapshot.empty("no project")
                : StoreGit.capture(project);
        String sha = git.available() && git.commit() != null ? git.commit() : "";
        boolean dirty = git.available() && git.dirty();
        String at = Instant.now().toString();
        change(connection, """
                INSERT INTO pins(id, commit_sha, committed_at, dirty) VALUES (1, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    commit_sha = excluded.commit_sha,
                    committed_at = excluded.committed_at,
                    dirty = excluded.dirty
                """, sha, at, dirty ? 1 : 0);
        StoreLog.log(project, "pin", "pins", "commit=" + sha + " dirty=" + (dirty ? 1 : 0));
        return new Pin(sha, at, dirty);
    }

    public static Pin loadPin(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT commit_sha, committed_at, dirty FROM pins WHERE id = 1")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Pin("", "", false);
                return new Pin(rows.getString(1), rows.getString(2), rows.getInt(3) != 0);
            }
        }
    }

    public static boolean headStale(Connection connection) throws SQLException {
        String raw = text(connection, "SELECT stale FROM heads WHERE head_id = ?", HEAD_ID);
        return raw != null && !"0".equals(raw.trim());
    }

    static String meta(Connection connection, String key) throws SQLException {
        return text(connection, "SELECT value FROM schema_meta WHERE `key` = ?", key);
    }

    static void putMeta(Connection connection, String key, String value) throws SQLException {
        change(connection, tools.dscode.store.sql.SqlDialect.UPSERT_META, key, value);
    }

    static String text(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return rows.getString(1);
            }
        }
    }

    static int change(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        }
    }

    static long insert(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, args);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    if (id > 0) return id;
                }
            }
        }
        String raw = text(connection, "SELECT last_insert_rowid()");
        if (raw == null || raw.isBlank()) throw new SQLException("no generated key");
        return Long.parseLong(raw.trim());
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        if (args == null) return;
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            if (value == null) statement.setNull(index + 1, Types.VARCHAR);
            else statement.setObject(index + 1, value);
        }
    }

    private record Hit(boolean found, String payload, boolean tombstone) {
    }

    private static Hit headHit(Connection connection, String table, String pk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json, tombstone FROM head_rows
                WHERE head_id = ? AND tbl = ? AND pk = ?
                """)) {
            statement.setString(1, HEAD_ID);
            statement.setString(2, table);
            statement.setString(3, pk);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Hit(false, null, false);
                return new Hit(true, rows.getString(1), rows.getInt(2) != 0);
            }
        }
    }

    private static String parentPayload(Connection connection, String table, String pk) throws SQLException {
        if ("entities".equals(table)) {
            int cut = pk == null ? -1 : pk.indexOf('#');
            if (cut <= 0 || cut == pk.length() - 1) return null;
            return text(connection,
                    "SELECT payload_json FROM entities WHERE entity_type = ? AND entity_id = ?",
                    pk.substring(0, cut), pk.substring(cut + 1));
        }
        if ("files".equals(table)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT raw_content FROM files WHERE path = ?")) {
                statement.setString(1, pk);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    byte[] raw = rows.getBytes(1);
                    if (raw == null) return "";
                    return new String(raw, StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
