package tools.dscode.dolt.store;

import tools.dscode.dolt.DoltPaths;
import tools.dscode.dolt.server.DoltServer;

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
        DoltServer.ServerInfo info = DoltServer.ensure(root);
        String key = root + "|" + binding.branch();
        Pool pool = POOLS.computeIfAbsent(key, ignored -> new Pool(info.port(), binding.databasePath()));
        synchronized (pool) {
            pool.port = info.port();
        }
        Connection connection = take(pool);
        remember(connection);
        touchLease(root, binding.branch());
        return new DoltSession(root, binding, connection, pool);
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
        String key = project.toAbsolutePath().normalize() + "|" + branch;
        Pool pool = POOLS.get(key);
        int port = pool == null ? 0 : pool.port;
        if (port <= 0) {
            Pool main = POOLS.get(project.toAbsolutePath().normalize() + "|main");
            if (main != null) port = main.port;
        }
        if (port <= 0) return false;
        try (Connection connection = DoltJdbc.open(port, "pickleball", 2_000);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID, DB FROM information_schema.PROCESSLIST")) {
            while (rows.next()) {
                long id = rows.getLong(1);
                if (OWN_CONNECTION_IDS.contains(id)) continue;
                String db = rows.getString(2);
                if (db != null && (db.equals(branch) || db.endsWith("/" + branch))) return true;
            }
        } catch (SQLException ignored) {
            return false;
        }
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
                    Connection created = DoltJdbc.open(pool.port, pool.database, 5_000);
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
        final String database;
        volatile int port;
        int open;

        Pool(int port, String database) {
            this.port = port;
            this.database = database;
        }
    }

    record Held(Connection connection, long returnedAt) {
    }
}
