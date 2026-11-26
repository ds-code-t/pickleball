package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.SqlConnections;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalEntityTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void putOverridesParentAndTombstoneHidesIt() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            insertEntity(connection, "Doc", "one", "base");
            long putSeq = DeltaLog.put(connection, project, "entities", "Doc#one", "edited");
            UniversalEntity.View edited = UniversalEntity.resolve(connection, "entities", "Doc#one");
            assertEquals("edited", edited.payload());
            assertTrue(edited.present());
            assertNotNull(DeltaLog.find(connection, putSeq));

            DeltaLog.delete(connection, project, "entities", "Doc#one");
            UniversalEntity.View hidden = UniversalEntity.resolve(connection, "entities", "Doc#one");
            assertTrue(hidden.tombstone());
            assertFalse(hidden.present());
            assertNull(hidden.payload());
            assertEquals("base", parent(connection));
            assertNotNull(DeltaLog.find(connection, putSeq));
        }
    }

    @Test
    void unchangedRowStaysOutOfTheHeadUntilADeltaNamesIt() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            insertEntity(connection, "Doc", "keep", "kept");
            insertFile(connection, "features/keep.feature", "Feature: Keep\n");
            assertEquals(0, headRows(connection, "entities", "Doc#keep"));
            assertEquals(0, headRows(connection, "files", "features/keep.feature"));
            assertEquals("kept", UniversalEntity.resolve(connection, "entities", "Doc#keep").payload());
            assertEquals("Feature: Keep\n",
                    UniversalEntity.resolve(connection, "files", "features/keep.feature").payload());

            DeltaLog.put(connection, project, "entities", "Doc#other", "named");
            assertEquals(1, headRows(connection, "entities", "Doc#other"));
            assertEquals(0, headRows(connection, "entities", "Doc#keep"));
            assertEquals(0, headRows(connection, "files", "features/keep.feature"));
            assertEquals("kept", UniversalEntity.resolve(connection, "entities", "Doc#keep").payload());
            assertEquals("Feature: Keep\n",
                    UniversalEntity.resolve(connection, "files", "features/keep.feature").payload());
        }
    }

    @Test
    void undoRestoresPreviousAndRedoRestoresTheEdit() throws Exception {
        String previous = System.getProperty("pkb_store_log");
        try (StoreDatabase database = StoreDatabase.open(project)) {
            System.setProperty("pkb_store_log", "delta");
            Connection connection = database.connection();
            insertEntity(connection, "Doc", "one", "base");
            long putSeq = DeltaLog.put(connection, project, "entities", "Doc#one", "SECRET-PAYLOAD-VALUE");
            assertEquals("SECRET-PAYLOAD-VALUE", UniversalEntity.resolve(connection, "entities", "Doc#one").payload());

            long undoSeq = DeltaLog.undo(connection, project);
            assertEquals("base", UniversalEntity.resolve(connection, "entities", "Doc#one").payload());
            DeltaLog.Delta undone = DeltaLog.find(connection, putSeq);
            assertNotNull(undone);
            DeltaLog.Delta inverse = DeltaLog.find(connection, undoSeq);
            assertNotNull(inverse);
            assertEquals(DeltaLog.UNDO, inverse.kind());
            assertEquals(putSeq, inverse.undoOf());

            long redoSeq = DeltaLog.redo(connection, project);
            assertEquals("SECRET-PAYLOAD-VALUE",
                    UniversalEntity.resolve(connection, "entities", "Doc#one").payload());
            DeltaLog.Delta redone = DeltaLog.find(connection, redoSeq);
            assertNotNull(redone);
            assertEquals(DeltaLog.REDO, redone.kind());
            assertNotNull(DeltaLog.find(connection, putSeq));
        } finally {
            if (previous == null) System.clearProperty("pkb_store_log");
            else System.setProperty("pkb_store_log", previous);
        }
        String log = Files.readString(project.resolve(".pickleball").resolve("store").resolve("store.log"));
        assertTrue(log.contains("delta entities sandbox=main"), log);
        assertTrue(log.contains("kind=undo"), log);
        assertTrue(log.contains("kind=redo"), log);
        assertFalse(log.contains("SECRET-PAYLOAD-VALUE"), log);
    }

    @Test
    void the101stDeltaFoldsTheOldestAndKeepsTheHeadPayload() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            long first = DeltaLog.put(connection, project, "entities", "Doc#old", "from-oldest");
            for (int index = 0; index < 100; index++) {
                DeltaLog.put(connection, project, "entities", "Doc#other", "n" + (index + 2));
            }
            assertEquals("100", count(connection, "SELECT COUNT(*) FROM deltas"));
            assertNull(DeltaLog.find(connection, first));
            assertEquals("from-oldest", UniversalEntity.resolve(connection, "entities", "Doc#old").payload());
            assertEquals("n101", UniversalEntity.resolve(connection, "entities", "Doc#other").payload());
            assertEquals("from-oldest", headPayload(connection, "entities", "Doc#old"));
        }
    }

    @Test
    void pinShowOnAnEmptyGitDirectoryStoresAnEmptySha() throws Exception {
        Capture pin = run("store", "pin", "show", "--project=" + project);
        assertEquals(0, pin.code, pin.err);
        assertTrue(pin.out.contains("commit \n") || pin.out.startsWith("commit \r\n") || line(pin.out, "commit "), pin.out);
        assertTrue(pin.out.contains("dirty 0"), pin.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            UniversalEntity.Pin stored = UniversalEntity.loadPin(database.connection());
            assertEquals("", stored.commitSha());
            assertFalse(stored.dirty());
        }
        Capture missing = run("store", "delta", "undo", "--project=" + project);
        assertEquals(1, missing.code, missing.out);
        assertTrue(missing.err.contains("nothing to undo"), missing.err);
        Capture unknown = run("store", "pin", "nope", "--project=" + project);
        assertEquals(2, unknown.code, unknown.out);
        assertTrue(unknown.err.contains("error:"), unknown.err);
        assertTrue(StoreCli.help().contains("Usage: store pin show"));
        assertTrue(StoreCli.help().contains("Usage: store delta undo"));
        assertTrue(StoreCli.help().contains("Usage: store head rebuild"));
    }

    private static boolean line(String text, String prefix) {
        for (String row : text.split("\\R")) {
            if (row.equals(prefix)) return true;
        }
        return false;
    }

    private static void insertEntity(Connection connection, String type, String id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO entities(entity_type, entity_id, version, payload_json, secret_refs, content_hash, updated_at)
                VALUES (?, ?, 1, ?, '', 'h', 't')
                """)) {
            statement.setString(1, type);
            statement.setString(2, id);
            statement.setString(3, payload);
            statement.executeUpdate();
        }
    }

    private static void insertFile(Connection connection, String path, String text) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited)
                VALUES (?, 'abc', ?, 0, 'feature', 't', 'abc', 0)
                """)) {
            statement.setString(1, path);
            statement.setBytes(2, text.getBytes(StandardCharsets.UTF_8));
            statement.executeUpdate();
        }
    }

    private static String parent(Connection connection) throws Exception {
        return UniversalEntity.text(connection,
                "SELECT payload_json FROM entities WHERE entity_type = 'Doc' AND entity_id = 'one'");
    }

    private static int headRows(Connection connection, String table, String pk) throws Exception {
        String raw = UniversalEntity.text(connection,
                "SELECT COUNT(*) FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                UniversalEntity.HEAD_ID, table, pk);
        return raw == null ? 0 : Integer.parseInt(raw.trim());
    }

    private static String headPayload(Connection connection, String table, String pk) throws Exception {
        return UniversalEntity.text(connection,
                "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                UniversalEntity.HEAD_ID, table, pk);
    }

    private static String count(Connection connection, String sql) throws Exception {
        return UniversalEntity.text(connection, sql);
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
