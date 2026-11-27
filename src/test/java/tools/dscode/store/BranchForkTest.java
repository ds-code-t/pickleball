package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.adapter.cucumber.NativeScenarioRun;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.launcher.VerbDispatch;
import tools.dscode.store.sql.Binding;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code store branch fork} copies a finished run's pin into a cow sandbox.
 * The original run rows stay put. An empty fork is discarded. A second delta
 * refuses discard unless {@code --force}.
 */
class BranchForkTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        StoreExecution.finishScenario("PASSED");
        System.clearProperty("pkb_run_id");
        System.clearProperty("pkb_store_track");
        System.clearProperty("pkb_store_project");
        System.clearProperty("pkb_store_sandbox");
        System.clearProperty("pkb_journal");
        SqlConnections.closeProject(project);
    }

    @Test
    void forkCopiesThePinAndDiscardRefusesASecondDelta() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        Capture recorded = verb(session, "store", "history", "record", "run-finished",
                "--status=passed", "--scenario=Ada", "--message=kept");
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        stampRun("run-finished", "abc123def", "head-9");
        assertEquals(1, logCount("run-finished"));

        Capture forked = verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=fork-a", "--json");
        assertEquals(0, forked.code, forked.out + forked.err);
        assertTrue(forked.out.contains("fork-a"), forked.out);
        assertTrue(Files.isRegularFile(StorePaths.sandboxDatabase(project, "fork-a")), "fork database");

        try (Connection child = SqliteOpen.open(StorePaths.sandboxDatabase(project, "fork-a"))) {
            try (PreparedStatement pin = child.prepareStatement(
                    "SELECT commit_sha, head_id FROM pins WHERE id = 1")) {
                try (ResultSet rows = pin.executeQuery()) {
                    assertTrue(rows.next(), "pin row");
                    assertEquals("abc123def", rows.getString(1));
                    assertEquals("head-9", rows.getString(2));
                }
            }
            try (PreparedStatement delta = child.prepareStatement(
                    "SELECT kind, payload_json FROM deltas ORDER BY seq")) {
                try (ResultSet rows = delta.executeQuery()) {
                    assertTrue(rows.next(), "fork delta");
                    assertEquals("fork", rows.getString(1));
                    String payload = rows.getString(2);
                    assertTrue(payload.contains("\"fromRun\":\"run-finished\""), payload);
                    assertTrue(payload.contains("\"fromHead\":\"head-9\""), payload);
                    assertFalse(rows.next(), "only the fork delta");
                }
            }
        }
        try (Connection runstate = SqliteOpen.open(StorePaths.runState(project))) {
            try (PreparedStatement run = runstate.prepareStatement(
                    "SELECT commit_sha, head_id, sandbox FROM runs WHERE run_id = 'run-finished'")) {
                try (ResultSet rows = run.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("abc123def", rows.getString(1));
                    assertEquals("head-9", rows.getString(2));
                    assertEquals("", rows.getString(3));
                }
            }
        }

        Capture titled = verb(session, "store", "pin", "show", "--sandbox=fork-a");
        assertEquals(0, titled.code, titled.out + titled.err);
        assertTrue(titled.out.contains("commit abc123def"), titled.out);
        assertTrue(titled.out.contains("head head-9"), titled.out);
        assertTrue(titled.out.contains("branch fork-a"), titled.out);

        Capture logged = verb(session, "store", "history", "record", "run-fork-log",
                "--status=passed", "--scenario=Ada", "--message=fork-activity", "--sandbox=fork-a");
        assertEquals(0, logged.code, logged.out + logged.err);
        assertEquals(1, logCount("run-finished"));
        assertEquals(1, logCount("run-fork-log"));

        System.setProperty("pkb_store_track", "true");
        System.setProperty("pkb_store_project", project.toString());
        System.setProperty("pkb_run_id", "run-finished");
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("fork-a"));
        NativeScenarioRun.run(
                EngineScenario.of("Fork activity", List.of(EngineStep.of("* ", "INFO: fork step"))),
                null,
                EngineOptions.of().project(project));
        StoreScope.clear();
        assertEquals(1, logCount("run-finished"));
        assertEquals("", runSandbox("run-finished"));
        assertTrue(forkRunExists(), "fork activity run");

        Capture dirty = verb(session, "store", "file", "put", "--path=notes.txt", "--text=hello", "--sandbox=fork-a");
        assertEquals(0, dirty.code, dirty.out + dirty.err);
        StoreScope.clear();
        Capture refused = verb(session, "store", "branch", "discard", "--name=fork-a");
        assertEquals(2, refused.code, refused.out + refused.err);
        assertTrue(refused.err.contains("error:"), refused.err);
        assertTrue(refused.err.contains("fix:"), refused.err);
        assertTrue(Files.isRegularFile(StorePaths.sandboxDatabase(project, "fork-a")), "non-empty fork stays");
        assertEquals(1, logCount("run-finished"));
        assertTrue(Files.isRegularFile(StorePaths.mainDatabase(project)), "parent stays");

        Capture empty = verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=empty-fork");
        assertEquals(0, empty.code, empty.out + empty.err);
        Capture dropped = verb(session, "store", "branch", "discard", "--name=empty-fork");
        assertEquals(0, dropped.code, dropped.out + dropped.err);
        assertFalse(Files.isRegularFile(StorePaths.sandboxDatabase(project, "empty-fork")), "empty fork removed");
        assertTrue(Files.isRegularFile(StorePaths.sandboxDatabase(project, "fork-a")), "other fork stays");
        assertEquals("abc123def", runCommit("run-finished"));
    }

    private void stampRun(String runId, String commit, String head) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE runs SET commit_sha = ?, head_id = ? WHERE run_id = ?")) {
            statement.setString(1, commit);
            statement.setString(2, head);
            statement.setString(3, runId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private int logCount(String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM run_logs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private String runSandbox(String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT sandbox FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private String runCommit(String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT commit_sha FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private boolean forkRunExists() throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM runs WHERE sandbox = 'fork-a' AND run_id <> 'run-fork-log'")) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) > 0;
            }
        }
    }

    private Capture verb(String session, String... args) {
        String[] argv = new String[args.length + 2];
        System.arraycopy(args, 0, argv, 0, args.length);
        argv[args.length] = "--session=" + session;
        argv[args.length + 1] = "--project=" + project.toAbsolutePath();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                args[0],
                VerbArgs.capture(argv),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        StoreScope.clear();
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
