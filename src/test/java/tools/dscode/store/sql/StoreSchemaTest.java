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
    void freshStoreIsStoreSchemaOneWithEveryTable() throws Exception {
        assertEquals("1", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'store_schema'"));
        assertEquals(1, SqlDialect.SCHEMA_VERSION);
        assertEquals("1", store.session().scalar(
                "SELECT value FROM schema_meta WHERE `key` = 'universal_entity'"));
        assertEquals("100", store.session().scalar(
                "SELECT value FROM schema_meta WHERE `key` = 'delta_window'"));
        assertEquals("1", store.session().scalar(
                "SELECT value FROM schema_meta WHERE `key` = 'hydration_spine'"));
        assertEquals("enabled", column("files", "enabled"));
        assertEquals("norm_path", column("files", "norm_path"));
        assertEquals("folder_key", column("files", "folder_key"));
        assertEquals("enabled", column("scenarios", "enabled"));
        assertEquals("enabled", column("steps", "enabled"));
        assertEquals("enabled", column("step_definitions", "enabled"));
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
    void openAddsContextColumnsWhenTheSpineMarkerIsAlreadySet() throws Exception {
        assertEquals("1", store.session().scalar(
                "SELECT value FROM schema_meta WHERE `key` = 'hydration_spine'"));
        store.session().script("ALTER TABLE steps DROP COLUMN context_mode");
        store.session().script("ALTER TABLE steps DROP COLUMN context_scope");
        try {
            assertEquals(null, column("steps", "context_mode"));
            int fullBefore = SqlDialect.schemaFullEnsures();
            SqlDialect.ensureSchema(store.session());
            assertEquals(fullBefore, SqlDialect.schemaFullEnsures(), "column repair ran full DDL");
            assertEquals("context_mode", column("steps", "context_mode"));
            assertEquals("context_scope", column("steps", "context_scope"));
        } finally {
            SqlDialect.ensureSchema(store.session());
        }
    }

    @Test
    void openAddsHostWhenTheContextColumnsAreAlreadyPresent() throws Exception {
        Path nested = project.resolve("host-repair");
        Files.createDirectories(nested);
        try (SqlStore fresh = SqlStore.open(nested, Binding.main())) {
            fresh.session().script("DROP TRIGGER IF EXISTS step_definitions_pkb_insert");
            fresh.session().script("DROP TRIGGER IF EXISTS step_definitions_pkb_update");
            fresh.session().script("DROP TRIGGER IF EXISTS step_definitions_pkb_delete");
            fresh.session().script("ALTER TABLE step_definitions DROP COLUMN host");
            assertEquals(null, fresh.session().scalar(
                    "SELECT name FROM pragma_table_info('step_definitions') WHERE name = ?", "host"));
            int fullBefore = SqlDialect.schemaFullEnsures();
            SqlDialect.ensureSchema(fresh.session());
            assertEquals(fullBefore, SqlDialect.schemaFullEnsures(), "host repair ran full DDL");
            assertEquals("host", fresh.session().scalar(
                    "SELECT name FROM pragma_table_info('step_definitions') WHERE name = ?", "host"));
        }
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

    private static String column(String table, String name) throws SQLException {
        return store.session().scalar(
                "SELECT name FROM pragma_table_info('" + table + "') WHERE name = ?", name);
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
