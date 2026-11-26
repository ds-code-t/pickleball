package tools.dscode.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.store.sql.SqliteOpen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes the head id and enabled entity hashes for one run into {@code runstate.db}.
 * Source rows are read on the caller's connection. Snapshot inserts name only run-state
 * tables, so they stay on the run-state connection inside the same transaction.
 * A cow parent is read with its own connection and is not attached.
 */
public final class RunSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_SEPARATOR = "\u001f";

    private RunSnapshot() {
    }

    /**
     * Writes one snapshot the first time {@code runId} is seen. Later steps and a later
     * promote leave the row alone.
     */
    public static void capture(Connection connection, Path project, String runId, String startedAt) throws SQLException {
        if (runId == null || runId.isBlank() || connection == null) return;
        if (exists(connection, runId)) return;
        String headId = UniversalEntity.HEAD_ID;
        UniversalEntity.Pin pin = UniversalEntity.loadPin(connection);
        String commit = pin.commitSha() == null ? "" : pin.commitSha();
        String sandbox = StoreScope.label(StoreScope.current());
        Parent parent = parentOf(connection, project);
        List<Identity> identities = identities(connection);
        identities.sort(Comparator.comparing(Identity::key));
        String enabledHash = StoreHashes.sha256(joined(identities).getBytes(StandardCharsets.UTF_8));
        String started = startedAt == null ? "" : startedAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_snapshots(
                    run_id, commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, commit);
            statement.setString(3, headId);
            statement.setString(4, sandbox);
            statement.setString(5, parent.commit());
            statement.setString(6, parent.headId());
            statement.setString(7, enabledHash);
            statement.setString(8, started);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_snapshot_entities(run_id, entity_type, entity_id, content_hash)
                VALUES (?, ?, ?, ?)
                """)) {
            for (Identity identity : identities) {
                statement.setString(1, runId);
                statement.setString(2, identity.type());
                statement.setString(3, identity.id());
                statement.setString(4, identity.hash());
                statement.addBatch();
            }
            if (!identities.isEmpty()) statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runs SET commit_sha = ?, head_id = ?, parent_commit = ?, parent_head_id = ?
                WHERE run_id = ?
                """)) {
            statement.setString(1, commit);
            statement.setString(2, headId);
            statement.setString(3, parent.commit());
            statement.setString(4, parent.headId());
            statement.setString(5, runId);
            statement.executeUpdate();
        }
        StoreLog.log(project, "pin", runId, "snapshot head=" + headId);
    }

    /** Snapshot fields for {@code store history show}. Null when this run has no snapshot row. */
    public static Map<String, Object> load(Path project, String runId) throws SQLException {
        if (project == null || runId == null || runId.isBlank()) return null;
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at
                     FROM run_snapshots WHERE run_id = ?
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("commit_sha", text(rows.getString(1)));
                snapshot.put("head_id", text(rows.getString(2)));
                snapshot.put("sandbox", text(rows.getString(3)));
                snapshot.put("parent_commit", text(rows.getString(4)));
                snapshot.put("parent_head_id", text(rows.getString(5)));
                snapshot.put("enabled_hash", text(rows.getString(6)));
                snapshot.put("started_at", text(rows.getString(7)));
                return snapshot;
            }
        }
    }

    public static String headId(Path project, String runId) throws SQLException {
        Map<String, Object> snapshot = load(project, runId);
        if (snapshot == null) return "";
        return text(String.valueOf(snapshot.get("head_id")));
    }

    /** Agent envelope. The run row, including {@code snapshot}, is one {@code items} element. */
    public static String historyJson(Path project, StoreHistory.Run run) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", run.id());
        item.put("status", run.status());
        item.put("started", run.startedAt());
        item.put("scenario", run.scenario());
        item.put("passed", run.passed());
        item.put("failed", run.failed());
        item.put("note", run.note());
        Map<String, Object> snapshot = load(project, run.id());
        if (snapshot != null) item.put("snapshot", snapshot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(item));
        return JSON.writeValueAsString(AgentEnvelope.apply(body, 0, "history show", project, run.id()));
    }

    private static boolean exists(Connection connection, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM run_snapshots WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Parent parentOf(Connection connection, Path project) throws SQLException {
        if (project == null || !CowSandbox.isCow(connection)) return Parent.NONE;
        String name = UniversalEntity.text(connection, "SELECT value FROM _pkb_branch WHERE `key` = ?", "parent");
        if (name == null || name.isBlank()) return Parent.NONE;
        Path file = "main".equals(name)
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, name);
        if (!Files.isRegularFile(file)) return Parent.NONE;
        try (Connection parent = SqliteOpen.open(file)) {
            UniversalEntity.Pin pin = UniversalEntity.loadPin(parent);
            String commit = pin.commitSha() == null ? "" : pin.commitSha();
            String head = UniversalEntity.text(parent, "SELECT head_id FROM heads WHERE head_id = ?", UniversalEntity.HEAD_ID);
            if (head == null || head.isBlank()) head = UniversalEntity.HEAD_ID;
            return new Parent(commit, head);
        }
    }

    /**
     * Enabled rows of the bound database. Identities and hashes only.
     * The cow parent is not copied into this list.
     */
    private static List<Identity> identities(Connection connection) throws SQLException {
        List<Identity> found = new ArrayList<>();
        read(connection, found, "SELECT path, content_hash FROM files WHERE enabled = 1",
                (rows, into) -> into.add(new Identity("file", text(rows.getString(1)), text(rows.getString(2)))));
        read(connection, found, """
                SELECT file_path, ord, keyword, name, line, tags, description
                FROM scenarios WHERE enabled = 1
                """, (rows, into) -> {
            String id = text(rows.getString(1)) + KEY_SEPARATOR + rows.getInt(2);
            String material = text(rows.getString(3)) + "\n" + text(rows.getString(4)) + "\n"
                    + rows.getInt(5) + "\n" + text(rows.getString(6)) + "\n" + text(rows.getString(7));
            into.add(new Identity("scenario", id, StoreHashes.sha256(material.getBytes(StandardCharsets.UTF_8))));
        });
        read(connection, found, """
                SELECT file_path, scenario_ord, ord, keyword, text FROM steps WHERE enabled = 1
                """, (rows, into) -> {
            String id = text(rows.getString(1)) + KEY_SEPARATOR + rows.getInt(2) + KEY_SEPARATOR + rows.getInt(3);
            String material = text(rows.getString(4)) + "\n" + text(rows.getString(5));
            into.add(new Identity("step", id, StoreHashes.sha256(material.getBytes(StandardCharsets.UTF_8))));
        });
        read(connection, found, "SELECT definition_id, content_hash FROM step_definitions WHERE enabled = 1",
                (rows, into) -> into.add(new Identity("definition", text(rows.getString(1)), text(rows.getString(2)))));
        read(connection, found, "SELECT folder_key, norm_path FROM folders WHERE enabled = 1",
                (rows, into) -> into.add(new Identity(
                        "folder", text(rows.getString(1)),
                        StoreHashes.sha256(text(rows.getString(2)).getBytes(StandardCharsets.UTF_8)))));
        read(connection, found, "SELECT entity_type, entity_id, content_hash FROM entities",
                (rows, into) -> into.add(new Identity(text(rows.getString(1)), text(rows.getString(2)), text(rows.getString(3)))));
        return found;
    }

    private static void read(Connection connection, List<Identity> into, String sql, RowReader reader)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) reader.read(rows, into);
        } catch (SQLException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
            if (message.contains("no such table")) return;
            throw failure;
        }
    }

    private static String joined(List<Identity> identities) {
        StringBuilder body = new StringBuilder();
        for (Identity identity : identities) {
            if (!body.isEmpty()) body.append('\n');
            body.append(identity.key());
        }
        return body.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record Identity(String type, String id, String hash) {
        String key() {
            return type + KEY_SEPARATOR + id;
        }
    }

    private record Parent(String commit, String headId) {
        private static final Parent NONE = new Parent("", "");
    }

    @FunctionalInterface
    private interface RowReader {
        void read(ResultSet rows, List<Identity> into) throws SQLException;
    }
}
