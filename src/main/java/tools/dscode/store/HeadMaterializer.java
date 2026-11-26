package tools.dscode.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flattens deltas into {@code head_rows}. An unchanged source row is not copied.
 * More than {@code delta_window} deltas (default 100) are folded into the head.
 * Those rows are copied to {@code delta_archive} in {@code runstate.db} and then
 * removed from the source {@code deltas} table. A normal run does not replay them.
 */
public final class HeadMaterializer {
    private HeadMaterializer() {
    }

    /** Applies deltas after the cursor. An {@code expensive=1} append does not call this. */
    public static void flatten(Connection connection, Path project) throws SQLException {
        transaction(connection, () -> applyPending(connection, project));
    }

    /** Flattens only when {@code heads.stale} is 1. {@code store status} and hydrate call this. */
    public static void quiet(Connection connection, Path project) throws SQLException {
        if (!UniversalEntity.headStale(connection)) return;
        flatten(connection, project);
    }

    /**
     * Replays every remaining delta onto the head. Rows that only a folded delta
     * touched stay, because that delta is already gone and its effect is the head.
     */
    public static void rebuild(Connection connection, Path project) throws SQLException {
        transaction(connection, () -> {
            Set<String> touched = new HashSet<>();
            for (DeltaLog.Delta delta : DeltaLog.after(connection, 0L)) {
                touched.add(delta.table() + "\n" + delta.pk());
            }
            for (String key : touched) {
                int split = key.indexOf('\n');
                UniversalEntity.change(connection,
                        "DELETE FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                        UniversalEntity.HEAD_ID, key.substring(0, split), key.substring(split + 1));
            }
            UniversalEntity.putMeta(connection, UniversalEntity.META_CURSOR, "0");
            markStale(connection);
            applyPending(connection, project);
        });
    }

    static void markStale(Connection connection) throws SQLException {
        long through = UniversalEntity.cursor(connection);
        int updated = UniversalEntity.change(connection,
                "UPDATE heads SET stale = 1 WHERE head_id = ?", UniversalEntity.HEAD_ID);
        if (updated == 0) {
            UniversalEntity.change(connection, """
                    INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash)
                    VALUES (?, NULL, 1, ?, '')
                    """, UniversalEntity.HEAD_ID, through);
        }
    }

    /**
     * Writes one head row per delta after the cursor, then folds the window.
     * The caller owns the transaction.
     */
    static void applyPending(Connection connection, Path project) throws SQLException {
        CowSandbox.seedParentHead(connection, project);
        long cursor = UniversalEntity.cursor(connection);
        List<DeltaLog.Delta> pending = DeltaLog.after(connection, cursor);
        for (DeltaLog.Delta delta : pending) {
            boolean tombstone = delta.after() == null;
            String payload = tombstone ? "" : delta.after();
            UniversalEntity.change(connection, """
                    INSERT INTO head_rows(head_id, tbl, pk, payload_json, tombstone)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(head_id, tbl, pk) DO UPDATE SET
                        payload_json = excluded.payload_json,
                        tombstone = excluded.tombstone
                    """, UniversalEntity.HEAD_ID, delta.table(), delta.pk(), payload, tombstone ? 1 : 0);
            cursor = delta.seq();
        }
        String now = Instant.now().toString();
        int updated = UniversalEntity.change(connection, """
                UPDATE heads SET built_at = ?, stale = 0, through_seq = ? WHERE head_id = ?
                """, now, cursor, UniversalEntity.HEAD_ID);
        if (updated == 0) {
            UniversalEntity.change(connection, """
                    INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash)
                    VALUES (?, ?, 0, ?, '')
                    """, UniversalEntity.HEAD_ID, now, cursor);
        }
        UniversalEntity.putMeta(connection, UniversalEntity.META_CURSOR, Long.toString(cursor));
        fold(connection, project, cursor);
        StoreLog.log(project, "pin", "heads", "flatten through=" + cursor + " stale=0");
    }

    /**
     * Copies deltas past the live window into {@code delta_archive} and removes
     * them from the source log. Does not replay a head from the archive.
     */
    public static void archiveOverflow(Connection connection, Path project) throws SQLException {
        fold(connection, project, UniversalEntity.cursor(connection));
    }

    /**
     * Replays one archived delta onto the current head.
     * A seq that is still in the source {@code deltas} table is refused.
     */
    public static void rebuildFromArchive(Connection connection, Path project, long seq) throws SQLException {
        if (seq < 1) {
            throw new IllegalArgumentException(
                    "error: archive seq must be a positive integer\nfix: store head rebuild --from-archive=<seq>");
        }
        String live = UniversalEntity.text(connection, "SELECT seq FROM deltas WHERE seq = ?", seq);
        if (live != null) {
            throw new IllegalArgumentException(
                    "error: archive seq " + seq + " is still in the live window\nfix: store delta list");
        }
        DeltaLog.Delta delta = loadArchive(connection, seq);
        transaction(connection, () -> applyArchived(connection, delta));
        StoreLog.log(project, "delta", "delta_archive", "rebuild seq=" + seq + " head=" + UniversalEntity.HEAD_ID);
    }

    private static void fold(Connection connection, Path project, long through) throws SQLException {
        int window = window(connection);
        String raw = UniversalEntity.text(connection, "SELECT COUNT(*) FROM deltas");
        int count = raw == null ? 0 : Integer.parseInt(raw.trim());
        if (count <= window) return;
        int drop = count - window;
        archive(connection, drop);
        UniversalEntity.change(connection, """
                DELETE FROM deltas WHERE seq IN (
                    SELECT seq FROM deltas ORDER BY seq ASC LIMIT ?
                )
                """, drop);
        StoreLog.log(project, "delta", "deltas",
                "fold dropped=" + drop + " kept=" + window + " through=" + through);
    }

    private static void archive(Connection connection, int drop) throws SQLException {
        List<String[]> rows = new java.util.ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT seq, at, kind, payload_json FROM deltas ORDER BY seq ASC LIMIT ?
                """)) {
            select.setInt(1, drop);
            try (ResultSet selected = select.executeQuery()) {
                while (selected.next()) {
                    rows.add(new String[] {
                            Long.toString(selected.getLong(1)),
                            selected.getString(2),
                            selected.getString(3),
                            selected.getString(4)
                    });
                }
            }
        }
        for (String[] row : rows) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO delta_archive(seq, at, kind, payload_json, head_id)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setLong(1, Long.parseLong(row[0]));
                insert.setString(2, row[1]);
                insert.setString(3, row[2]);
                insert.setString(4, row[3]);
                insert.setString(5, UniversalEntity.HEAD_ID);
                insert.executeUpdate();
            }
        }
    }

    private static DeltaLog.Delta loadArchive(Connection connection, long seq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT seq, at, kind, payload_json FROM delta_archive WHERE seq = ?")) {
            statement.setLong(1, seq);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException(
                            "error: archive seq " + seq + " was not folded\nfix: store delta list");
                }
                return DeltaLog.readPayload(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4));
            }
        }
    }

    private static void applyArchived(Connection connection, DeltaLog.Delta delta) throws SQLException {
        if (delta.table() == null || delta.table().isBlank() || delta.pk() == null || delta.pk().isBlank()) {
            throw new IllegalArgumentException(
                    "error: archive seq " + delta.seq() + " has no table\nfix: store delta list");
        }
        boolean tombstone = delta.after() == null;
        String payload = tombstone ? "" : delta.after();
        UniversalEntity.change(connection, """
                INSERT INTO head_rows(head_id, tbl, pk, payload_json, tombstone)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(head_id, tbl, pk) DO UPDATE SET
                    payload_json = excluded.payload_json,
                    tombstone = excluded.tombstone
                """, UniversalEntity.HEAD_ID, delta.table(), delta.pk(), payload, tombstone ? 1 : 0);
    }

    private static int window(Connection connection) throws SQLException {
        String raw = UniversalEntity.meta(connection, "delta_window");
        if (raw == null || raw.isBlank()) return 100;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 ? 100 : parsed;
        } catch (NumberFormatException failure) {
            return 100;
        }
    }

    private static void transaction(Connection connection, SqlWork work) throws SQLException {
        boolean own = connection.getAutoCommit();
        if (own) connection.setAutoCommit(false);
        try {
            work.run();
            if (own) connection.commit();
        } catch (SQLException | RuntimeException failure) {
            if (own) connection.rollback();
            throw failure;
        } finally {
            if (own) connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
