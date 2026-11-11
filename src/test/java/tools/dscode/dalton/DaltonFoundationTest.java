package tools.dscode.dalton;

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

class DaltonFoundationTest {
    @TempDir
    Path project;

    @Test
    void schemaCreateAndMigrateUsesWalAndBusyTimeout() throws Exception {
        assertTrue(DaltonDatabase.driverPresent());
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            assertEquals("wal", database.pragma("journal_mode"));
            assertEquals("5000", database.pragma("busy_timeout"));
            assertEquals(Set.of(
                    "path", "content_hash", "raw_content", "mtime", "source_type",
                    "last_hydrated_at", "last_synced_hash", "db_edited"
            ), columns(database, "files"));
            assertTrue(columns(database, "schema_meta").contains("key"));
            assertTrue(columns(database, "provenance").contains("schema_version"));
        }
        try (DaltonDatabase again = DaltonDatabase.open(project)) {
            assertEquals(1, again.schemaVersion());
        }
    }

    @Test
    void newerSchemaIsRejected() throws Exception {
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
        }
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(DaltonPaths.database(project)));
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE schema_meta SET value = '99' WHERE key = 'schema_version'");
        }
        SQLException failure = assertThrows(SQLException.class, () -> DaltonDatabase.open(project));
        assertTrue(failure.getMessage().contains("newer"), failure.getMessage());
    }

    @Test
    void contentHashIsSha256OfTheBytes() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DaltonHashes.sha256("abc".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(DaltonHashes.sha256(new byte[] {1, 2, 3}), DaltonHashes.sha256(new byte[] {1, 2, 3}));
        assertFalse(DaltonHashes.sha256(new byte[] {1}).equals(DaltonHashes.sha256(new byte[] {2})));
        assertEquals(64, DaltonHashes.sha256(new byte[0]).length());
    }

    @Test
    void statusOnMissingDatabaseDoesNotCreateOne() {
        Capture capture = run("dalton", "status", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Dalton database missing: .pickleball/dalton/dalton.db"), capture.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));
        assertFalse(Files.exists(DaltonPaths.root(project)));
    }

    @Test
    void hydrateDryRunWritesNothing() throws Exception {
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
        }
        Capture capture = run("dalton", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database present"), capture.out);
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(0, count(database));
        }
    }

    @Test
    void dryRunOnMissingDatabaseDoesNotCreateIt() {
        Capture capture = run("dalton", "hydrate", "--dry-run", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(capture.out.contains("database missing"), capture.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));
    }

    @Test
    void hydrateWithoutDryRunCreatesAnEmptyDatabase() {
        Capture capture = run("dalton", "hydrate", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("hydrated inserted=0 updated=0 skipped=0 conflicts=0"), capture.out);
        assertTrue(Files.isRegularFile(DaltonPaths.database(project)));
    }

    @Test
    void loggingIsToggleablePerEntityDateStampedAndSizeCapped() throws Exception {
        String previous = System.getProperty("pkb_dalton_log");
        String previousEntity = System.getProperty("pkb_dalton_log_entity");
        String previousMax = System.getProperty("pkb_dalton_log_max_bytes");
        try {
            System.setProperty("pkb_dalton_log", "status");
            System.setProperty("pkb_dalton_log_entity", "files");
            DaltonLog.log(project, "hydrate", "files", "skipped");
            DaltonLog.log(project, "status", "other", "skipped");
            DaltonLog.log(project, "status", "files", "seen");
            String text = Files.readString(DaltonPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" status files seen"), text);
            assertFalse(text.contains("hydrate"), text);
            assertFalse(text.contains("other"), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_dalton_log_max_bytes", "80");
            DaltonLog.log(project, "status", "files", "x".repeat(200));
            byte[] capped = Files.readAllBytes(DaltonPaths.log(project));
            assertTrue(capped.length <= 80, "size " + capped.length);
            System.setProperty("pkb_dalton_log", "off");
            long size = Files.size(DaltonPaths.log(project));
            DaltonLog.log(project, "status", "files", "again");
            assertEquals(size, Files.size(DaltonPaths.log(project)));
        } finally {
            restore("pkb_dalton_log", previous);
            restore("pkb_dalton_log_entity", previousEntity);
            restore("pkb_dalton_log_max_bytes", previousMax);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static int count(DaltonDatabase database) throws SQLException {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM files")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static Set<String> columns(DaltonDatabase database, String table) throws SQLException {
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
        int code = DaltonCli.execute(
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
