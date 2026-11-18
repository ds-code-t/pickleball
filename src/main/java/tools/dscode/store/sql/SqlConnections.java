package tools.dscode.store.sql;

import tools.dscode.store.StorePaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
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
public final class SqlConnections {
    public static final int MAX = 4;
    static final long IDLE_MS = 60_000;

    private static final ConcurrentHashMap<String, Pool> POOLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, HeldLease> LEASES = new ConcurrentHashMap<>();

    private SqlConnections() {
    }

    public static SqlSession borrow(java.nio.file.Path project, Binding binding) throws IOException, SQLException {
        java.nio.file.Path root = project.toAbsolutePath().normalize();
        Binding chosen = binding == null ? Binding.main() : binding;
        Path file = chosen.primary()
                ? StorePaths.mainDatabase(root)
                : StorePaths.sandboxDatabase(root, chosen.name());
        if (!chosen.primary() && !Files.isRegularFile(file)) {
            throw new SQLException("unknown sandbox " + chosen.name()
                    + "\nfix: store sandbox create " + chosen.name());
        }
        Files.createDirectories(file.getParent());
        ensureRunState(root);
        String key = root + "|" + chosen.branch();
        Pool pool = POOLS.computeIfAbsent(key, ignored -> new Pool(file));
        Connection connection = take(pool);
        prepareSource(connection, StorePaths.runState(root));
        touchLease(root, chosen);
        return new SqlSession(root, chosen, connection, pool);
    }

    /** A writable connection to the shared run-state file. The caller returns it with {@link #giveBack}. */
    public static Borrowed borrowRunState(java.nio.file.Path project) throws IOException, SQLException {
        java.nio.file.Path root = project.toAbsolutePath().normalize();
        Path file = StorePaths.runState(root);
        Files.createDirectories(file.getParent());
        String key = root + "|runstate";
        Pool pool = POOLS.computeIfAbsent(key, ignored -> new Pool(file));
        return new Borrowed(take(pool), pool);
    }

    private static final java.util.Set<String> RUN_SCHEMA = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void ensureRunState(Path project) throws IOException, SQLException {
        Path file = StorePaths.runState(project);
        String key = file.toAbsolutePath().normalize().toString();
        if (RUN_SCHEMA.contains(key) && Files.isRegularFile(file)) return;
        Borrowed borrowed = borrowRunState(project);
        try (Statement statement = borrowed.connection().createStatement()) {
            for (String ddl : SqlDialect.RUN_DDL) statement.execute(ddl);
            addRunColumns(borrowed.connection());
        } finally {
            giveBack(borrowed.pool(), borrowed.connection());
        }
        RUN_SCHEMA.add(key);
    }

