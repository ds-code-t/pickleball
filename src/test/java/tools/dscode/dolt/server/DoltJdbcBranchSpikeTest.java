package tools.dscode.dolt.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 spike against a real Dolt 2.7.3 sql-server. Answers are written back into
 * the Dolt core design.
 */
class DoltJdbcBranchSpikeTest {
    @TempDir
    Path project;

    @BeforeEach
    void preferCachedBinary() {
        Path cached = Path.of(System.getProperty("user.home"), ".pickleball", "tools", "dolt", "2.7.3",
                "linux-amd64", "dolt");
        if (Files.isRegularFile(cached)) System.setProperty("pkb_dolt_bin", cached.toString());
    }

    @AfterEach
    void stop() throws Exception {
        DoltServer.stop(project);
        System.clearProperty("pkb_dolt_bin");
    }

    @Test
    void branchesBindPerConnectionAndStayIsolated() throws Exception {
        DoltServer.ServerInfo info = DoltServer.ensure(project);
        try (Connection main = connect(info.port(), "pickleball")) {
            exec(main, "CREATE TABLE spike_notes (id VARCHAR(64) PRIMARY KEY, body VARCHAR(200))");
            exec(main, "INSERT INTO spike_notes VALUES ('base', 'main-row')");
            exec(main, "CALL DOLT_COMMIT('-Am', 'base')");
            exec(main, "CALL DOLT_BRANCH('sbx-a')");
            exec(main, "CALL DOLT_BRANCH('sbx-b')");
            assertEquals("main", scalar(main, "SELECT active_branch()"));
        }
        try (Connection first = connect(info.port(), "pickleball");
             Connection second = connect(info.port(), "pickleball")) {
            exec(first, "USE `pickleball/sbx-a`");
            exec(second, "USE `pickleball/sbx-b`");
            assertEquals("sbx-a", scalar(first, "SELECT active_branch()"));
            assertEquals("sbx-b", scalar(second, "SELECT active_branch()"));
            exec(first, "UPDATE spike_notes SET body = 'from-a' WHERE id = 'base'");
            exec(second, "UPDATE spike_notes SET body = 'from-b' WHERE id = 'base'");
            assertEquals("from-a", scalar(first, "SELECT body FROM spike_notes WHERE id = 'base'"));
            assertEquals("from-b", scalar(second, "SELECT body FROM spike_notes WHERE id = 'base'"));
        }
        try (Connection main = connect(info.port(), "pickleball")) {
            assertEquals("main-row", scalar(main, "SELECT body FROM spike_notes WHERE id = 'base'"));
        }
        boolean urlBound = false;
        String urlError = "";
        try (Connection bound = connect(info.port(), "pickleball/sbx-a")) {
            urlBound = "sbx-a".equals(scalar(bound, "SELECT active_branch()"));
        } catch (RuntimeException | java.sql.SQLException failure) {
            urlError = failure.getMessage();
        }
        System.out.println("DOLT_SPIKE urlBound=" + urlBound + " urlError=" + urlError);
        assertTrue(urlBound, urlError);
    }

