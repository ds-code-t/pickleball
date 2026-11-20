package tools.dscode.store.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.dscode.store.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sixteen child JVMs write one WAL database. Prints {@code STORE_MP_P95_MS}.
 * The ceiling is about ten times the 5 ms design target.
 */
class EmbeddedMultiProcessTest {
    private static final int PROCESSES = 16;
    private static final int INSERTS = 200;

    @TempDir
    Path dir;

    @Test
    void sixteenProcessesWriteWithoutLosingARow() throws Exception {
        Path db = dir.resolve("wal.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("CREATE TABLE rows(id INTEGER PRIMARY KEY AUTOINCREMENT, worker INTEGER NOT NULL, n INTEGER NOT NULL)");
        }
        List<Process> children = new ArrayList<>();
        for (int worker = 0; worker < PROCESSES; worker++) {
            Path times = dir.resolve("times-" + worker + ".txt");
            // Each JVM extracts its own library. A shared temp dir lets one
            // process delete a sibling's sqlitejdbc.dll on Windows.
            Path nativeDir = dir.resolve("sqlite-native-" + worker);
            Files.createDirectories(nativeDir);
            List<String> command = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dorg.sqlite.tmpdir=" + nativeDir.toAbsolutePath(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    EmbeddedMultiProcessTest.class.getName(),
                    db.toAbsolutePath().toString(),
                    Integer.toString(worker),
                    times.toAbsolutePath().toString(),
                    Integer.toString(INSERTS));
            children.add(new ProcessBuilder(command).redirectErrorStream(true).start());
        }
        StringBuilder failures = new StringBuilder();
        for (int worker = 0; worker < children.size(); worker++) {
            Process child = children.get(worker);
            boolean finished = child.waitFor(120, TimeUnit.SECONDS);
            String text = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished || child.exitValue() != 0) {
                failures.append("worker ").append(worker).append(" code=")
                        .append(finished ? child.exitValue() : "timeout").append(' ').append(text).append('\n');
            }
        }
        assertEquals("", failures.toString());
        List<Long> samples = new ArrayList<>();
        for (int worker = 0; worker < PROCESSES; worker++) {
            for (String line : Files.readAllLines(dir.resolve("times-" + worker + ".txt"))) {
                if (!line.isBlank()) samples.add(Long.parseLong(line.trim()));
            }
        }
        assertEquals(PROCESSES * INSERTS, samples.size());
        Collections.sort(samples);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        System.out.println("STORE_MP_P95_MS=" + p95);
        System.out.println("STORE_MP_MAX_MS=" + samples.get(samples.size() - 1));
        assertTrue(p95 <= 50, "p95 " + p95 + " ms exceeds 10x the 5 ms target");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM rows")) {
            assertTrue(rows.next());
            assertEquals(PROCESSES * INSERTS, rows.getInt(1));
        }
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        int worker = Integer.parseInt(args[1]);
        Path times = Path.of(args[2]);
        int inserts = Integer.parseInt(args[3]);
        StringBuilder out = new StringBuilder();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }
            for (int n = 0; n < inserts; n++) {
                long started = System.nanoTime();
                int row = n;
                SqlDialect.immediate(connection, "rows", () -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO rows(worker, n) VALUES (?, ?)")) {
                        statement.setInt(1, worker);
                        statement.setInt(2, row);
                        statement.executeUpdate();
                    }
                });
                long millis = (System.nanoTime() - started) / 1_000_000L;
                out.append(millis).append('\n');
            }
        }
        Files.writeString(times, out.toString(), StandardCharsets.UTF_8);
    }
}
