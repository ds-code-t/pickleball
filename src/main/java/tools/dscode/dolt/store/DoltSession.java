package tools.dscode.dolt.store;

import tools.dscode.dolt.DoltLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One borrowed connection, already bound to a branch. */
public final class DoltSession implements AutoCloseable {
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:into|update|from)\\s+`?([a-z0-9_]+)");

    private final Path project;
    private final Binding binding;
    private final Connection connection;
    private final DoltConnections.Pool pool;
    private boolean closed;
    private boolean inTransaction;

    DoltSession(Path project, Binding binding, Connection connection, DoltConnections.Pool pool) {
        this.project = project;
        this.binding = binding;
        this.connection = connection;
        this.pool = pool;
    }

    public Path project() {
        return project;
    }

    public Binding binding() {
        return binding;
    }

    public String branch() {
        return binding.branch();
    }

    public Connection connection() {
        return connection;
    }

    public void script(String sql) throws SQLException {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            drain(statement, statement.execute(sql));
        } finally {
            log(sql, started);
        }
    }

    /** Bound stored-procedure call. Arguments are parameters, never concatenated SQL. */
    public void call(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            drain(statement, statement.execute());
        } finally {
            log(sql, started);
        }
    }

    public int update(String sql, Object... args) throws SQLException {
        if (inTransaction) return updateOnce(sql, args);
        int[] count = new int[1];
        DoltDialect.retry(tableOf(sql), () -> count[0] = updateOnce(sql, args));
        return count[0];
    }

    public long insertId(String sql, Object... args) throws SQLException {
        long[] id = new long[1];
        DoltDialect.SqlTask task = () -> {
            long started = System.nanoTime();
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, args);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no generated key");
                    id[0] = keys.getLong(1);
                }
            } finally {
                log(sql, started);
            }
        };
        if (inTransaction) task.run();
        else DoltDialect.retry(tableOf(sql), task);
        return id[0];
    }

    public String scalar(String sql, Object... args) throws SQLException {
        List<String> values = column(sql, args);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<String> column(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) values.add(rows.getString(1));
                return values;
            }
        } finally {
            log(sql, started);
        }
    }

    public void transaction(String table, DoltDialect.SqlTask work) throws SQLException {
        // Dolt commits by merging. Two transactions that store the same cell value both succeed,
        // so SELECT FOR UPDATE does not keep a read-modify-write. One lock per branch and table does.
        String lock = writeLock(binding.branch(), table);
        DoltDialect.retry(table, () -> {
            String got = scalar("SELECT GET_LOCK(?, 30)", lock);
            if (!"1".equals(got)) throw new SQLException("error: could not lock " + table);
            inTransaction = true;
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // The original failure is the one the caller needs.
                }
                throw failure;
            } finally {
                inTransaction = false;
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The commit or rollback already finished.
                }
                try {
                    scalar("SELECT RELEASE_LOCK(?)", lock);
                } catch (SQLException ignored) {
                    // Closing the connection releases a leftover lock.
                }
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        DoltConnections.giveBack(pool, connection);
    }

    private int updateOnce(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        } finally {
            log(sql, started);
        }
    }

    private void log(String sql, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        String shape = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        String sandbox = binding.primary() ? "main" : binding.name();
        DoltLog.log(project, "sql", tableOf(shape), sandbox, "ms=" + millis + " " + shape);
    }

    private static void drain(Statement statement, boolean hasResult) throws SQLException {
        int update = statement.getUpdateCount();
        while (hasResult || update != -1) {
            if (hasResult) {
                try (ResultSet rows = statement.getResultSet()) {
                    while (rows != null && rows.next()) {
                        // Stored procedures return status rows. Callers that need a value use scalar.
                    }
                }
            }
            hasResult = statement.getMoreResults();
            update = statement.getUpdateCount();
        }
    }

    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        if (args == null) return;
        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            int column = index + 1;
            if (arg == null) statement.setObject(column, null);
            else if (arg instanceof byte[] bytes) statement.setBytes(column, bytes);
            else if (arg instanceof Integer number) statement.setInt(column, number);
            else if (arg instanceof Long number) statement.setLong(column, number);
            else if (arg instanceof Boolean flag) statement.setInt(column, flag ? 1 : 0);
            else statement.setString(column, arg.toString());
        }
    }

    static String tableOf(String sql) {
        if (sql == null) return "sql";
        Matcher matcher = TABLE.matcher(sql);
        return matcher.find() ? matcher.group(1) : "sql";
    }

    /** MySQL lock names are 64 characters. A long branch and table name is hashed. */
    static String writeLock(String branch, String table) {
        String name = "pkb:w:" + (branch == null ? "" : branch) + ":" + (table == null ? "" : table);
        if (name.length() <= 64) return name;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("pkb:w:");
            for (int index = 0; index < 16; index++) {
                hex.append(String.format("%02x", digest[index] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            return "pkb:w:" + Integer.toHexString(name.hashCode());
        }
    }
}
