package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only delta log. Undo appends an inverse and leaves the undone row.
 * Redo appends a copy of the delta that undo inverted. Folding copies rows into
 * {@code delta_archive} only after {@link HeadMaterializer} has their effect in the head.
 */
public final class DeltaLog {
    public static final String PUT = "put";
    public static final String DELETE = "delete";
    public static final String UNDO = "undo";
    public static final String REDO = "redo";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeltaLog() {
    }

    public record Delta(
            long seq,
            String at,
            String actor,
            String kind,
            String table,
            String pk,
            String before,
            String after,
            long undoOf,
            boolean expensive
    ) {
    }

    public static long put(Connection connection, Path project, String table, String pk, String after)
            throws SQLException {
        String before = UniversalEntity.currentPayload(connection, table, pk);
        return append(connection, project, PUT, table, pk, before, after, false, 0L);
    }

    public static long delete(Connection connection, Path project, String table, String pk) throws SQLException {
        String before = UniversalEntity.currentPayload(connection, table, pk);
        return append(connection, project, DELETE, table, pk, before, null, false, 0L);
    }

    public static long append(Connection connection, Path project, String kind, String table, String pk,
                              String before, String after, boolean expensive) throws SQLException {
        return append(connection, project, kind, table, pk, before, after, expensive, 0L);
    }

    public static long undo(Connection connection, Path project) throws SQLException {
        long cursor = UniversalEntity.cursor(connection);
        Delta target = find(connection, cursor);
        if (target == null) throw new IllegalStateException("nothing to undo");
        return append(connection, project, UNDO, target.table(), target.pk(),
                target.after(), target.before(), false, target.seq());
    }

    /** Appends a copy of the delta that the latest undo inverted. */
    public static long redo(Connection connection, Path project) throws SQLException {
        long cursor = UniversalEntity.cursor(connection);
        Delta latest = find(connection, cursor);
        if (latest == null || latest.undoOf() <= 0) throw new IllegalStateException("nothing to redo");
        Delta following = find(connection, latest.undoOf());
        if (following == null) throw new IllegalStateException("nothing to redo");
        return append(connection, project, REDO, following.table(), following.pk(),
                following.before(), following.after(), false, 0L);
    }

    public static Delta find(Connection connection, long seq) throws SQLException {
        if (seq <= 0) return null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT seq, at, actor, kind, payload_json, expensive FROM deltas WHERE seq = ?
                """)) {
            statement.setLong(1, seq);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return read(rows);
            }
        }
    }

    public static List<Delta> list(Connection connection, int limit) throws SQLException {
        int capped = limit < 1 ? 1 : limit;
        List<Delta> newest = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT seq, at, actor, kind, payload_json, expensive
                FROM deltas ORDER BY seq DESC LIMIT ?
                """)) {
            statement.setInt(1, capped);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) newest.add(read(rows));
            }
        }
        java.util.Collections.reverse(newest);
        return newest;
    }

    /** Deltas strictly after {@code cursor}, oldest first. */
    public static List<Delta> after(Connection connection, long cursor) throws SQLException {
        List<Delta> pending = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT seq, at, actor, kind, payload_json, expensive
                FROM deltas WHERE seq > ? ORDER BY seq ASC
                """)) {
            statement.setLong(1, cursor);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) pending.add(read(rows));
            }
        }
        return pending;
    }

    private static long append(Connection connection, Path project, String kind, String table, String pk,
                               String before, String after, boolean expensive, long undoOf) throws SQLException {
        if (table == null || table.isBlank() || pk == null || pk.isBlank()) {
            throw new IllegalArgumentException("delta needs a table and a primary key");
        }
        boolean own = connection.getAutoCommit();
        if (own) connection.setAutoCommit(false);
        try {
            String actor = actor(connection);
            String payload = payload(table, pk, before, after, undoOf);
            long seq = UniversalEntity.insert(connection, """
                    INSERT INTO deltas(at, actor, kind, payload_json, expensive)
                    VALUES (?, ?, ?, ?, ?)
                    """, Instant.now().toString(), actor, kind, payload, expensive ? 1 : 0);
            if (expensive) HeadMaterializer.markStale(connection);
            else HeadMaterializer.applyPending(connection, project);
            if (own) connection.commit();
            String of = undoOf > 0 ? " of=" + undoOf : "";
            StoreLog.log(project, "delta", table,
                    "append seq=" + seq + " kind=" + kind + of + " pk=" + pk + " expensive=" + (expensive ? 1 : 0));
            return seq;
        } catch (SQLException | RuntimeException failure) {
            if (own) connection.rollback();
            throw failure;
        } finally {
            if (own) connection.setAutoCommit(true);
        }
    }

    private static String actor(Connection connection) throws SQLException {
        String name = UniversalEntity.text(connection, "SELECT name FROM _pkb_actor WHERE id = 1");
        return name == null || name.isBlank() ? "store" : name;
    }

    private static String payload(String table, String pk, String before, String after, long undoOf) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tbl", table);
        node.put("pk", pk);
        if (before == null) node.putNull("before");
        else node.put("before", before);
        if (after == null) node.putNull("after");
        else node.put("after", after);
        if (undoOf > 0) node.put("undoOf", undoOf);
        return node.toString();
    }

    static Delta readPayload(long seq, String at, String kind, String json) throws SQLException {
        try {
            JsonNode node = MAPPER.readTree(json == null ? "{}" : json);
            return new Delta(
                    seq,
                    at,
                    "",
                    kind,
                    node.path("tbl").asText(""),
                    node.path("pk").asText(""),
                    textOrNull(node.get("before")),
                    textOrNull(node.get("after")),
                    node.path("undoOf").asLong(0L),
                    false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("delta payload is not json: seq " + seq, failure);
        }
    }

    private static Delta read(ResultSet rows) throws SQLException {
        Delta parsed = readPayload(rows.getLong(1), rows.getString(2), rows.getString(4), rows.getString(5));
        return new Delta(
                parsed.seq(),
                parsed.at(),
                rows.getString(3),
                parsed.kind(),
                parsed.table(),
                parsed.pk(),
                parsed.before(),
                parsed.after(),
                parsed.undoOf(),
                rows.getInt(6) != 0);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.asText();
    }
}
