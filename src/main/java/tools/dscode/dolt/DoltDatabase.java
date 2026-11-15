package tools.dscode.dolt;

import tools.dscode.dolt.server.DoltServer;
import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltConnections;
import tools.dscode.dolt.store.DoltDialect;
import tools.dscode.dolt.store.DoltJdbc;
import tools.dscode.dolt.store.DoltSession;
import tools.dscode.dolt.store.DoltSql;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Dolt working copy for the Dolt layer. The connection is a pooled MariaDB
 * session on branch {@code main}. SQLite is not opened here.
 */
public final class DoltDatabase implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    public static final String MISSING_DRIVER = DoltJdbc.MISSING_DRIVER;

    private final Connection connection;
    private final Path project;

    private DoltDatabase(Connection connection, Path project) {
        this.connection = connection;
        this.project = project;
    }

    public static boolean driverPresent() {
        return DoltJdbc.driverPresent();
    }

    public static DoltDatabase open(Path project) throws SQLException {
        return open(project, DoltScope.current());
    }

    public static DoltDatabase open(Path project, Binding binding) throws SQLException {
        if (!driverPresent()) throw new SQLException(MISSING_DRIVER);
        Binding chosen = binding == null ? Binding.main() : binding;
        try {
            DoltMigrate.auto(project);
            DoltServer.retain(project);
            try (DoltSession schema = DoltConnections.borrow(project, Binding.main())) {
                DoltDialect.ensureSchema(schema);
                if (!chosen.primary()) {
                    String name = schema.scalar("SELECT name FROM sandboxes WHERE name = ?", chosen.name());
                    if (name == null) {
                        throw new SQLException("unknown sandbox " + chosen.name()
                                + "\nfix: dolt sandbox create " + chosen.name());
                    }
                    schema.update(
                            "UPDATE sandboxes SET last_used = ? WHERE name = ?",
                            java.time.Instant.now().toString(),
                            chosen.name());
                }
            }
            DoltSession session = DoltConnections.borrow(project, chosen);
            try {
                if (!chosen.primary()) {
                    String active = session.scalar(DoltDialect.ACTIVE_BRANCH);
                    if (active != null && !chosen.branch().equals(active)) {
                        throw new SQLException("connection is on " + active + " not " + chosen.branch());
                    }
                }
                return new DoltDatabase(DoltSql.wrap(session), project.toAbsolutePath().normalize());
            } catch (SQLException | RuntimeException failure) {
                session.close();
                throw failure;
            }
        } catch (java.io.IOException failure) {
            throw new SQLException(failure.getMessage() == null ? "Dolt server did not start" : failure.getMessage(), failure);
        }
    }

    public Path project() {
        return project;
    }

    public Connection connection() {
        return connection;
    }

    public int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT value FROM schema_meta WHERE `key` = 'dolt_schema'")) {
            if (!rows.next() || rows.getString(1) == null) return 0;
            return Integer.parseInt(rows.getString(1).trim());
        }
    }

    /** Text columns of the store, so tests can prove a secret was not written. */
    public static String storedText(Path project) throws SQLException {
        if (!DoltPaths.ready(project)) return "";
        StringBuilder text = new StringBuilder();
        String[] queries = {
                "SELECT payload_json FROM entities",
                "SELECT secret_refs FROM entities",
                "SELECT note FROM runs",
                "SELECT message FROM run_logs",
                "SELECT value_ref FROM entity_usages",
                "SELECT CAST(raw_content AS CHAR) FROM files"
        };
        try (DoltDatabase database = open(project);
             Statement statement = database.connection().createStatement()) {
            for (String sql : queries) {
                try (ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String value = rows.getString(1);
                        if (value != null) text.append(value).append('\n');
                    }
                }
            }
        }
        return text.toString();
    }

    public String pragma(String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            return rows.next() ? rows.getString(1) : "";
        }
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            mode.next();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS files (
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
                    CREATE TABLE IF NOT EXISTS provenance (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        schema_version INTEGER NOT NULL,
                        hydration_id TEXT,
                        last_hydrated_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS feature_docs (
                        file_path TEXT PRIMARY KEY,
                        keyword TEXT NOT NULL,
                        name TEXT NOT NULL,
                        language TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        description TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS scenarios (
                        file_path TEXT NOT NULL,
                        ord INTEGER NOT NULL,
                        keyword TEXT NOT NULL,
                        name TEXT NOT NULL,
                        line INTEGER NOT NULL,
                        tags TEXT NOT NULL,
                        description TEXT NOT NULL,
                        PRIMARY KEY (file_path, ord)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS steps (
                        file_path TEXT NOT NULL,
                        scenario_ord INTEGER NOT NULL,
                        ord INTEGER NOT NULL,
                        keyword TEXT NOT NULL,
                        text TEXT NOT NULL,
                        line INTEGER NOT NULL,
                        docstring TEXT,
                        docstring_type TEXT,
                        table_json TEXT,
                        PRIMARY KEY (file_path, scenario_ord, ord)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS example_blocks (
                        file_path TEXT NOT NULL,
                        scenario_ord INTEGER NOT NULL,
                        ord INTEGER NOT NULL,
                        line INTEGER NOT NULL,
                        tags TEXT NOT NULL,
                        PRIMARY KEY (file_path, scenario_ord, ord)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS entities (
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        payload_json TEXT NOT NULL,
                        secret_refs TEXT NOT NULL,
                        source_file TEXT,
                        content_hash TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (entity_type, entity_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runs (
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
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS run_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id TEXT NOT NULL,
                        logged_at TEXT NOT NULL,
                        entity TEXT NOT NULL,
                        message TEXT NOT NULL,
                        FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS step_definitions (
                        definition_id TEXT PRIMARY KEY,
                        language TEXT NOT NULL,
                        pattern TEXT NOT NULL,
                        method_name TEXT,
                        source_path TEXT NOT NULL,
                        source_line INTEGER NOT NULL,
                        content_hash TEXT NOT NULL,
                        hydrated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS scenario_executions (
                        exec_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
                        scenario_key TEXT NOT NULL,
                        scenario_hash TEXT NOT NULL,
                        examples_row INTEGER,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        status TEXT NOT NULL,
                        passed INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS step_executions (
                        step_exec_id TEXT PRIMARY KEY,
                        scenario_exec_id TEXT NOT NULL REFERENCES scenario_executions(exec_id) ON DELETE CASCADE,
                        step_ord INTEGER NOT NULL,
                        step_text TEXT NOT NULL,
                        step_text_hash TEXT NOT NULL,
                        definition_id TEXT REFERENCES step_definitions(definition_id),
                        source_line INTEGER,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        status TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS entity_usages (
                        usage_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        step_exec_id TEXT NOT NULL REFERENCES step_executions(step_exec_id) ON DELETE CASCADE,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        field TEXT,
                        operation TEXT NOT NULL,
                        value_ref TEXT,
                        logged_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS log_entity_links (
                        log_id INTEGER NOT NULL REFERENCES run_logs(id) ON DELETE CASCADE,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        PRIMARY KEY (log_id, entity_type, entity_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS artifacts (
                        artifact_id TEXT PRIMARY KEY,
                        run_id TEXT REFERENCES runs(run_id) ON DELETE CASCADE,
                        step_exec_id TEXT REFERENCES step_executions(step_exec_id) ON DELETE SET NULL,
                        kind TEXT NOT NULL,
                        path TEXT NOT NULL,
                        retained_at TEXT NOT NULL
                    )
                    """);
        }
        int current = schemaVersion();
        if (current > SCHEMA_VERSION) {
            throw new SQLException("Dolt schema " + current + " is newer than this build (" + SCHEMA_VERSION + ")");
        }
        addColumn("files", "git_blob", "TEXT");
        addColumn("files", "binary_ref", "INTEGER NOT NULL DEFAULT 0");
        addColumn("files", "byte_size", "INTEGER");
        addColumn("provenance", "git_commit", "TEXT");
        addColumn("provenance", "git_branch", "TEXT");
        addColumn("provenance", "git_remote", "TEXT");
        addColumn("provenance", "github_repo", "TEXT");
        addColumn("provenance", "git_dirty", "INTEGER");
        addColumn("step_definitions", "removed", "INTEGER NOT NULL DEFAULT 0");
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO schema_meta(key, value) VALUES ('schema_version', '"
                    + SCHEMA_VERSION + "') ON CONFLICT(key) DO UPDATE SET value = '" + SCHEMA_VERSION + "'");
            statement.execute("INSERT INTO provenance(id, schema_version) VALUES (1, " + SCHEMA_VERSION
                    + ") ON CONFLICT(id) DO UPDATE SET schema_version = " + SCHEMA_VERSION);
        }
        indexExistingExampleTags();
    }

    /** One-time read of Examples tags already stored on steps. Later hydrates write the rows themselves. */
    private void indexExistingExampleTags() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT value FROM schema_meta WHERE key = 'example_blocks'")) {
            if (rows.next()) return;
        }
        java.util.List<String[]> found = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT file_path, scenario_ord, ord, line, text FROM steps WHERE keyword = 'Examples'")) {
            while (rows.next()) {
                String tags = DoltGherkin.examplesTags(rows.getString(5));
                if (tags.isEmpty()) continue;
                found.add(new String[] {
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), tags
                });
            }
        }
        try (java.sql.PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO example_blocks(file_path, scenario_ord, ord, line, tags)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (String[] row : found) {
                insert.setString(1, row[0]);
                insert.setInt(2, Integer.parseInt(row[1]));
                insert.setInt(3, Integer.parseInt(row[2]));
                insert.setInt(4, Integer.parseInt(row[3]));
                insert.setString(5, row[4]);
                insert.executeUpdate();
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO schema_meta(key, value) VALUES ('example_blocks', '1')");
        }
    }

    private void addColumn(String table, String column, String definition) throws SQLException {
        if (hasColumn(table, column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
