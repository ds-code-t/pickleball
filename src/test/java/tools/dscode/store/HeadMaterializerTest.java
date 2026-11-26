package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.SqlConnections;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadMaterializerTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void rebuildAfterAForcedStaleHeadMatchesAFreshFlatten() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            DeltaLog.put(connection, project, "entities", "Doc#one", "fresh");
            String fresh = UniversalEntity.text(connection,
                    "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#one");
            assertEquals("fresh", fresh);
            assertEquals("", jar(connection));

            UniversalEntity.change(connection,
                    "UPDATE head_rows SET payload_json = 'corrupt' WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#one");
            UniversalEntity.change(connection, "UPDATE heads SET stale = 1 WHERE head_id = ?", UniversalEntity.HEAD_ID);
            assertTrue(UniversalEntity.headStale(connection));

            HeadMaterializer.rebuild(connection, project);
            assertEquals(fresh, UniversalEntity.text(connection,
                    "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#one"));
            assertEquals("fresh", UniversalEntity.resolve(connection, "entities", "Doc#one").payload());
            assertEquals("", jar(connection));
            assertEquals("0", UniversalEntity.text(connection,
                    "SELECT stale FROM heads WHERE head_id = ?", UniversalEntity.HEAD_ID));
        }
    }

    @Test
    void headShowRebuildsWhenTheHeadIsStale() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            DeltaLog.put(connection, project, "entities", "Doc#one", "fresh");
            UniversalEntity.change(connection,
                    "UPDATE head_rows SET payload_json = 'corrupt' WHERE head_id = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "Doc#one");
            UniversalEntity.change(connection, "UPDATE heads SET stale = 1 WHERE head_id = ?", UniversalEntity.HEAD_ID);
        }
        Capture shown = run("store", "head", "show", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("stale 0"), shown.out);
        assertTrue(shown.out.contains("rows 1"), shown.out);
        assertTrue(shown.out.contains("jar "), shown.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals("fresh", UniversalEntity.resolve(database.connection(), "entities", "Doc#one").payload());
            assertEquals("", jar(database.connection()));
        }
    }

    private static String jar(Connection connection) throws Exception {
        String raw = UniversalEntity.text(connection,
                "SELECT jar_hash FROM heads WHERE head_id = ?", UniversalEntity.HEAD_ID);
        return raw == null ? "" : raw;
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
