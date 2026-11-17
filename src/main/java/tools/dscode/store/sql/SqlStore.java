package tools.dscode.store.sql;

import tools.dscode.store.StoreHashes;
import tools.dscode.store.StorePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Opens one bound session. Schema is created on main before the session is returned. */
public final class SqlStore implements AutoCloseable {
    private final Path project;
    private final Binding binding;
    private final SqlSession session;

    private SqlStore(Path project, Binding binding, SqlSession session) {
        this.project = project;
        this.binding = binding;
        this.session = session;
    }

    public static SqlStore open(Path project, Binding binding) throws IOException, SQLException {
        Path root = project.toAbsolutePath().normalize();
        Binding chosen = binding == null ? Binding.main() : binding;
        try (SqlSession schema = SqlConnections.borrow(root, Binding.main())) {
            SqlDialect.ensureSchema(schema);
        }
        return new SqlStore(root, chosen, SqlConnections.borrow(root, chosen));
    }

    public Path project() {
        return project;
    }

    public Binding binding() {
        return binding;
    }

    public SqlSession session() {
        return session;
    }

    /** Copies main into an independent sandbox database. */
    public void createBranch(String sandboxName) throws SQLException {
        createBranch(sandboxName, Map.of());
    }

    /**
     * Copies main into an independent sandbox file. Branch metadata is written
     * into that copy before anything opens {@code source.db}.
     */
    public void createBranch(String sandboxName, Map<String, String> meta) throws SQLException {
        Binding check = Binding.sandbox(sandboxName);
        if (!binding.primary()) throw new SQLException("error: create a sandbox branch from main");
        Path target = StorePaths.sandboxDatabase(project, check.name());
        if (Files.exists(target)) {
            throw new SQLException("error: sandbox " + check.name() + " already exists");
        }
        String now = Instant.now().toString();
        Path tmp = target.resolveSibling("source.db.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(tmp);
            try (Connection copy = SqliteOpen.open(StorePaths.mainDatabase(project))) {
                copy.setAutoCommit(true);
                try (Statement statement = copy.createStatement()) {
                    statement.execute("VACUUM INTO '" + sqlPath(tmp) + "'");
                }
            }
            try (Connection branched = SqliteOpen.open(tmp)) {
                branched.setAutoCommit(true);
                try (Statement statement = branched.createStatement()) {
                    statement.execute("DELETE FROM _pkb_changes");
                    writeBranch(statement, "name", check.name());
                    writeBranch(statement, "created_at", now);
                    writeBranch(statement, "created_from_commit", meta(meta, "created_from_commit"));
                    writeBranch(statement, "created_from_dirty", meta(meta, "created_from_dirty").isBlank()
                            ? "0" : meta(meta, "created_from_dirty"));
                    writeBranch(statement, "owner", meta(meta, "owner"));
                    writeBranch(statement, "ttl", meta(meta, "ttl").isBlank() ? "24h" : meta(meta, "ttl"));
                    writeBranch(statement, "store_schema", "1");
                    writeBranch(statement, "last_used", now);
                    statement.execute("DELETE FROM _pkb_base");
                    statement.execute("""
                            INSERT INTO _pkb_base(tbl, pk, base_hash, location, git_commit)
                            SELECT 'files', path, content_hash, location, git_commit FROM files
                            """);
                    writeEntityBase(branched);
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new SQLException(failure.getMessage() == null ? "sandbox copy failed" : failure.getMessage(), failure);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // The moved file is already gone. A leftover temp is retried on the next create.
            }
        }
    }

    /** Rewrites {@code _pkb_base} from the current files and entities on this connection. */
    public static void writeBase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM _pkb_base");
            statement.execute("""
                    INSERT INTO _pkb_base(tbl, pk, base_hash, location, git_commit)
                    SELECT 'files', path, content_hash, location, git_commit FROM files
                    """);
        }
        writeEntityBase(connection);
    }

    private static void writeEntityBase(Connection connection) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT entity_type, entity_id, payload_json, secret_refs, content_hash FROM entities")) {
            while (result.next()) {
                rows.add(new String[] {
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5)
                });
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO _pkb_base(tbl, pk, base_hash, location, git_commit) VALUES ('entities', ?, ?, 'db', '')")) {
            for (String[] row : rows) {
                insert.setString(1, row[0] + "#" + row[1]);
                insert.setString(2, StoreHashes.entityIdentity(row[2], row[3], row[4]));
                insert.executeUpdate();
            }
        }
    }

    private static String meta(Map<String, String> meta, String key) {
        if (meta == null) return "";
        String value = meta.get(key);
        return value == null ? "" : value;
    }

    private static void writeBranch(Statement statement, String key, String value) throws SQLException {
        String safeKey = key.replace("'", "''");
        String safeValue = value == null ? "" : value.replace("'", "''");
        statement.execute("INSERT INTO _pkb_branch(`key`, value) VALUES ('" + safeKey + "', '" + safeValue + "') "
                + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value");
    }

    private static String sqlPath(Path file) {
        return file.toAbsolutePath().toString().replace("'", "''");
    }

    @Override
    public void close() {
        session.close();
    }
}
