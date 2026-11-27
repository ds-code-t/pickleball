package tools.dscode.store.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sqlite-jdbc 3.47.2.0 facts for the embedded store. No product code.
 * Prints the numbers design sections 15 and 16 record.
 */
class EmbeddedStoreSpikeTest {
    @TempDir
    Path dir;

    @Test
    void vacuumIntoStaysConsistentWhileAnotherConnectionWrites() throws Exception {
        Path source = dir.resolve("main.db");
        Path copy = dir.resolve("copy.db");
        try (Connection writer = open(source)) {
            exec(writer, """
                    CREATE TABLE rows(n INTEGER PRIMARY KEY, payload TEXT NOT NULL)
                    """);
            for (int n = 1; n <= 200; n++) insert(writer, n);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger wrote = new AtomicInteger(200);
            Thread other = new Thread(() -> {
                try (Connection extra = open(source)) {
                    while (!stop.get()) {
                        int n = wrote.incrementAndGet();
                        insert(extra, n);
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            other.start();
            long started = System.nanoTime();
            try (Statement statement = writer.createStatement()) {
                statement.execute("VACUUM INTO '" + copy.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            stop.set(true);
            other.join(10_000);
            System.out.println("STORE_VACUUM_INTO_MS=" + millis);
            try (Connection check = open(copy);
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT n, payload FROM rows ORDER BY n")) {
                int previous = 0;
                int count = 0;
                while (rows.next()) {
                    int n = rows.getInt(1);
                    String payload = rows.getString(2);
                    assertEquals(previous + 1, n, "gap in the vacuum snapshot");
                    assertEquals("p" + n, payload);
                    previous = n;
                    count++;
                }
                assertTrue(count >= 200, "snapshot rows " + count);
                System.out.println("STORE_VACUUM_ROWS=" + count);
            }
        }
    }

    @Test
    void beginImmediateOnSourceDoesNotLockReadOnlyRunState() throws Exception {
        Path source = dir.resolve("source.db");
        Path runstate = dir.resolve("runstate.db");
        try (Connection run = open(runstate)) {
            exec(run, "CREATE TABLE runs(run_id TEXT PRIMARY KEY, note TEXT NOT NULL)");
            exec(run, "INSERT INTO runs(run_id, note) VALUES ('r1', 'kept')");
        }
        try (Connection sourceConn = open(source)) {
            exec(sourceConn, "CREATE TABLE files(path TEXT PRIMARY KEY)");
            String uri = "file:" + runstate.toAbsolutePath().toString().replace("\\", "/") + "?mode=ro";
            exec(sourceConn, "ATTACH DATABASE '" + uri.replace("'", "''") + "' AS rs");
            exec(sourceConn, "BEGIN IMMEDIATE");
            exec(sourceConn, "INSERT INTO files(path) VALUES ('a.txt')");
            SQLException writeAttached = assertThrows(SQLException.class,
                    () -> exec(sourceConn, "INSERT INTO rs.runs(run_id, note) VALUES ('r2', 'no')"));
            String message = writeAttached.getMessage() == null ? "" : writeAttached.getMessage().toLowerCase(Locale.ROOT);
            assertTrue(message.contains("readonly") || message.contains("read-only"), message);
            try (Connection other = open(runstate)) {
                exec(other, "BEGIN IMMEDIATE");
                exec(other, "INSERT INTO runs(run_id, note) VALUES ('r2', 'yes')");
                exec(other, "COMMIT");
            }
            exec(sourceConn, "COMMIT");
            try (Connection check = open(runstate);
                 Statement statement = check.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM runs")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            System.out.println("STORE_ATTACH_RO_LOCK=none");
        }
    }

    @Test
    void triggerBuildsJsonObjectChangeRowsAndRefusesABlob() throws Exception {
        Path db = dir.resolve("changes.db");
        try (Connection connection = open(db)) {
            exec(connection, """
                    CREATE TABLE files(
                        path TEXT PRIMARY KEY,
                        content_hash TEXT NOT NULL,
                        raw_content BLOB,
                        binary_ref INTEGER NOT NULL,
                        location TEXT NOT NULL,
                        byte_size INTEGER NOT NULL
                    )
                    """);
            exec(connection, """
                    CREATE TABLE _pkb_changes(
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        at TEXT NOT NULL,
                        tbl TEXT NOT NULL,
                        pk TEXT NOT NULL,
                        op TEXT NOT NULL,
                        old TEXT,
                        new TEXT,
                        actor TEXT NOT NULL DEFAULT ''
                    )
                    """);
            exec(connection, """
                    CREATE TRIGGER files_ai AFTER INSERT ON files BEGIN
                        INSERT INTO _pkb_changes(at, tbl, pk, op, old, new, actor)
                        VALUES (
                            strftime('%Y-%m-%dT%H:%M:%fZ','now'),
                            'files',
                            NEW.path,
                            'insert',
                            NULL,
                            json_object(
                                'path', NEW.path,
                                'content_hash', NEW.content_hash,
                                'binary_ref', NEW.binary_ref,
                                'location', NEW.location,
                                'byte_size', NEW.byte_size),
                            'main hydrate');
                    END
                    """);
            exec(connection, """
                    INSERT INTO files(path, content_hash, raw_content, binary_ref, location, byte_size)
                    VALUES ('features/a.feature', 'sha256:abc', x'010203', 0, 'db', 3)
                    """);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT tbl, pk, op, new FROM _pkb_changes")) {
                assertTrue(rows.next());
                assertEquals("files", rows.getString(1));
                assertEquals("features/a.feature", rows.getString(2));
                assertEquals("insert", rows.getString(3));
                String json = rows.getString(4);
                assertTrue(json.contains("\"path\":\"features/a.feature\""), json);
                assertTrue(json.contains("\"content_hash\":\"sha256:abc\""), json);
                assertTrue(json.contains("\"binary_ref\":0"), json);
                assertTrue(json.contains("\"location\":\"db\""), json);
                assertTrue(json.contains("\"byte_size\":3"), json);
                assertFalse(json.contains("raw_content"), json);
            }
            SQLException blob = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT json_object('raw', raw_content) FROM files")) {
                    if (rows.next()) rows.getString(1);
                }
            });
            String blobMessage = blob.getMessage() == null ? "" : blob.getMessage().toLowerCase(Locale.ROOT);
            assertTrue(blobMessage.contains("blob"), blobMessage);
            System.out.println("STORE_CHANGE_JSON=ok");
        }
    }

    @Test
    void nativeLibraryHonorsSqliteTmpdirAndReportsNoexecWhenMounted() throws Exception {
        Path execDir = dir.resolve("native");
        Files.createDirectories(execDir);
        Result honored = java(List.of("-Dorg.sqlite.tmpdir=" + execDir, EmbeddedNativeProbe.class.getName(), "load"));
        assertEquals(0, honored.code, honored.out + honored.err);
        assertEquals(0, honored.code, honored.out + honored.err);
        System.out.println("STORE_SQLITE_TMPDIR_OUT=" + oneLine(honored.out));
        assertTrue(honored.out.contains("file sqlite-"), honored.out);
        String listed = honored.out.toLowerCase(Locale.ROOT);
        assertTrue(listed.contains(".so") || listed.contains(".dylib") || listed.contains(".dll"), honored.out);
        System.out.println("STORE_SQLITE_TMPDIR=honored");

        Path noexec = dir.resolve("noexec");
        Files.createDirectories(noexec);
        boolean mounted = mountNoexec(noexec);
        if (!mounted) {
            System.out.println("STORE_NOEXEC=unavailable");
            return;
        }
        try {
            Result blocked = java(List.of("-Djava.io.tmpdir=" + noexec, "-Dorg.sqlite.tmpdir=" + noexec,
                    EmbeddedNativeProbe.class.getName(), "load"));
            String text = (blocked.out + blocked.err).toLowerCase(Locale.ROOT);
            System.out.println("STORE_NOEXEC_EXIT=" + blocked.code);
            System.out.println("STORE_NOEXEC_TEXT=" + oneLine(blocked.err + blocked.out));
            assertTrue(blocked.code != 0, text);
            assertTrue(text.contains("noexec") || text.contains("failed to map")
                    || text.contains("unsatisfiedlink") || text.contains("permission"), text);
            Result fixed = java(List.of("-Djava.io.tmpdir=" + noexec, "-Dorg.sqlite.tmpdir=" + execDir,
                    EmbeddedNativeProbe.class.getName(), "load"));
            assertEquals(0, fixed.code, fixed.out + fixed.err);
            System.out.println("STORE_NOEXEC_FIX=org.sqlite.tmpdir");
        } finally {
            umount(noexec);
        }
    }

    @Test
    void gitCatFileReadsABlobById() throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        byte[] bytes = "large-binary-payload".getBytes(StandardCharsets.UTF_8);
        Files.write(repo.resolve("blob.bin"), bytes);
        git(repo, "init");
        git(repo, "config", "user.email", "spike@example.com");
        git(repo, "config", "user.name", "spike");
        git(repo, "add", "blob.bin");
        git(repo, "commit", "-m", "blob");
        String blob = git(repo, "rev-parse", "HEAD:blob.bin").strip();
        byte[] read = gitBytes(repo, "cat-file", "blob", blob);
        assertEquals(new String(bytes, StandardCharsets.UTF_8), new String(read, StandardCharsets.UTF_8).strip());
        System.out.println("STORE_GIT_CAT_FILE=" + blob);
    }

    @Test
    void deletingTheDatabaseWhileAChildRunsDoesNotNeedTheChildToExit() throws Exception {
        Path db = dir.resolve("sandbox.db");
        try (Connection connection = open(db)) {
            exec(connection, "CREATE TABLE t(n INTEGER)");
            exec(connection, "INSERT INTO t(n) VALUES (1)");
        }
        Process child = new ProcessBuilder(tools.dscode.store.StoreTestProcesses.java(List.of(
                "-cp", System.getProperty("java.class.path"),
                EmbeddedNativeProbe.class.getName(), "sleep")))
                .redirectErrorStream(true)
                .start();
        try {
            Thread.sleep(200);
            boolean deleted = false;
            IOException locked = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                try {
                    Files.deleteIfExists(db);
                    Files.deleteIfExists(Path.of(db + "-wal"));
                    Files.deleteIfExists(Path.of(db + "-shm"));
                    deleted = true;
                    break;
                } catch (IOException busy) {
                    locked = busy;
                    Thread.sleep(100);
                }
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            System.out.println("STORE_DELETE_WHILE_CHILD=" + (deleted ? "released" : "blocked") + " os=" + os);
            if (os.contains("win") && !deleted) {
                child.destroyForcibly();
                child.waitFor();
                Files.deleteIfExists(db);
                System.out.println("STORE_DELETE_AFTER_CHILD=released");
                return;
            }
            assertTrue(deleted, "database stayed locked: " + locked);
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }

    private static void insert(Connection connection, int n) throws SQLException {
        exec(connection, "BEGIN IMMEDIATE");
        try (var statement = connection.prepareStatement("INSERT INTO rows(n, payload) VALUES (?, ?)")) {
            statement.setInt(1, n);
            statement.setString(2, "p" + n);
            statement.executeUpdate();
        }
        exec(connection, "COMMIT");
    }

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        exec(connection, "PRAGMA journal_mode=WAL");
        exec(connection, "PRAGMA busy_timeout=5000");
        exec(connection, "PRAGMA foreign_keys=ON");
        exec(connection, "PRAGMA synchronous=NORMAL");
        return connection;
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Result java(List<String> args) throws Exception {
        List<String> launch = new ArrayList<>();
        launch.add("-cp");
        launch.add(System.getProperty("java.class.path"));
        launch.addAll(args);
        Process process = new ProcessBuilder(tools.dscode.store.StoreTestProcesses.java(launch)).redirectErrorStream(false).start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        int code = process.waitFor();
        return new Result(code, new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    private static boolean mountNoexec(Path dir) {
        try {
            Process process = new ProcessBuilder("mount", "-t", "tmpfs", "-o", "noexec,size=16m", "tmpfs", dir.toString())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                System.out.println("STORE_NOEXEC_MOUNT=" + oneLine(text));
                return false;
            }
            return true;
        } catch (Exception failure) {
            System.out.println("STORE_NOEXEC_MOUNT=" + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void umount(Path dir) {
        try {
            new ProcessBuilder("umount", dir.toString()).start().waitFor();
        } catch (Exception ignored) {
            // The mount is best-effort and local to this test.
        }
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) throw new IOException(text);
        return text;
    }

    private static byte[] gitBytes(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) throw new IOException(new String(err, StandardCharsets.UTF_8));
        return out;
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        String line = text.strip().replace('\n', ' ');
        return line.length() > 240 ? line.substring(0, 240) : line;
    }

    private record Result(int code, String out, String err) {
    }
}
