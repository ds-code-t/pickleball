package tools.dscode.store.sql;

import tools.dscode.store.StoreLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
public final class SqlSession implements AutoCloseable {
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:into|update|from)\\s+`?([a-z0-9_]+)");

    private final Path project;
    private final Binding binding;
    private final Connection connection;
    private final SqlConnections.Pool pool;
    private Connection runState;
    private SqlConnections.Pool runPool;
    private boolean closed;
    private boolean inTransaction;

    SqlSession(Path project, Binding binding, Connection connection, SqlConnections.Pool pool) {
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
        Connection target = pick(sql);
        try (Statement statement = target.createStatement()) {
            drain(statement, statement.execute(sql));
        } finally {
            log(sql, started);
        }
    }

    /** Bound stored-procedure call. Arguments are parameters, never concatenated SQL. */
    public void call(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = pick(sql).prepareStatement(sql)) {
            bind(statement, args);
            drain(statement, statement.execute());
        } finally {
            log(sql, started);
        }
    }

    public int update(String sql, Object... args) throws SQLException {
        if (inTransaction) return updateOnce(sql, args);
        int[] count = new int[1];
        SqlDialect.retry(tableOf(sql), () -> count[0] = updateOnce(sql, args));
        return count[0];
    }

    public long insertId(String sql, Object... args) throws SQLException {
        long[] id = new long[1];
        SqlDialect.SqlTask task = () -> {
            long started = System.nanoTime();
            try (PreparedStatement statement = pick(sql).prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
        else SqlDialect.retry(tableOf(sql), task);
        return id[0];
    }

    public String scalar(String sql, Object... args) throws SQLException {
        List<String> values = column(sql, args);
        return values.isEmpty() ? null : values.get(0);
    }

    public List<String> column(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = pick(sql).prepareStatement(sql)) {
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

    public void transaction(String table, SqlDialect.SqlTask work) throws SQLException {
        SqlDialect.retry(table, () -> {
            inTransaction = true;
            Connection run = runState();
            connection.setAutoCommit(false);
            run.setAutoCommit(false);
            try {
                work.run();
                run.commit();
                connection.commit();
            } catch (SQLException failure) {
                try {
                    run.rollback();
                } catch (SQLException ignored) {
                    // The original failure is the one the caller needs.
                }
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // The original failure is the one the caller needs.
                }
                throw failure;
            } finally {
                inTransaction = false;
                try {
                    run.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The commit or rollback already finished.
                }
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // The commit or rollback already finished.
                }
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (runState != null) SqlConnections.giveBack(runPool, runState);
        SqlConnections.giveBack(pool, connection);
    }

    private Connection pick(String sql) throws SQLException {
        if (Sql.classify(sql) == Sql.SqlKind.RUN_STATE || Sql.runStatePragma(sql)) {
            return runState();
        }
        return connection;
    }

    private Connection runState() throws SQLException {
        if (runState != null) return runState;
        try {
            SqlConnections.Borrowed borrowed = SqlConnections.borrowRunState(project);
            runState = borrowed.connection();
            runPool = borrowed.pool();
            return runState;
        } catch (java.io.IOException failure) {
            throw new SQLException(failure.getMessage() == null ? "run-state database did not open" : failure.getMessage(), failure);
        }
    }

    private int updateOnce(String sql, Object... args) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = pick(sql).prepareStatement(sql)) {
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
        StoreLog.log(project, "sql", tableOf(shape), sandbox, "ms=" + millis + " " + shape);
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
}
