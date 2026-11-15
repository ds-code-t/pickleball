package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.DoltCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltLegacyMigrationTest {
    @TempDir
    Path project;

    @Test
    void editedRowIsImportedHistoryIsDroppedAndTheLegacyFileStays() throws Exception {
        seed();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                new String[] {"dolt", "migrate", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + stderr);
        assertTrue(out.contains("migrated edits=1"), out);
        assertTrue(out.contains("history not imported: 2 runs"), out);
        assertTrue(Files.isRegularFile(DaltonPaths.database(project)));
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/kept.feature");
            assertEquals("kept", new String(row.raw(), StandardCharsets.UTF_8));
            assertTrue(row.edited());
            assertEquals("abc", row.lastSyncedHash());
            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM runs")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void removeLegacyDeletesTheDaltonDirectory() throws Exception {
        seed();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                new String[] {"dolt", "migrate", "--remove-legacy", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertEquals(0, code, stdout.toString(StandardCharsets.UTF_8) + stderr);
        assertFalse(Files.exists(DaltonPaths.root(project)));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("migrated edits=1"));
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        seed();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                new String[] {"dolt", "migrate", "--dry-run", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code, stdout.toString(StandardCharsets.UTF_8));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("migrated edits=1"), stdout.toString());
        assertFalse(DaltonPaths.ready(project));
        assertTrue(Files.isRegularFile(DaltonPaths.database(project)));
    }

    private void seed() throws Exception {
        Path db = DaltonPaths.database(project);
        Files.createDirectories(db.getParent());
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(db));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE files (
                        path TEXT PRIMARY KEY,
                        content_hash TEXT NOT NULL,
                        raw_content BLOB NOT NULL,
                        mtime INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        last_hydrated_at TEXT NOT NULL,
                        last_synced_hash TEXT NOT NULL,
                        db_edited INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE runs (
                        run_id TEXT PRIMARY KEY,
                        started_at TEXT NOT NULL,
                        status TEXT NOT NULL,
                        scenario TEXT NOT NULL,
                        passed INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        note TEXT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                    + "VALUES ('features/kept.feature', 'abc', x'6b657074', 1, 'feature', 't', 'abc', 1)");
            statement.execute("INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                    + "VALUES ('features/plain.feature', 'def', x'706c61696e', 1, 'feature', 't', 'def', 0)");
            statement.execute("INSERT INTO runs(run_id, started_at, status, scenario, note) VALUES ('r1', 't', 'passed', 'A', '')");
            statement.execute("INSERT INTO runs(run_id, started_at, status, scenario, note) VALUES ('r2', 't', 'failed', 'B', '')");
        }
    }
}
