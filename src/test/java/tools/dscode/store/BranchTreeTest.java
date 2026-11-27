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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code store branch tree} lists the forks that touched one file.
 * {@code branch link --off} does not change where promote writes.
 */
class BranchTreeTest {
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
    void twoForksAppearAndLinkOffLeavesPromoteOnTheCowParent() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        Capture recorded = verb(session, "store", "history", "record", "run-finished",
                "--status=passed", "--scenario=Ada", "--message=kept");
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        stampRun("run-finished", "abc123def", "head-9");
        assertEquals(0, verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=touch-a").code);
        assertEquals(0, verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=touch-b").code);
        assertEquals(0, verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=absent-c").code);

        Capture putA = verb(session, "store", "file", "put",
                "--path=notes.txt", "--text=hello-a", "--sandbox=touch-a");
        assertEquals(0, putA.code, putA.out + putA.err);
        Capture putB = verb(session, "store", "file", "put",
                "--path=notes.txt", "--text=hello-b", "--sandbox=touch-b");
        assertEquals(0, putB.code, putB.out + putB.err);
        Capture putC = verb(session, "store", "file", "put",
                "--path=other.txt", "--text=nope", "--sandbox=absent-c");
        assertEquals(0, putC.code, putC.out + putC.err);

        String childHead = headPayload("touch-a", "notes.txt");
        Capture tree = verb(session, "store", "branch", "tree", "--file=notes.txt", "--json");
        assertEquals(0, tree.code, tree.out + tree.err);
        assertTrue(tree.out.contains("touch-a"), tree.out);
        assertTrue(tree.out.contains("touch-b"), tree.out);
        assertFalse(tree.out.contains("absent-c"), tree.out);
        assertTrue(tree.out.contains("notes.txt"), tree.out);
        int firstA = tree.out.indexOf("touch-a");
        int firstB = tree.out.indexOf("touch-b");
        assertTrue(firstA >= 0 && firstB >= 0, tree.out);

        Capture bare = verb(session, "store", "branch", "promote", "touch-a", "--to=parent");
        assertEquals(2, bare.code, bare.out + bare.err);
        assertTrue(bare.err.contains("--logs=clear|archive"), bare.err);

        Capture linked = verb(session, "store", "branch", "link", "touch-a",
                "--parent=touch-b", "--off");
        assertEquals(0, linked.code, linked.out + linked.err);
        assertEquals(0, linkedFlag("touch-a"));
        assertEquals("touch-b", linkedParent("touch-a"));

        Capture promoted = verb(session, "store", "branch", "promote", "touch-a",
                "--to=parent", "--logs=clear");
        assertEquals(0, promoted.code, promoted.out + promoted.err);
        assertEquals("hello-a", Files.readString(project.resolve("notes.txt"), StandardCharsets.UTF_8).trim());
        assertEquals("hello-b", fileText("touch-b", "notes.txt"));
        assertEquals(childHead, headPayload("", "notes.txt"));
        assertEquals(0, linkedFlag("touch-a"));

        String page = Files.readString(Path.of("workbench-ui/pages/index.html"));
        assertTrue(page.contains("[\"branch\", \"promote\""), page);
        assertTrue(page.contains("[\"branch\", \"tree\""), page);
        assertTrue(page.contains("[\"branch\", \"link\""), page);
        assertTrue(page.contains("--logs="), page);
        assertTrue(page.contains("id=\"promote\""), page);
        assertTrue(page.contains("id=\"tree\""), page);
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

    private String headPayload(String sandbox, String path) throws Exception {
        Path database = sandbox == null || sandbox.isBlank()
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, sandbox);
        if (!Files.isRegularFile(database)) return "";
        try (Connection connection = SqliteOpen.open(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload_json FROM head_rows WHERE tbl = 'files' AND pk = ?")) {
            statement.setString(1, path);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getString(1) != null ? rows.getString(1) : "";
            }
        }
    }

    private String fileText(String sandbox, String path) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.sandboxDatabase(project, sandbox));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT CAST(raw_content AS TEXT) FROM files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), path);
                return rows.getString(1);
            }
        }
    }

    private int linkedFlag(String sandbox) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.sandboxDatabase(project, sandbox));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT linked FROM branch_parents WHERE name = ?")) {
            statement.setString(1, sandbox);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private String linkedParent(String sandbox) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.sandboxDatabase(project, sandbox));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT parent_name FROM branch_parents WHERE name = ?")) {
            statement.setString(1, sandbox);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
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