    @Test
    void dialectFactsForTheDesign() throws Exception {
        DoltServer.ServerInfo info = DoltServer.ensure(project);
        List<String> notes = new ArrayList<>();
        try (Connection connection = connect(info.port(), "pickleball");
             Statement statement = connection.createStatement()) {
            int keyLength = 0;
            for (int length : new int[]{255, 512, 1024, 2048, 3072}) {
                String table = "key_" + length;
                try {
                    statement.execute("DROP TABLE IF EXISTS " + table);
                    statement.execute("CREATE TABLE " + table + " (path VARCHAR(" + length + ") PRIMARY KEY, body VARCHAR(8))");
                    statement.execute("INSERT INTO " + table + " VALUES ('" + "p".repeat(Math.min(length, 64)) + "', 'ok')");
                    keyLength = length;
                } catch (java.sql.SQLException failure) {
                    notes.add("varchar pk " + length + " failed: " + failure.getMessage());
                    break;
                }
            }
            notes.add("max varchar primary key tried that worked: " + keyLength);
            assertTrue(keyLength >= 1024, String.join("\n", notes));

            statement.execute("DROP TABLE IF EXISTS blobs");
            statement.execute("CREATE TABLE blobs (id INT PRIMARY KEY, raw LONGBLOB)");
            byte[] seventy = new byte[70_000];
            byte[] oneMib = new byte[1_048_576];
            try (var insert = connection.prepareStatement("INSERT INTO blobs VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setBytes(2, seventy);
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setBytes(2, oneMib);
                insert.executeUpdate();
            }
            assertEquals("70000", scalar(connection, "SELECT LENGTH(raw) FROM blobs WHERE id = 1"));
            assertEquals("1048576", scalar(connection, "SELECT LENGTH(raw) FROM blobs WHERE id = 2"));
            boolean blobRejected = false;
            try {
                statement.execute("CREATE TABLE blob_small (id INT PRIMARY KEY, raw BLOB)");
                try (var insert = connection.prepareStatement("INSERT INTO blob_small VALUES (?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setBytes(2, seventy);
                    insert.executeUpdate();
                }
            } catch (java.sql.SQLException failure) {
                blobRejected = true;
                notes.add("BLOB 70000 rejected: " + failure.getMessage());
            }
            assertTrue(blobRejected, "BLOB accepted 70000 bytes; notes=" + notes);

            statement.execute("CREATE TABLE ids (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(32))");
            long first;
            long second;
            try (var insert = connection.prepareStatement(
                    "INSERT INTO ids (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, "one");
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    first = keys.getLong(1);
                }
                insert.setString(1, "two");
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    second = keys.getLong(1);
                }
            }
            assertTrue(first > 0 && second > first, first + " then " + second);
            notes.add("generated keys " + first + "," + second);

            statement.execute("CREATE TABLE spike_notes (id VARCHAR(64) PRIMARY KEY, body VARCHAR(200))");
            statement.execute("INSERT INTO spike_notes VALUES ('up', 'old')");
            statement.execute("INSERT INTO spike_notes (id, body) VALUES ('up', 'new') "
                    + "ON DUPLICATE KEY UPDATE body = VALUES(body)");
            assertEquals("new", scalar(connection, "SELECT body FROM spike_notes WHERE id = 'up'"));
            boolean alias = false;
            try {
                statement.execute("INSERT INTO spike_notes (id, body) VALUES ('up', 'alias') AS newrow "
                        + "ON DUPLICATE KEY UPDATE body = newrow.body");
                alias = "alias".equals(scalar(connection, "SELECT body FROM spike_notes WHERE id = 'up'"));
            } catch (java.sql.SQLException failure) {
                notes.add("alias upsert failed: " + failure.getMessage());
            }
            notes.add("alias upsert worked: " + alias);
            assertTrue(alias, String.join("\n", notes));

            statement.execute("INSERT IGNORE INTO spike_notes VALUES ('up', 'ignored')");
            assertEquals("alias", scalar(connection, "SELECT body FROM spike_notes WHERE id = 'up'"));

            boolean pipesZero = "0".equals(scalar(connection, "SELECT 'a' || 'b'"));
            notes.add("pipes 'a' || 'b' = " + scalar(connection, "SELECT 'a' || 'b'"));
            assertTrue(pipesZero, String.join("\n", notes));

            statement.execute("CREATE TABLE parents (id VARCHAR(16) PRIMARY KEY)");
            statement.execute("INSERT INTO parents VALUES ('p')");
            boolean fkEnforced = false;
            try {
                statement.execute("CREATE TABLE children (id VARCHAR(16) PRIMARY KEY, parent VARCHAR(16), "
                        + "FOREIGN KEY (parent) REFERENCES parents(id))");
                statement.execute("INSERT INTO children VALUES ('c', 'missing')");
            } catch (java.sql.SQLException failure) {
                fkEnforced = true;
                notes.add("fk rejected: " + failure.getMessage());
            }
            notes.add("fk enforced: " + fkEnforced);

            String lock = scalar(connection, "SELECT GET_LOCK('pkb:hydrate:main', 2)");
            notes.add("GET_LOCK=" + lock);
            String release = scalar(connection, "SELECT RELEASE_LOCK('pkb:hydrate:main')");
            notes.add("RELEASE_LOCK=" + release);

            statement.execute("CREATE TABLE uncommitted_probe (id INT PRIMARY KEY, body VARCHAR(32))");
            statement.execute("INSERT INTO uncommitted_probe VALUES (1, 'dirty')");
        }
        System.out.println("DOLT_SPIKE " + String.join(" | ", notes));
        assertTrue(notes.stream().anyMatch(line -> line.startsWith("GET_LOCK=")), String.join("\n", notes));
    }

    @Test
    void uncommittedRowsRestartBranchVisibilityDiffAndGc() throws Exception {
        DoltServer.ServerInfo info = DoltServer.ensure(project);
        try (Connection connection = connect(info.port(), "pickleball")) {
            exec(connection, "CREATE TABLE persist_probe (id INT PRIMARY KEY, body VARCHAR(32))");
            exec(connection, "INSERT INTO persist_probe VALUES (1, 'dirty')");
            exec(connection, "CALL DOLT_BRANCH('sbx-see')");
        }
        String onBranch;
        try (Connection branch = connect(info.port(), "pickleball")) {
            exec(branch, "USE `pickleball/sbx-see`");
            try {
                onBranch = scalar(branch, "SELECT COUNT(*) FROM persist_probe");
            } catch (RuntimeException failure) {
                onBranch = "absent";
            }
        }
        int port = info.port();
        DoltServer.stop(project);
        DoltServer.ServerInfo again = DoltServer.ensure(project);
        String afterRestart;
        String diff;
        String gc;
        String crossBranchIds;
        try (Connection connection = connect(again.port(), "pickleball")) {
            try {
                afterRestart = scalar(connection, "SELECT COUNT(*) FROM persist_probe");
            } catch (RuntimeException failure) {
                afterRestart = "absent";
            }
            exec(connection, "CREATE TABLE IF NOT EXISTS diff_probe (id INT PRIMARY KEY, body VARCHAR(32))");
            exec(connection, "CALL DOLT_ADD('diff_probe')");
            exec(connection, "CALL DOLT_COMMIT('-m', 'diff base')");
            exec(connection, "INSERT INTO diff_probe VALUES (1, 'working')");
            diff = workingDiff(connection);
            try {
                exec(connection, "CALL DOLT_GC()");
                gc = "ok";
            } catch (RuntimeException failure) {
                gc = "failed: " + oneLine(failure.getMessage());
            }
            exec(connection, "CREATE TABLE IF NOT EXISTS id_probe (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(16))");
            exec(connection, "INSERT INTO id_probe (name) VALUES ('main')");
            exec(connection, "CALL DOLT_COMMIT('-Am', 'ids')");
            exec(connection, "CALL DOLT_BRANCH('sbx-ids')");
            long mainId = Long.parseLong(scalar(connection, "SELECT id FROM id_probe WHERE name = 'main'"));
            try (Connection other = connect(again.port(), "pickleball/sbx-ids")) {
                exec(other, "INSERT INTO id_probe (name) VALUES ('other')");
                long otherId = Long.parseLong(scalar(other, "SELECT id FROM id_probe WHERE name = 'other'"));
                crossBranchIds = mainId + "," + otherId;
            }
        }
        System.out.println("DOLT_SPIKE branchCount=" + onBranch + " afterRestart=" + afterRestart
                + " previousPort=" + port + " newPort=" + again.port() + " diff=" + diff
                + " gc=" + gc + " crossBranchIds=" + crossBranchIds);
        assertEquals("absent", onBranch);
        assertEquals("1", afterRestart);
        assertTrue(diff.contains("working"), diff);
        assertEquals("ok", gc);
    }

    @Test
    void dataDirWithSpacesStarts() throws Exception {
        Path spaced = project.resolve("space dir");
        Files.createDirectories(spaced);
        DoltServer.ServerInfo info = DoltServer.ensure(spaced);
        assertTrue(DoltServer.selectOne(info.port(), 2000));
        DoltServer.stop(spaced);
    }

    private static String workingDiff(Connection connection) {
        String[] queries = {
                "SELECT to_body FROM dolt_diff_diff_probe WHERE to_commit = 'WORKING' LIMIT 1",
                "SELECT to_body FROM DOLT_DIFF('diff_probe', 'HEAD', 'WORKING') LIMIT 1",
                "SELECT to_body FROM dolt_diff('diff_probe', 'HEAD', 'WORKING') LIMIT 1"
        };
        StringBuilder tried = new StringBuilder();
        for (String query : queries) {
            try {
                String value = scalar(connection, query);
                if (value != null && !value.isBlank()) return query + " => " + value;
                tried.append(query).append(" => empty; ");
            } catch (RuntimeException failure) {
                tried.append(oneLine(failure.getMessage())).append("; ");
            }
        }
        return tried.toString();
    }

    private static String oneLine(String message) {
        if (message == null) return "";
        String flat = message.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > 180 ? flat.substring(0, 180) : flat;
    }

    private static Connection connect(int port, String database) throws Exception {
        String url = "jdbc:mariadb://127.0.0.1:" + port + "/" + database + "?user=root&connectTimeout=5000&socketTimeout=15000";
        return DriverManager.getConnection(url);
    }

    private static void exec(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String scalar(Connection connection, String sql) {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) return "";
            String value = rows.getString(1);
            return value == null ? "" : value;
        } catch (Exception failure) {
            throw new RuntimeException(sql + " -> " + failure.getMessage(), failure);
        }
    }
}
