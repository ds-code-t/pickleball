package tools.dscode.dalton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded SQLite for the Dalton working copy. The driver is loaded by
 * reflection so {@code sqlite-jdbc} stays off the published POM and the
 * consumer shadow JAR. WAL and {@code busy_timeout} are set on every open.
 */
public final class DaltonDatabase implements AutoCloseable {
    public static final int SCHEMA_VERSION = 4;
    public static final String MISSING_DRIVER =
            "Dalton is enabled but org.xerial:sqlite-jdbc is not on the classpath. Add it as testRuntimeOnly / test scope or use the Gradle dalton* tasks.";
    static final String DRIVER = "org.sqlite.JDBC";

    private final Connection connection;
    private final Path project;
    /** One lock per database file so the first open finishes WAL setup before another connection exists. */
    private static final ConcurrentHashMap<String, Object> OPEN_LOCKS = new ConcurrentHashMap<>();

    private DaltonDatabase(Connection connection, Path project) {
        this.connection = connection;
        this.project = project;
    }

    public static boolean driverPresent() {
        try {
            Class.forName(DRIVER);
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    public static DaltonDatabase open(Path project) throws SQLException {
        if (!driverPresent()) {
            throw new SQLException(MISSING_DRIVER);
        }
        Path db = DaltonPaths.database(project);
        try {
            Files.createDirectories(db.getParent());
        } catch (java.io.IOException failure) {
            throw new SQLException("Could not create " + DaltonPaths.display(project, db.getParent()), failure);
        }
        Object lock = OPEN_LOCKS.computeIfAbsent(db.toAbsolutePath().normalize().toString(), ignored -> new Object());
        synchronized (lock) {
            Connection connection = DriverManager.getConnection(jdbcUrl(db));
            DaltonDatabase database = new DaltonDatabase(connection, project);
            database.configure();
            database.migrate();
            return database;
        }
    }

    public static String jdbcUrl(Path database) {
        String path = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "jdbc:sqlite:" + path;
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
                     "SELECT value FROM schema_meta WHERE key = 'schema_version'")) {
            if (!rows.next()) return 0;
            return Integer.parseInt(rows.getString(1));
        }
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
        }
        int current = schemaVersion();
        if (current > SCHEMA_VERSION) {
            throw new SQLException("Dalton schema " + current + " is newer than this build (" + SCHEMA_VERSION + ")");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO schema_meta(key, value) VALUES ('schema_version', '"
                    + SCHEMA_VERSION + "') ON CONFLICT(key) DO UPDATE SET value = '" + SCHEMA_VERSION + "'");
            statement.execute("INSERT INTO provenance(id, schema_version) VALUES (1, " + SCHEMA_VERSION
                    + ") ON CONFLICT(id) DO UPDATE SET schema_version = " + SCHEMA_VERSION);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