    /** Older run-state files predate git commit and feature path. New files already have both. */
    private static void addRunColumns(Connection connection) throws SQLException {
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ResultSet rows = connection.createStatement().executeQuery("PRAGMA table_info(runs)")) {
            while (rows.next()) names.add(rows.getString("name"));
        }
        if (names.isEmpty()) return;
        try (Statement statement = connection.createStatement()) {
            if (!names.contains("git_commit")) {
                statement.execute("ALTER TABLE runs ADD COLUMN git_commit TEXT NOT NULL DEFAULT ''");
            }
            if (!names.contains("feature_path")) {
                statement.execute("ALTER TABLE runs ADD COLUMN feature_path TEXT NOT NULL DEFAULT ''");
            }
        }
    }

    /** Feature paths from scenario keys. Does not assign a git commit and does not create a database. */
    public static void fillRunProvenance(Path project) throws SQLException {
        if (project == null) return;
        Path file = StorePaths.runState(project);
        if (!Files.isRegularFile(file)) return;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            tools.dscode.store.RunProvenance.fillBlanks(connection, project);
        }
    }

    /**
     * Adds the run provenance columns when a run-state file already exists.
     * Does not create a database.
     */
    public static void ensureRunColumns(Path project) throws IOException, SQLException {
        if (project == null) return;
        Path file = StorePaths.runState(project);
        if (!Files.isRegularFile(file)) return;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            addRunColumns(connection);
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
        if (!branch.startsWith("sbx-")) return false;
        return foreignLock(root, branch.substring(4));
    }

    private static boolean localBorrowed(java.nio.file.Path project, String branch) {
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.get(key);
        if (pool == null) return false;
        synchronized (pool) {
            return pool.open > pool.idle.size();
        }
    }

    /** Exclusive OS lock at {@code sandboxes/<name>/lease/<pid>.lock} while this JVM uses the sandbox. */
    private static void touchLease(Path project, Binding binding) {
        if (binding == null || binding.primary()) return;
        Path database = StorePaths.sandboxDatabase(project, binding.name()).toAbsolutePath().normalize();
        LEASES.computeIfAbsent(database, ignored -> openLease(project, binding.name()));
    }

    private static HeldLease openLease(Path project, String name) {
        Path file = StorePaths.sandboxHome(project, name).resolve("lease")
                .resolve(ProcessHandle.current().pid() + ".lock");
        try {
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new HeldLease(channel, lock, file);
        } catch (IOException failure) {
            throw new IllegalStateException("sandbox lease did not lock: " + name, failure);
        }
    }

    /** True when another process holds a lease. A lock whose owner is gone is deleted. */
    private static boolean foreignLock(Path project, String name) {
        Path dir = StorePaths.sandboxHome(project, name).resolve("lease");
        if (!Files.isDirectory(dir)) return false;
        String ours = ProcessHandle.current().pid() + ".lock";
        try (var stream = Files.list(dir)) {
            for (Path lease : stream.toList()) {
                if (!lease.getFileName().toString().endsWith(".lock")) continue;
                if (ours.equals(lease.getFileName().toString())) continue;
                if (lockedByOther(lease)) return true;
                try {
                    Files.deleteIfExists(lease);
                } catch (IOException ignored) {
                    // A stale file that cannot be removed is not a live lease.
                }
            }
        } catch (IOException ignored) {
            return true;
        }
        return false;
    }

    private static boolean lockedByOther(Path lease) {
        try (FileChannel channel = FileChannel.open(lease, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) return true;
            lock.release();
            return false;
        } catch (OverlappingFileLockException ours) {
            return false;
        } catch (IOException busy) {
            return true;
        }
    }

    private static void releaseLeaseFor(Pool pool) {
        if (pool == null || pool.file == null) return;
        HeldLease lease = LEASES.remove(pool.file.toAbsolutePath().normalize());
        if (lease != null) lease.close();
    }

    private static void releaseLease(Path project, String branch) {
        if (branch == null || !branch.startsWith("sbx-")) return;
        Path database = StorePaths.sandboxDatabase(project, branch.substring(4)).toAbsolutePath().normalize();
        HeldLease lease = LEASES.remove(database);
        if (lease != null) lease.close();
    }

    public static void closeBranch(java.nio.file.Path project, String branch) {
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.remove(key);
        if (pool != null) releaseEvery(pool);
        releaseLease(project, branch);
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
                if (wait <= 0) throw new SQLException("error: store connection pool exhausted");
                try {
                    pool.wait(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("error: store connection pool interrupted", interrupted);
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
        releaseLeaseFor(pool);
    }

    private static void releaseEvery(Pool pool) {
        synchronized (pool) {
            pool.idle.clear();
            List<Connection> closing = new ArrayList<>(pool.live);
            pool.live.clear();
            pool.open = 0;
            finish(closing);
            releaseLeaseFor(pool);
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

    private record HeldLease(FileChannel channel, FileLock lock, Path file) {
        void close() {
            try {
                if (lock != null && lock.isValid()) lock.release();
            } catch (IOException ignored) {
                // The process exit drops an OS lock that release refused.
            }
            try {
                if (channel != null && channel.isOpen()) channel.close();
            } catch (IOException ignored) {
                // Closing the channel is what drops the lock on some hosts.
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A leftover lock file with no holder is ignored on the next check.
            }
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
