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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreFoundationTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void schemaCreateAndMigrateUsesWalAndBusyTimeout() throws Exception {
        assertTrue(StoreDatabase.driverPresent());
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            assertEquals("wal", database.pragma("journal_mode"));
            assertEquals("5000", database.pragma("busy_timeout"));
            assertEquals(Set.of(
                    "path", "content_hash", "raw_content", "mtime", "source_type",
                    "last_hydrated_at", "last_synced_hash", "db_edited",
                    "git_blob", "binary_ref", "byte_size", "location", "git_commit",
                    "enabled", "norm_path", "folder_key"
            ), columns(database, "files"));
            assertTrue(columns(database, "schema_meta").contains("key"));
            assertTrue(columns(database, "provenance").contains("schema_version"));
            assertTrue(columns(database, "provenance").contains("git_commit"));
            assertTrue(columns(database, "provenance").contains("github_repo"));
            assertEquals(Set.of("file_path", "keyword", "name", "language", "tags", "description"),
                    columns(database, "feature_docs"));
            assertEquals(Set.of("file_path", "ord", "keyword", "name", "line", "tags", "description", "enabled"),
                    columns(database, "scenarios"));
            assertEquals(Set.of(
                    "file_path", "scenario_ord", "ord", "keyword", "text", "line",
                    "docstring", "docstring_type", "table_json", "enabled"
            ), columns(database, "steps"));
            assertEquals(Set.of(
                    "entity_type", "entity_id", "version", "payload_json", "secret_refs",
                    "source_file", "content_hash", "updated_at"
            ), columns(database, "entities"));
            assertEquals(Set.of(
                    "run_id", "started_at", "finished_at", "status", "scenario",
                    "passed", "failed", "note", "sandbox", "git_commit", "feature_path",
                    "commit_sha", "head_id", "parent_commit", "parent_head_id"
            ), columns(database, "runs"));
            assertEquals(Set.of("id", "run_id", "logged_at", "entity", "message", "sandbox"),
                    columns(database, "run_logs"));
            assertTrue(columns(database, "step_definitions").containsAll(Set.of(
                    "definition_id", "language", "pattern", "method_name", "source_path",
                    "source_line", "content_hash", "hydrated_at")));
            assertTrue(columns(database, "scenario_executions").contains("exec_id"));
            assertTrue(columns(database, "step_executions").contains("definition_id"));
            assertTrue(columns(database, "entity_usages").contains("operation"));
            assertTrue(columns(database, "log_entity_links").contains("entity_id"));
            assertTrue(columns(database, "artifacts").contains("artifact_id"));
        }
        try (StoreDatabase again = StoreDatabase.open(project)) {
            assertEquals(1, again.schemaVersion());
        }
    }

    @Test
    void newerSchemaIsRejected() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            assertEquals(1, database.schemaVersion());
            statement.execute("UPDATE schema_meta SET value = '99' WHERE `key` = 'store_schema'");
        }
        SQLException failure = assertThrows(SQLException.class, () -> StoreDatabase.open(project));
        assertTrue(failure.getMessage().contains("newer"), failure.getMessage());
    }

    @Test
    void contentHashIsSha256OfTheBytes() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                StoreHashes.sha256("abc".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(StoreHashes.sha256(new byte[] {1, 2, 3}), StoreHashes.sha256(new byte[] {1, 2, 3}));
        assertFalse(StoreHashes.sha256(new byte[] {1}).equals(StoreHashes.sha256(new byte[] {2})));
        assertEquals(64, StoreHashes.sha256(new byte[0]).length());
    }

    @Test
    void statusOnMissingDatabaseDoesNotCreateOne() {
        Capture capture = run("store", "status", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Store database missing: .pickleball/store/main.db"), capture.out);
        assertFalse(Files.exists(StorePaths.database(project)));
        assertFalse(Files.exists(StorePaths.root(project)));
    }

    @Test
    void hydrateDryRunWritesNothing() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
        }
        Capture capture = run("store", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database present"), capture.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(0, count(database));
        }
    }

    @Test
    void dryRunOnMissingDatabaseDoesNotCreateIt() {
        Capture capture = run("store", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database missing"), capture.out);
        assertFalse(Files.exists(StorePaths.database(project)));
    }

    @Test
    void hydrateWithoutDryRunCreatesAnEmptyDatabase() {
        Capture capture = run("store", "hydrate", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("hydrated inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(StorePaths.ready(project));
    }

    @Test
    void loggingIsToggleablePerEntityDateStampedAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_store_log");
        String previousEntity = System.getProperty("pkb_store_log_entity");
        String previousMax = System.getProperty("pkb_store_log_max_bytes");
        try {
            System.setProperty("pkb_store_log", "status");
            System.setProperty("pkb_store_log_entity", "files");
            StoreLog.log(project, "hydrate", "files", "skipped");
            StoreLog.log(project, "status", "other", "skipped");
            StoreLog.log(project, "status", "files", "seen");
            String text = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" status files sandbox=main seen"), text);
            assertFalse(text.contains("hydrate"), text);
            assertFalse(text.contains("other"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_store_log_max_bytes", "80");
            StoreLog.log(project, "status", "files", "x".repeat(200));
            byte[] capped = Files.readAllBytes(StorePaths.log(project));
            assertTrue(capped.length <= 80, "size " + capped.length);
            System.setProperty("pkb_store_log", "off");
            long size = Files.size(StorePaths.log(project));
            StoreLog.log(project, "status", "files", "again");
            assertEquals(size, Files.size(StorePaths.log(project)));
        } finally {
            restore("pkb_store_log", previous);
            restore("pkb_store_log_entity", previousEntity);
            restore("pkb_store_log_max_bytes", previousMax);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static int count(StoreDatabase database) throws SQLException {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM files")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static Set<String> columns(StoreDatabase database, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) names.add(rows.getString("name"));
        }
        return names;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(
                code,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private record Capture(int code, String out, String err) {
    }
}
