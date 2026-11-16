package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class DoltFoundationTest {
    @TempDir
    Path project;

    @Test
    void schemaCreateAndMigrateUsesWalAndBusyTimeout() throws Exception {
        assertTrue(DoltDatabase.driverPresent());
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            assertEquals("wal", database.pragma("journal_mode"));
            assertEquals("5000", database.pragma("busy_timeout"));
            assertEquals(Set.of(
                    "path", "content_hash", "raw_content", "mtime", "source_type",
                    "last_hydrated_at", "last_synced_hash", "db_edited",
                    "git_blob", "binary_ref", "byte_size", "location", "git_commit"
            ), columns(database, "files"));
            assertTrue(columns(database, "schema_meta").contains("key"));
            assertTrue(columns(database, "provenance").contains("schema_version"));
            assertTrue(columns(database, "provenance").contains("git_commit"));
            assertTrue(columns(database, "provenance").contains("github_repo"));
            assertEquals(Set.of("file_path", "keyword", "name", "language", "tags", "description"),
                    columns(database, "feature_docs"));
            assertEquals(Set.of("file_path", "ord", "keyword", "name", "line", "tags", "description"),
                    columns(database, "scenarios"));
            assertEquals(Set.of(
                    "file_path", "scenario_ord", "ord", "keyword", "text", "line",
                    "docstring", "docstring_type", "table_json"
            ), columns(database, "steps"));
            assertEquals(Set.of(
                    "entity_type", "entity_id", "version", "payload_json", "secret_refs",
                    "source_file", "content_hash", "updated_at"
            ), columns(database, "entities"));
            assertEquals(Set.of(
                    "run_id", "started_at", "finished_at", "status", "scenario",
                    "passed", "failed", "note", "sandbox"
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
        try (DoltDatabase again = DoltDatabase.open(project)) {
            assertEquals(1, again.schemaVersion());
        }
    }

    @Test
    void newerSchemaIsRejected() throws Exception {
        try (DoltDatabase database = DoltDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            assertEquals(1, database.schemaVersion());
            statement.execute("UPDATE schema_meta SET value = '99' WHERE `key` = 'store_schema'");
        }
        SQLException failure = assertThrows(SQLException.class, () -> DoltDatabase.open(project));
        assertTrue(failure.getMessage().contains("newer"), failure.getMessage());
    }

    @Test
    void contentHashIsSha256OfTheBytes() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DoltHashes.sha256("abc".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(DoltHashes.sha256(new byte[] {1, 2, 3}), DoltHashes.sha256(new byte[] {1, 2, 3}));
        assertFalse(DoltHashes.sha256(new byte[] {1}).equals(DoltHashes.sha256(new byte[] {2})));
        assertEquals(64, DoltHashes.sha256(new byte[0]).length());
    }

    @Test
    void statusOnMissingDatabaseDoesNotCreateOne() {
        Capture capture = run("dolt", "status", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Dolt database missing: .pickleball/dolt/main.db"), capture.out);
        assertFalse(Files.exists(DoltPaths.database(project)));
        assertFalse(Files.exists(DoltPaths.root(project)));
    }

    @Test
    void hydrateDryRunWritesNothing() throws Exception {
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
        }
        Capture capture = run("dolt", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database present"), capture.out);
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertEquals(0, count(database));
        }
    }

    @Test
    void dryRunOnMissingDatabaseDoesNotCreateIt() {
        Capture capture = run("dolt", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database missing"), capture.out);
        assertFalse(Files.exists(DoltPaths.database(project)));
    }

    @Test
    void hydrateWithoutDryRunCreatesAnEmptyDatabase() {
        Capture capture = run("dolt", "hydrate", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("hydrated inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(DoltPaths.ready(project));
    }

    @Test
    void loggingIsToggleablePerEntityDateStampedAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_dolt_log");
        String previousEntity = System.getProperty("pkb_dolt_log_entity");
        String previousMax = System.getProperty("pkb_dolt_log_max_bytes");
        try {
            System.setProperty("pkb_dolt_log", "status");
            System.setProperty("pkb_dolt_log_entity", "files");
            DoltLog.log(project, "hydrate", "files", "skipped");
            DoltLog.log(project, "status", "other", "skipped");
            DoltLog.log(project, "status", "files", "seen");
            String text = Files.readString(DoltPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" status files sandbox=main seen"), text);
            assertFalse(text.contains("hydrate"), text);
            assertFalse(text.contains("other"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_dolt_log_max_bytes", "80");
            DoltLog.log(project, "status", "files", "x".repeat(200));
            byte[] capped = Files.readAllBytes(DoltPaths.log(project));
            assertTrue(capped.length <= 80, "size " + capped.length);
            System.setProperty("pkb_dolt_log", "off");
            long size = Files.size(DoltPaths.log(project));
            DoltLog.log(project, "status", "files", "again");
            assertEquals(size, Files.size(DoltPaths.log(project)));
        } finally {
            restore("pkb_dolt_log", previous);
            restore("pkb_dolt_log_entity", previousEntity);
            restore("pkb_dolt_log_max_bytes", previousMax);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static int count(DoltDatabase database) throws SQLException {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM files")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static Set<String> columns(DoltDatabase database, String table) throws SQLException {
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
        int code = DoltCli.execute(
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
