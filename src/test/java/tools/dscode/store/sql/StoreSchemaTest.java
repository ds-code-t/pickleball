package tools.dscode.store.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSchemaTest {
    @TempDir
    static Path project;

    static SqlStore store;

    @BeforeAll
    static void open() throws Exception {
        StoreStoreFixture.preferBinary();
        store = SqlStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        StoreStoreFixture.stop(project);
    }

    @Test
    void freshStoreIsDoltSchemaOneWithEveryTable() throws Exception {
        assertEquals("1", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'store_schema'"));
        for (String table : SqlDialect.SOURCE_TABLES) {
            assertEquals(table, tableName(table), table);
        }
        for (String table : SqlDialect.RUN_STATE_TABLES) {
            assertEquals(table, runStateTable(table), table);
            assertEquals("sandbox", store.session().scalar(
                    "SELECT name FROM pragma_table_info('" + table + "', 'rs') WHERE name = 'sandbox'"), table);
        }
        assertEquals("path", store.session().scalar(
                "SELECT name FROM pragma_table_info('files') WHERE pk > 0"));
        assertEquals("2", store.session().scalar(
                "SELECT COUNT(*) FROM pragma_table_info('scenarios') WHERE pk > 0"));
        assertEquals("blob", store.session().scalar(
                "SELECT LOWER(type) FROM pragma_table_info('files') WHERE name = 'raw_content'"));
    }

    @Test
    void runStateStaysOffABranchCreatedFromHead() throws Exception {
        store.createBranch("see");
        try (SqlStore other = SqlStore.open(project, Binding.sandbox("see"))) {
            assertEquals("see", other.session().scalar("SELECT value FROM _pkb_branch WHERE `key` = 'name'"));
            assertEquals("0", other.session().scalar("SELECT COUNT(*) FROM files"));
            assertEquals("1", other.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'store_schema'"));
            assertEquals(null, other.session().scalar(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'runs'"));
            assertEquals(null, other.session().scalar(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sandboxes'"));
        }
        assertEquals("0", store.session().scalar("SELECT COUNT(*) FROM runs"));
    }

    @Test
    void sqlLogRecordsShapeAndTimingButNotBoundValues() throws Exception {
        System.setProperty("pkb_store_log", "sql");
        try {
            store.session().scalar("SELECT value FROM schema_meta WHERE `key` = ?", "SECRET-VALUE");
            String log = Files.readString(project.resolve(".pickleball").resolve("store").resolve("store.log"));
            assertTrue(log.contains("SELECT value FROM schema_meta"), log);
            assertTrue(log.contains("sandbox=main"), log);
            assertTrue(log.contains("ms="), log);
            assertFalse(log.contains("SECRET-VALUE"), log);
            assertTrue(log.charAt(0) == '2', log.substring(0, Math.min(30, log.length())));
        } finally {
            System.clearProperty("pkb_store_log");
        }
    }

    private static String tableName(String table) throws SQLException {
        return store.session().scalar(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", table);
    }

    private static String runStateTable(String table) throws SQLException {
        return store.session().scalar(
                "SELECT name FROM rs.sqlite_master WHERE type = 'table' AND name = ?", table);
    }
}
