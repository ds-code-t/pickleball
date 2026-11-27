package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.launcher.VerbDispatch;
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

/**
 * {@code store glue put --sandbox=} refreshes that fork's step catalog.
 * Main's {@code step_definitions} stay unchanged.
 */
class ForkGluePutTest {
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
    void gluePutOnTheForkChangesThatCatalogAndNotMain() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        Capture recorded = verb(session, "store", "history", "record", "run-finished",
                "--status=passed", "--scenario=Ada", "--message=kept");
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        stampRun("run-finished", "abc123def", "head-9");
        Capture forked = verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=fork-a");
        assertEquals(0, forked.code, forked.out + forked.err);

        Path body = project.resolve("fork-steps-body.java");
        Files.writeString(body, """
                package demo;
                import io.cucumber.java.en.Given;
                public class ForkSteps {
                    @Given("fork only step")
                    public void forkOnly() {}
                }
                """, StandardCharsets.UTF_8);
        Capture put = verb(session, "store", "glue", "put",
                "--path=src/test/java/demo/ForkSteps.java",
                "--file=" + body.toAbsolutePath(),
                "--sandbox=fork-a");
        assertEquals(0, put.code, put.out + put.err);
        assertEquals(1, definitions("fork-a", "fork only step"), put.out + put.err);
        assertEquals(0, definitions("", "fork only step"));
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

    private int definitions(String sandbox, String pattern) throws Exception {
        Path database = sandbox == null || sandbox.isBlank()
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, sandbox);
        if (!Files.isRegularFile(database)) return 0;
        try (Connection connection = SqliteOpen.open(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM step_definitions WHERE pattern = ? AND removed = 0")) {
            statement.setString(1, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
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
