package tools.dscode.dolt.store;

import tools.dscode.dolt.DoltPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JVM pool keyed by project and branch. At most four connections.
 * Idle connections older than 60 seconds are closed on borrow and return.
 * When nothing is borrowed, the pool closes those connections and checkpoints
 * WAL so the database files can be deleted. No extra pool library.
 */
public final class DoltConnections {
    public static final int MAX = 4;
    static final long IDLE_MS = 60_000;

    private static final ConcurrentHashMap<String, Pool> POOLS = new ConcurrentHashMap<>();

    private DoltConnections() {
    }

    public static DoltSession borrow(java.nio.file.Path project, Binding binding) throws IOException, SQLException {
        java.nio.file.Path root = project.toAbsolutePath().normalize();
        Binding chosen = binding == null ? Binding.main() : binding;
        Path file = chosen.primary()
                ? DoltPaths.mainDatabase(root)
                : DoltPaths.sandboxDatabase(root, chosen.name());
        if (!chosen.primary() && !Files.isRegularFile(file)) {
            throw new SQLException("unknown sandbox " + chosen.name()
                    + "\nfix: dolt sandbox create " + chosen.name());
        }
        Files.createDirectories(file.getParent());
        ensureRunState(root);
        String key = root + "|" + chosen.branch();
        Pool pool = POOLS.computeIfAbsent(key, ignored -> new Pool(file));
        Connection connection = take(pool);
        prepareSource(connection, DoltPaths.runState(root));
        touchLease(root, chosen.branch());
        return new DoltSession(root, chosen, connection, pool);
    }

    /** A writable connection to the shared run-state file. The caller returns it with {@link #giveBack}. */
    public static Borrowed borrowRunState(java.nio.file.Path project) throws IOException, SQLException {
        java.nio.file.Path root = project.toAbsolutePath().normalize();
        Path file = DoltPaths.runState(root);
        Files.createDirectories(file.getParent());
        String key = root + "|runstate";
        Pool pool = POOLS.computeIfAbsent(key, ignored -> new Pool(file));
        return new Borrowed(take(pool), pool);
    }

    private static void ensureRunState(Path project) throws IOException, SQLException {
        Borrowed borrowed = borrowRunState(project);
        try (Statement statement = borrowed.connection().createStatement()) {
            for (String ddl : DoltDialect.RUN_DDL) statement.execute(ddl);
        } finally {
            giveBack(borrowed.pool(), borrowed.connection());
        }
    }

