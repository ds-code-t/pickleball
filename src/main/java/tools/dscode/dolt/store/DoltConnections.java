package tools.dscode.dolt.store;

import tools.dscode.dolt.server.DoltServer;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
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

    private static void closeQuiet(Connection connection) {
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
