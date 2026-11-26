package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retention drops old runs and their snapshot rows. Folded deltas move to delta_archive. */
class SnapshotRetainTest {
    @TempDir
    Path project;

    @BeforeEach
    void isolateScope() {
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
    }

    @AfterEach
    void release() throws Exception {
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreScope.clear();
    }

    @Test
    void retainDropsTheOldRunAndRefusesALiveArchiveSeq() throws Exception {
        long folded;
        long live;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            exec(connection, """
                    INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash)
                    VALUES ('kept', '', 0, 0, '')
                    """);
            insertRun(connection, "old-run", "2020-01-01T00:00:00Z");
            insertRun(connection, "new-run", "2099-01-01T00:00:00Z");
            exec(connection, """
                    INSERT INTO run_snapshots(
                        run_id, commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at)
                    VALUES ('old-run', 'abc', 'kept', 'main', '', '', 'old-hash', '2020-01-01T00:00:00Z')
                    """);
            exec(connection, """
                    INSERT INTO run_snapshots(
                        run_id, commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at)
                    VALUES ('new-run', 'abc', 'kept', 'main', '', '', 'new-hash', '2099-01-01T00:00:00Z')
                    """);
            exec(connection, """
                    INSERT INTO snap_find_index(
                        run_id, head_id, doc_version, identity_chain, handle, role, name, backend_node_id)
                    VALUES ('old-run', 'kept', 'v1', '[]', 'h1', 'button', 'Save', '1')
                    """);
            UniversalEntity.putMeta(connection, "delta_window", "1");
            folded = DeltaLog.put(connection, project, "entities", "Doc#old", "from-oldest");
            live = DeltaLog.put(connection, project, "entities", "Doc#new", "kept-payload");
            assertNull(DeltaLog.find(connection, folded));
            assertNotNull(DeltaLog.find(connection, live));
        }
        SqlConnections.closeProject(project);
        StoreScope.clear();
        assertEquals(1, archiveCount(folded));
        assertFalse(liveDelta(folded));

        Capture aged = run("store", "retain", "--days=0", "--max-bytes=2147483648", "--project=" + project);
        assertEquals(0, aged.code, aged.err);
        assertTrue(aged.out.contains("removed=1"), aged.out);
        assertEquals(0, runStateCount("SELECT COUNT(*) FROM runs WHERE run_id = ?", "old-run"));
        assertEquals(1, runStateCount("SELECT COUNT(*) FROM runs WHERE run_id = ?", "new-run"));
        assertEquals(0, runStateCount("SELECT COUNT(*) FROM run_snapshots WHERE run_id = ?", "old-run"));
        assertEquals(1, runStateCount("SELECT COUNT(*) FROM run_snapshots WHERE run_id = ?", "new-run"));
        assertEquals(0, runStateCount("SELECT COUNT(*) FROM snap_find_index WHERE run_id = ?", "old-run"));
        assertEquals("kept", headId("kept"));
        assertEquals(1, archiveCount(folded));
        assertFalse(liveDelta(folded));

        Capture refused = run("store", "head", "rebuild", "--from-archive=" + live, "--project=" + project);
        assertEquals(1, refused.code, refused.out);
        assertTrue(refused.err.contains("error:"), refused.err);
        assertTrue(refused.err.contains("live window"), refused.err);
        assertEquals("kept", headId("kept"));

        Capture recovered = run("store", "head", "rebuild", "--from-archive=" + folded, "--project=" + project);
        assertEquals(0, recovered.code, recovered.err);
        assertTrue(recovered.out.contains("archive seq=" + folded), recovered.out);
        assertEquals("from-oldest", headPayload("entities", "Doc#old"));
    }

    private static void insertRun(Connection connection, String id, String started) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runs(
                    run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox,
                    git_commit, feature_path, commit_sha, head_id, parent_commit, parent_head_id)
                VALUES (?, ?, ?, 'PASSED', 'pay', 1, 0, '', 'main', '', '', 'abc', 'kept', '', '')
                """)) {
            statement.setString(1, id);
            statement.setString(2, started);
            statement.setString(3, started);
            statement.executeUpdate();
        }
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private int runStateCount(String sql, String id) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private int archiveCount(long seq) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM delta_archive WHERE seq = ?")) {
            statement.setLong(1, seq);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private boolean liveDelta(long seq) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return DeltaLog.find(database.connection(), seq) != null;
        }
    }

    private String headId(String id) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            String found = UniversalEntity.text(database.connection(),
                    "SELECT head_id FROM heads WHERE head_id = ?", id);
            return found == null ? "" : found;
        }
    }

    private String headPayload(String table, String pk) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return UniversalEntity.text(database.connection(),
                    "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, table, pk);
        }
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