    private static void prepareSource(Connection connection, Path runState) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean attached = false;
            try (ResultSet rows = statement.executeQuery("PRAGMA database_list")) {
                while (rows.next()) {
                    if ("rs".equalsIgnoreCase(rows.getString("name"))) attached = true;
                }
            }
            if (!attached) {
                String path = runState.toAbsolutePath().toString().replace("'", "''");
                statement.execute("ATTACH DATABASE 'file:" + path + "?mode=ro' AS rs");
            }
        }
    }

    public static boolean inUse(java.nio.file.Path project) {
        String prefix = project.toAbsolutePath().normalize() + "|";
        for (var entry : POOLS.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Pool pool = entry.getValue();
            synchronized (pool) {
                if (pool.open > pool.idle.size()) return true;
            }
        }
        return false;
    }

    public static boolean branchInUse(java.nio.file.Path project, String branch) {
        if (branch == null || branch.isBlank()) return false;
        java.nio.file.Path root = project.toAbsolutePath().normalize();
        if (localBorrowed(root, branch)) return true;
        return foreignLease(root, branch);
    }

    private static boolean localBorrowed(java.nio.file.Path project, String branch) {
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.get(key);
        if (pool == null) return false;
        synchronized (pool) {
            return pool.open > pool.idle.size();
        }
    }

    static Path leaseDirectory(Path project, String branch) {
        String safe = branch.replace('\\', '/').replace("/", "_");
        return DoltPaths.root(project).resolve("branch-lease").resolve(safe);
    }

    private static void touchLease(Path project, String branch) {
        try {
            Path file = leaseDirectory(project, branch).resolve(Long.toString(ProcessHandle.current().pid()));
            Files.createDirectories(file.getParent());
            Files.writeString(file, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A lease that cannot be written does not block the caller.
        }
    }

    private static boolean foreignLease(Path project, String branch) {
        Path dir = leaseDirectory(project, branch);
        if (!Files.isDirectory(dir)) return false;
        long self = ProcessHandle.current().pid();
        try (var stream = Files.list(dir)) {
            for (Path lease : stream.toList()) {
                long pid;
                try {
                    pid = Long.parseLong(lease.getFileName().toString());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (pid == self) continue;
                if (ProcessHandle.of(pid).isPresent()) return true;
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    public static void closeBranch(java.nio.file.Path project, String branch) {
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.remove(key);
        if (pool != null) releaseEvery(pool);
        clearOwnLease(project, branch);
    }

    public static void closeProject(java.nio.file.Path project) {
        if (project == null) return;
        String prefix = project.toAbsolutePath().normalize() + "|";
        List<String> keys = new ArrayList<>();
        for (String key : POOLS.keySet()) {
            if (key.startsWith(prefix)) keys.add(key);
        }
        for (String key : keys) {
            Pool pool = POOLS.remove(key);
            if (pool != null) releaseEvery(pool);
        }
    }

    public static void closeAll() {
        List<Pool> pools = new ArrayList<>(POOLS.values());
        POOLS.clear();
        for (Pool pool : pools) releaseEvery(pool);
    }

    static void giveBack(Pool pool, Connection connection) {
        synchronized (pool) {
            evict(pool);
            if (connection == null) {
                if (fullyIdle(pool)) releaseIdle(pool);
                pool.notifyAll();
                return;
            }
            boolean live = false;
            try {
                live = !connection.isClosed() && connection.isValid(1);
            } catch (SQLException ignored) {
                live = false;
            }
            if (!live) retire(pool, connection);
            else pool.idle.addLast(new Held(connection, System.currentTimeMillis()));
            if (fullyIdle(pool)) releaseIdle(pool);
            pool.notifyAll();
        }
    }

    private static Connection take(Pool pool) throws SQLException {
        long deadline = System.currentTimeMillis() + 30_000;
        synchronized (pool) {
            while (true) {
                evict(pool);
                Held held = pool.idle.pollFirst();
                if (held != null) {
                    try {
                        if (!held.connection.isClosed() && held.connection.isValid(1)) return held.connection;
                    } catch (SQLException ignored) {
                        // fall through and replace it
                    }
                    retire(pool, held.connection);
                    continue;
                }
                if (pool.open < MAX) {
                    Connection created = SqliteOpen.open(pool.file);
                    pool.live.add(created);
                    pool.open++;
                    return created;
                }
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) throw new SQLException("error: dolt connection pool exhausted");
                try {
                    pool.wait(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("error: dolt connection pool interrupted", interrupted);
                }
            }
        }
    }

    private static void evict(Pool pool) {
        long cutoff = System.currentTimeMillis() - IDLE_MS;
        Held held;
        while ((held = pool.idle.peekFirst()) != null && held.returnedAt < cutoff) {
            pool.idle.pollFirst();
            retire(pool, held.connection);
        }
    }

    private static boolean fullyIdle(Pool pool) {
        return pool.live.size() == pool.idle.size();
    }

    /** Drops one connection. Checkpoints WAL when it was the last handle on the file. */
    private static void retire(Pool pool, Connection connection) {
        pool.live.remove(connection);
        if (pool.open > 0) pool.open--;
        releaseConnection(connection, pool.live.isEmpty());
    }

    private static void releaseIdle(Pool pool) {
        List<Connection> closing = new ArrayList<>();
        Held held;
        while ((held = pool.idle.pollFirst()) != null) {
            pool.live.remove(held.connection);
            if (pool.open > 0) pool.open--;
            closing.add(held.connection);
        }
        finish(closing);
    }

    private static void releaseEvery(Pool pool) {
        synchronized (pool) {
            pool.idle.clear();
            List<Connection> closing = new ArrayList<>(pool.live);
            pool.live.clear();
            pool.open = 0;
            finish(closing);
            pool.notifyAll();
        }
    }

    private static void finish(List<Connection> closing) {
        for (int index = 0; index < closing.size(); index++) {
            releaseConnection(closing.get(index), index == closing.size() - 1);
        }
    }

    /**
     * Detaches extra databases, checkpoints WAL on the last handle, and closes
     * the connection. sqlite-jdbc finalizes leftover statements inside {@code close}.
     */
    private static void releaseConnection(Connection connection, boolean last) {
        if (connection == null) return;
        try {
            if (connection.isClosed()) return;
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ignored) {
                // A connection that cannot leave a transaction is still closed below.
            }
            List<String> attached = attachedNames(connection);
            if (last) checkpointAttached(connection, attached);
            for (String name : attached) detach(connection, name);
            if (last) checkpointMain(connection);
            connection.close();
        } catch (SQLException ignored) {
            try {
                connection.close();
            } catch (SQLException ignoredAgain) {
                // A closed connection is the state we wanted.
            }
        }
    }

    private static List<String> attachedNames(Connection connection) {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA database_list")) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (name == null) continue;
                if (name.equalsIgnoreCase("main") || name.equalsIgnoreCase("temp")) continue;
                if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) names.add(name);
            }
        } catch (SQLException ignored) {
            // A connection that cannot list databases is still closed by the caller.
        }
        return names;
    }

    private static void checkpointAttached(Connection connection, List<String> attached) {
        try (Statement statement = connection.createStatement()) {
            for (String name : attached) {
                try {
                    statement.execute("PRAGMA " + name + ".wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // A read-only attach may refuse a checkpoint. Detach still drops its lock.
                }
            }
        } catch (SQLException ignored) {
            // The main checkpoint and close still run.
        }
    }

    private static void detach(Connection connection, String name) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DETACH DATABASE " + name);
        } catch (SQLException ignored) {
            // Detach is best-effort. Closing the connection drops the attach.
        }
    }

    private static void checkpointMain(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ignored) {
            // Close still releases the handle. A later open checkpoints again.
        }
    }

    private static void clearOwnLease(java.nio.file.Path project, String branch) {
        try {
            Files.deleteIfExists(leaseDirectory(project, branch).resolve(Long.toString(ProcessHandle.current().pid())));
        } catch (IOException ignored) {
            // A leftover lease for a dead pid is ignored.
        }
    }

    static final class Pool {
        final ArrayDeque<Held> idle = new ArrayDeque<>();
        final Set<Connection> live = new HashSet<>();
        final Path file;
        int open;

        Pool(Path file) {
            this.file = file;
        }
    }

    public record Borrowed(Connection connection, Pool pool) {
    }

    record Held(Connection connection, long returnedAt) {
    }
}
