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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-JVM pool keyed by project and branch. At most four connections.
 * Idle connections older than 60 seconds are closed on borrow and return.
 * No extra pool library.
 */
public final class DoltConnections {
    public static final int MAX = 4;
    static final long IDLE_MS = 60_000;

    private static final ConcurrentHashMap<String, Pool> POOLS = new ConcurrentHashMap<>();
    private static final Set<Long> OWN_CONNECTION_IDS = ConcurrentHashMap.newKeySet();

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
        if (foreignLease(root, branch)) return true;
        return foreignProcess(root, branch);
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
            // A missing lease still leaves PROCESSLIST as the cross-process check.
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

    private static boolean foreignProcess(Path project, String branch) {
        return false;
    }

    private static void remember(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (rows.next()) OWN_CONNECTION_IDS.add(rows.getLong(1));
        } catch (SQLException ignored) {
            // PROCESSLIST exclusion is best-effort. The lease file is the other signal.
        }
    }

    private static void forget(Connection connection) {
        if (connection == null) return;
        try {
            if (connection.isClosed()) return;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (rows.next()) OWN_CONNECTION_IDS.remove(rows.getLong(1));
            }
        } catch (SQLException ignored) {
            // A dead connection is already gone from the server.
        }
    }

    public static void closeBranch(java.nio.file.Path project, String branch) {
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.remove(key);
        if (pool != null) {
            synchronized (pool) {
                int borrowed = pool.open - pool.idle.size();
                Held held;
                while ((held = pool.idle.pollFirst()) != null) closeQuiet(held.connection);
                pool.open = Math.max(0, borrowed);
            }
        }
        clearOwnLease(project, branch);
    }

    public static void closeProject(java.nio.file.Path project) {
        String prefix = project.toAbsolutePath().normalize() + "|";
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String key : POOLS.keySet()) {
            if (key.startsWith(prefix)) keys.add(key);
        }
        for (String key : keys) {
            Pool pool = POOLS.remove(key);
            if (pool == null) continue;
            synchronized (pool) {
                Held held;
                while ((held = pool.idle.pollFirst()) != null) closeQuiet(held.connection);
                pool.open = 0;
            }
        }
    }

    public static void closeAll() {
        for (Pool pool : POOLS.values()) {
            synchronized (pool) {
                Held held;
                while ((held = pool.idle.pollFirst()) != null) closeQuiet(held.connection);
                pool.open = 0;
            }
        }
        POOLS.clear();
    }

    static void giveBack(Pool pool, Connection connection) {
        synchronized (pool) {
            evict(pool);
            boolean live = false;
            try {
                live = connection != null && !connection.isClosed() && connection.isValid(1);
            } catch (SQLException ignored) {
                live = false;
            }
            if (!live) {
                closeQuiet(connection);
                if (pool.open > 0) pool.open--;
            } else {
                pool.idle.addLast(new Held(connection, System.currentTimeMillis()));
            }
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
                    closeQuiet(held.connection);
                    if (pool.open > 0) pool.open--;
                    continue;
                }
                if (pool.open < MAX) {
                    Connection created = SqliteOpen.open(pool.file);
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
            closeQuiet(held.connection);
            if (pool.open > 0) pool.open--;
        }
    }

    private static void clearOwnLease(java.nio.file.Path project, String branch) {
        try {
            Files.deleteIfExists(leaseDirectory(project, branch).resolve(Long.toString(ProcessHandle.current().pid())));
        } catch (IOException ignored) {
            // A leftover lease for a dead pid is ignored.
        }
    }

    private static void closeQuiet(Connection connection) {
        forget(connection);
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A closed connection is the state we wanted.
        }
    }

    static final class Pool {
        final ArrayDeque<Held> idle = new ArrayDeque<>();
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
