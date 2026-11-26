package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Promote writes an expensive parent delta and applies the requested run-log policy. */
class SandboxPromoteTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void archiveWritesOneParentDeltaAndLeavesTheRun() throws Exception {
        String original = feature("1");
        writeFeature(original);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "child", "--project=" + project).code);
        StoreScope.clear();
        String edited = feature("9");
        Capture put = cli("store", "feature", "put", "features/pay.feature", "--text=" + edited,
                "--sandbox=child", "--project=" + project);
        assertEquals(0, put.code, put.err);
        StoreScope.clear();
        insertRun("run-archive", "child");
        Capture promoted = cli("store", "sandbox", "promote", "child",
                "--to=parent", "--logs=archive", "--project=" + project);
        assertEquals(0, promoted.code, promoted.out + promoted.err);
        assertTrue(promoted.out.contains("deltas=1"), promoted.out);
        StoreScope.clear();
        SqlConnections.closeProject(project);

        assertEquals(edited, Files.readString(project.resolve("features/pay.feature")));
        try (Connection main = SqliteOpen.open(StorePaths.mainDatabase(project));
             PreparedStatement deltas = main.prepareStatement(
                     "SELECT COUNT(*), COALESCE(SUM(expensive), 0) FROM deltas")) {
            try (ResultSet rows = deltas.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "parent delta count");
                assertEquals(1, rows.getInt(2), "expensive flag");
            }
        }
        try (Connection child = SqliteOpen.open(StorePaths.sandboxDatabase(project, "child"));
             PreparedStatement mark = child.prepareStatement(
                     "SELECT value FROM schema_meta WHERE `key` = 'head_promoted'")) {
            try (ResultSet rows = mark.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("1", rows.getString(1));
            }
        }
        try (Connection runstate = SqliteOpen.open(StorePaths.runState(project))) {
            try (PreparedStatement runs = runstate.prepareStatement(
                    "SELECT note FROM runs WHERE run_id = 'run-archive'")) {
                try (ResultSet rows = runs.executeQuery()) {
                    assertTrue(rows.next(), "run row was removed");
                    assertTrue(rows.getString(1).startsWith("archived"), rows.getString(1));
                }
            }
            try (PreparedStatement logs = runstate.prepareStatement(
                    "SELECT COUNT(*) FROM run_logs WHERE sandbox = 'child'")) {
                try (ResultSet rows = logs.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void clearDropsChildRunLogs() throws Exception {
        writeFeature(feature("1"));
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "child", "--project=" + project).code);
        StoreScope.clear();
        insertRun("run-clear", "child");
        Capture promoted = cli("store", "sandbox", "promote", "child",
                "--to=parent", "--logs=clear", "--project=" + project);
        assertEquals(0, promoted.code, promoted.out + promoted.err);
        StoreScope.clear();
        SqlConnections.closeProject(project);
        try (Connection runstate = SqliteOpen.open(StorePaths.runState(project))) {
            try (PreparedStatement runs = runstate.prepareStatement(
                    "SELECT COUNT(*) FROM runs WHERE run_id = 'run-clear'")) {
                try (ResultSet rows = runs.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1), "run row was removed");
                }
            }
            try (PreparedStatement logs = runstate.prepareStatement(
                    "SELECT COUNT(*) FROM run_logs WHERE sandbox = 'child'")) {
                try (ResultSet rows = logs.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void bothSidesConflictOnMainIsReportedAndDoesNotWriteTheFile() throws Exception {
        String original = feature("1");
        writeFeature(original);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "child", "--project=" + project).code);
        StoreScope.clear();
        String databaseSide = feature("2");
        Capture mainEdit = cli("store", "feature", "put", "features/pay.feature", "--text=" + databaseSide,
                "--project=" + project);
        assertEquals(0, mainEdit.code, mainEdit.err);
        StoreScope.clear();
        String diskSide = feature("3");
        Files.writeString(project.resolve("features/pay.feature"), diskSide);
        String childSide = feature("4");
        Capture childEdit = cli("store", "feature", "put", "features/pay.feature", "--text=" + childSide,
                "--sandbox=child", "--project=" + project);
        assertEquals(0, childEdit.code, childEdit.err);
        StoreScope.clear();
        Capture promoted = cli("store", "sandbox", "promote", "child",
                "--to=parent", "--logs=archive", "--project=" + project);
        assertEquals(1, promoted.code, promoted.out + promoted.err);
        assertTrue(promoted.out.contains("conflict: features/pay.feature"), promoted.out);
        StoreScope.clear();
        SqlConnections.closeProject(project);
        assertEquals(diskSide, Files.readString(project.resolve("features/pay.feature")));
        try (Connection main = SqliteOpen.open(StorePaths.mainDatabase(project));
             PreparedStatement files = main.prepareStatement(
                     "SELECT CAST(raw_content AS TEXT) FROM files WHERE path = 'features/pay.feature'")) {
            try (ResultSet rows = files.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(databaseSide, rows.getString(1));
            }
        }
    }

    @Test
    void missingLogsExits2() throws Exception {
        writeFeature(feature("1"));
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "child", "--project=" + project).code);
        StoreScope.clear();
        Capture promoted = cli("store", "sandbox", "promote", "child", "--to=parent", "--project=" + project);
        assertEquals(2, promoted.code, promoted.out + promoted.err);
        assertTrue(promoted.err.contains("--logs=clear|archive"), promoted.err);
    }

    private void writeFeature(String text) throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), text);
    }

    private static String feature(String amount) {
        return "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is " + amount + "\n";
    }

    private void insertRun(String runId, String sandbox) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project))) {
            try (PreparedStatement run = connection.prepareStatement("""
                    INSERT INTO runs(run_id, started_at, status, scenario, passed, failed, note, sandbox)
                    VALUES (?, '2026-11-26T00:00:00Z', 'passed', 'Ada', 1, 0, 'kept', ?)
                    """)) {
                run.setString(1, runId);
                run.setString(2, sandbox);
                run.executeUpdate();
            }
            try (PreparedStatement log = connection.prepareStatement("""
                    INSERT INTO run_logs(run_id, logged_at, entity, message, sandbox)
                    VALUES (?, '2026-11-26T00:00:01Z', 'scenario', 'ok', ?)
                    """)) {
                log.setString(1, runId);
                log.setString(2, sandbox);
                log.executeUpdate();
            }
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
