package tools.dscode.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Flattens deltas into {@code head_rows}. An unchanged source row is not copied.
 * More than {@code delta_window} deltas (default 100) are folded into the head
 * and those delta rows are deleted in the same transaction. Nothing is archived.
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

    private static void fold(Connection connection, Path project, long through) throws SQLException {
        int window = window(connection);
        String raw = UniversalEntity.text(connection, "SELECT COUNT(*) FROM deltas");
        int count = raw == null ? 0 : Integer.parseInt(raw.trim());
        if (count <= window) return;
        int drop = count - window;
        UniversalEntity.change(connection, """
                DELETE FROM deltas WHERE seq IN (
                    SELECT seq FROM deltas ORDER BY seq ASC LIMIT ?
                )
                """, drop);
        StoreLog.log(project, "delta", "deltas",
                "fold dropped=" + drop + " kept=" + window + " through=" + through);
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
