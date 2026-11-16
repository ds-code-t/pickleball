package tools.dscode.dolt.store;

import tools.dscode.dolt.DoltPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/** Opens one bound session. Schema is created on main before the session is returned. */
public final class DoltStore implements AutoCloseable {
    private final Path project;
    private final Binding binding;
    private final DoltSession session;

    private DoltStore(Path project, Binding binding, DoltSession session) {
        this.project = project;
        this.binding = binding;
        this.session = session;
    }

    public static DoltStore open(Path project, Binding binding) throws IOException, SQLException {
        Path root = project.toAbsolutePath().normalize();
        Binding chosen = binding == null ? Binding.main() : binding;
        try (DoltSession schema = DoltConnections.borrow(root, Binding.main())) {
            DoltDialect.ensureSchema(schema);
        }
        return new DoltStore(root, chosen, DoltConnections.borrow(root, chosen));
    }

    public Path project() {
        return project;
    }

    public Binding binding() {
        return binding;
    }

    public DoltSession session() {
        return session;
    }

    /** Copies main into an independent sandbox database. */
    public void createBranch(String sandboxName) throws SQLException {
        Binding check = Binding.sandbox(sandboxName);
        if (!binding.primary()) throw new SQLException("error: create a sandbox branch from main");
        Path target = DoltPaths.sandboxDatabase(project, check.name());
        if (Files.exists(target)) {
            throw new SQLException("error: sandbox " + check.name() + " already exists");
        }
        Path tmp = target.resolveSibling("source.db.tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(tmp);
            try (Connection copy = SqliteOpen.open(DoltPaths.mainDatabase(project))) {
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
                    writeBranch(statement, "created_at", Instant.now().toString());
                    writeBranch(statement, "created_from_commit", "");
                    writeBranch(statement, "created_from_dirty", "0");
                    writeBranch(statement, "owner", "");
                    writeBranch(statement, "ttl", "");
                    writeBranch(statement, "store_schema", "1");
                    statement.execute("DELETE FROM _pkb_base");
                    statement.execute("""
                            INSERT INTO _pkb_base(tbl, pk, base_hash, location, git_commit)
                            SELECT 'files', path, content_hash, location, git_commit FROM files
                            """);
                    statement.execute("""
                            INSERT INTO _pkb_base(tbl, pk, base_hash, location, git_commit)
                            SELECT 'entities', concat(entity_type, '#', entity_id), content_hash, 'db', '' FROM entities
                            """);
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
