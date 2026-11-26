package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.SqlConnections;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaWindowTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void expensiveAppendLeavesTheHeadStaleUntilQuiet() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            assertEquals("100", UniversalEntity.meta(connection, "delta_window"));
            long seq = DeltaLog.append(connection, project, DeltaLog.PUT, "entities", "Doc#late",
                    null, "later", true);
            assertTrue(UniversalEntity.headStale(connection));
            assertEquals(0L, UniversalEntity.cursor(connection));
            assertNull(UniversalEntity.text(connection,
                    "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#late"));
            assertEquals("1", UniversalEntity.text(connection,
                    "SELECT stale FROM heads WHERE head_id = ?", UniversalEntity.HEAD_ID));

            HeadMaterializer.quiet(connection, project);
            assertFalse(UniversalEntity.headStale(connection));
            assertEquals(seq, UniversalEntity.cursor(connection));
            assertEquals("later", UniversalEntity.text(connection,
                    "SELECT payload_json FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#late"));
            assertEquals("0", UniversalEntity.text(connection,
                    "SELECT tombstone FROM head_rows WHERE head_id = ? AND tbl = ? AND pk = ?",
                    UniversalEntity.HEAD_ID, "entities", "Doc#late"));
        }
    }
}
