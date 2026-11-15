package tools.dscode.dolt.store;

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

class DoltSchemaTest {
    @TempDir
    static Path project;

    static DoltStore store;

    @BeforeAll
    static void open() throws Exception {
        DoltStoreFixture.preferBinary();
        store = DoltStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        DoltStoreFixture.stop(project);
    }

    @Test
    void freshStoreIsDoltSchemaOneWithEveryTable() throws Exception {
        assertEquals("1", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'dolt_schema'"));
        for (String table : DoltDialect.SOURCE_TABLES) {
            assertEquals(table, tableName(table), table);
        }
        for (String table : DoltDialect.RUN_STATE_TABLES) {
            assertEquals(table, tableName(table), table);
            assertEquals("sandbox", store.session().scalar("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'sandbox'
                    """, table), table);
        }
        assertEquals("path", store.session().scalar("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'files' AND column_key = 'PRI'
                """));
        assertEquals("2", store.session().scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'scenarios' AND column_key = 'PRI'
                """));
        assertEquals("longblob", store.session().scalar("""
                SELECT LOWER(data_type) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'files' AND column_name = 'raw_content'
                """));
    }

    @Test
    void runStateStaysOffABranchCreatedFromHead() throws Exception {
        store.createBranch("see");
        try (DoltStore other = DoltStore.open(project, Binding.sandbox("see"))) {
            assertEquals("sbx-see", other.session().scalar("SELECT active_branch()"));
            assertEquals("0", other.session().scalar("SELECT COUNT(*) FROM files"));
            assertEquals("1", other.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'dolt_schema'"));
            SQLException missing = assertThrows(SQLException.class,
                    () -> other.session().scalar("SELECT COUNT(*) FROM runs"));
            String message = missing.getMessage() == null ? "" : missing.getMessage().toLowerCase(Locale.ROOT);
            assertTrue(message.contains("not found") || message.contains("exist"), message);
            SQLException sandboxes = assertThrows(SQLException.class,
                    () -> other.session().scalar("SELECT COUNT(*) FROM sandboxes"));
            assertTrue(sandboxes.getMessage() != null && !sandboxes.getMessage().isBlank());
        }
        assertEquals("0", store.session().scalar("SELECT COUNT(*) FROM runs"));
    }

    @Test
    void sqlLogRecordsShapeAndTimingButNotBoundValues() throws Exception {
        System.setProperty("pkb_dolt_log", "sql");
        try {
            store.session().scalar("SELECT value FROM schema_meta WHERE `key` = ?", "SECRET-VALUE");
            String log = Files.readString(project.resolve(".pickleball").resolve("dolt").resolve("dolt.log"));
            assertTrue(log.contains("SELECT value FROM schema_meta"), log);
            assertTrue(log.contains("sandbox=main"), log);
            assertTrue(log.contains("ms="), log);
            assertFalse(log.contains("SECRET-VALUE"), log);
            assertTrue(log.charAt(0) == '2', log.substring(0, Math.min(30, log.length())));
        } finally {
            System.clearProperty("pkb_dolt_log");
        }
    }

    private static String tableName(String table) throws SQLException {
        return store.session().scalar("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, table);
    }
}
