package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SQLite schema-4 or schema-5 database is migrated by rehydrate.
 * Unedited rows come from the files. Edited rows are imported. Run history is dropped.
 */
class DaltonSchemaUpgradeTest {
    @TempDir
    Path project;

    @Test
    void previousSchemaUpgradesInPlace() throws Exception {
        Path db = DaltonPaths.database(project);
        Files.createDirectories(db.getParent());
        String disk = "Feature: From disk\n  Scenario: Kept\n    * ASSERT: \"1\" equals \"1\"\n";
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, disk, StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(db));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)
                    """);
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
                    CREATE TABLE provenance (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        schema_version INTEGER NOT NULL,
                        hydration_id TEXT,
                        last_hydrated_at TEXT
                    )
                    """);
            statement.execute("INSERT INTO schema_meta(key, value) VALUES ('schema_version', '4')");
            statement.execute("INSERT INTO provenance(id, schema_version) VALUES (1, 4)");
            statement.execute("INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                    + "VALUES ('features/one.feature', 'abc', x'616263', 1, 'feature', 't', 'abc', 0)");
        }
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/one.feature");
            assertEquals(disk, new String(row.raw(), StandardCharsets.UTF_8));
            assertFalse(new String(row.raw(), StandardCharsets.UTF_8).equals("abc"));
            assertFalse(row.edited());
            assertEquals("feature", row.sourceType());
            assertTrue(Files.isRegularFile(db));
        }
    }

    @Test
    void schemaFiveUpgradesInPlaceAndKeepsRows() throws Exception {
        Path db = DaltonPaths.database(project);
        Files.createDirectories(db.getParent());
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(db));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE files (
                        path TEXT PRIMARY KEY,
                        content_hash TEXT NOT NULL,
                        raw_content BLOB NOT NULL,
                        mtime INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        last_hydrated_at TEXT NOT NULL,
                        last_synced_hash TEXT NOT NULL,
                        db_edited INTEGER NOT NULL DEFAULT 0,
                        git_blob TEXT,
                        binary_ref INTEGER NOT NULL DEFAULT 0,
                        byte_size INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE provenance (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        schema_version INTEGER NOT NULL,
                        hydration_id TEXT,
                        last_hydrated_at TEXT,
                        git_commit TEXT,
                        git_branch TEXT,
                        git_remote TEXT,
                        github_repo TEXT,
                        git_dirty INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE runs (
                        run_id TEXT PRIMARY KEY,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        status TEXT NOT NULL,
                        scenario TEXT NOT NULL,
                        passed INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        note TEXT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO schema_meta(key, value) VALUES ('schema_version', '5')");
            statement.execute("INSERT INTO provenance(id, schema_version, git_commit) VALUES (1, 5, 'abc123')");
            statement.execute("INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited, git_blob, binary_ref, byte_size) "
                    + "VALUES ('features/kept.feature', 'abc', x'6b657074', 1, 'feature', 't', 'abc', 1, 'blob', 0, 4)");
            statement.execute("INSERT INTO runs(run_id, started_at, status, scenario, passed, failed, note) "
                    + "VALUES ('run-5', '2026-01-01T00:00:00Z', 'passed', 'Kept', 1, 0, '')");
        }
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/kept.feature");
            assertEquals("kept", new String(row.raw(), StandardCharsets.UTF_8));
            assertTrue(row.edited());
            assertEquals("0", text(database, "SELECT COUNT(*) FROM runs"));
            assertTrue(exists(database, "step_definitions"));
            assertTrue(exists(database, "scenario_executions"));
            assertTrue(exists(database, "step_executions"));
            assertTrue(exists(database, "entity_usages"));
            assertTrue(exists(database, "log_entity_links"));
            assertTrue(exists(database, "artifacts"));
            assertEquals("0", text(database, "SELECT COUNT(*) FROM step_definitions"));
            assertTrue(Files.isRegularFile(db));
        }
    }

    private static boolean exists(DaltonDatabase database, String table) throws SQLException {
        try (var statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '"
                             + table + "'")) {
            return rows.next();
        }
    }

    private static String text(DaltonDatabase database, String sql) throws SQLException {
        try (var statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : "";
        }
    }
}
