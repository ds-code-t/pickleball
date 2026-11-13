package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A database created with schema 4 opens, migrates, and keeps its rows. */
class DaltonSchemaUpgradeTest {
    @TempDir
    Path project;

    @Test
    void previousSchemaUpgradesInPlace() throws Exception {
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
            assertEquals(5, database.schemaVersion());
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/one.feature");
            assertEquals("abc", new String(row.raw(), StandardCharsets.UTF_8));
            assertFalse(row.binaryRef());
            assertEquals("feature", row.sourceType());
        }
    }
}
