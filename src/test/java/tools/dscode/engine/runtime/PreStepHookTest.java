package tools.dscode.engine.runtime;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.adapter.cucumber.NativeScenarioRun;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.launcher.VerbDispatch;
import tools.dscode.store.StoreExecution;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fork map delta is visible to that step and its child. Earlier and later
 * steps do not see it. A secret key refuses cleartext.
 */
class PreStepHookTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        CityProbe.seen.clear();
        StoreScope.clear();
        StoreExecution.finishScenario("PASSED");
        System.clearProperty("pkb_run_id");
        System.clearProperty("pkb_store_track");
        System.clearProperty("pkb_store_project");
        System.clearProperty("pkb_store_sandbox");
        System.clearProperty("pkb_journal");
        System.clearProperty("pkb_engine_log");
        SqlConnections.closeProject(project);
    }

    @Test
    void stepTwoAndItsChildSeeTheForkMapAndASecretRefusesCleartext() throws Exception {
        System.setProperty("pkb_journal", "off");
        System.setProperty("pkb_engine_log", "match");
        String session = SpineSession.create(project, "agent-1");
        Capture recorded = verb(session, "store", "history", "record", "run-finished",
                "--status=passed", "--scenario=Ada", "--message=kept");
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        stampRun("run-finished", "abc123def", "head-9");

        Capture forked = verb(session, "store", "branch", "fork",
                "--from-run=run-finished", "--name=fork-a");
        assertEquals(0, forked.code, forked.out + forked.err);

        Capture set = verb(session, "engine", "map", "set",
                "--run=run-finished", "--step=2", "--key=city", "--value=Denver", "--sandbox=fork-a");
        assertEquals(0, set.code, set.out + set.err);
        assertEquals(1, mapDeltas("fork-a"), set.out + set.err);
        assertEquals(0, mapDeltas(""));
        assertEquals("abc123def", runCommit("run-finished"));

        Capture shown = verb(session, "engine", "map", "show",
                "--run=run-finished", "--step=2", "--sandbox=fork-a", "--json");
        assertEquals(0, shown.code, shown.out + shown.err);
        assertTrue(shown.out.contains("boundRun"), shown.out);
        assertTrue(shown.out.contains("run-finished"), shown.out);
        assertTrue(shown.out.contains("boundStep"), shown.out);
        assertTrue(shown.out.contains("city"), shown.out);
        assertTrue(shown.out.contains("Denver"), shown.out);
        assertTrue(shown.out.contains("parent"), shown.out);

        Capture secret = verb(session, "engine", "map", "set",
                "--run=run-finished", "--step=2", "--key=password", "--value=hunter2", "--sandbox=fork-a");
        assertEquals(2, secret.code, secret.out + secret.err);
        assertTrue(secret.err.contains("error:"), secret.err);
        assertTrue(secret.err.contains("fix:"), secret.err);
        assertTrue(secret.err.contains("cleartext"), secret.err);
        assertEquals(1, mapDeltas("fork-a"));

        Capture masked = verb(session, "engine", "map", "set",
                "--run=run-finished", "--step=2", "--key=password", "--value=<password:db>", "--sandbox=fork-a");
        assertEquals(0, masked.code, masked.out + masked.err);
        Capture secretShow = verb(session, "engine", "map", "show",
                "--run=run-finished", "--step=2", "--sandbox=fork-a", "--json");
        assertEquals(0, secretShow.code, secretShow.out + secretShow.err);
        assertTrue(secretShow.out.contains("~MASK;"), secretShow.out);
        assertFalse(secretShow.out.contains("hunter2"), secretShow.out);
        assertFalse(secretShow.out.contains("<password:db>"), secretShow.out);

        Capture onMain = verb(session, "engine", "map", "set",
                "--run=run-finished", "--step=2", "--key=city", "--value=Denver");
        assertEquals(2, onMain.code, onMain.out + onMain.err);
        assertTrue(onMain.err.contains("error:"), onMain.err);
        assertTrue(onMain.err.contains("fork"), onMain.err);

        CityProbe.seen.clear();
        System.setProperty("pkb_store_project", project.toString());
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("fork-a"));
        NativeScenarioRun.run(scenario(), new StepRegistry().scan(CityProbe.class), EngineOptions.of().project(project));
        StoreScope.clear();
        assertEquals("", CityProbe.seen.get("one"), String.valueOf(CityProbe.seen));
        assertEquals("Denver", CityProbe.seen.get("two"), String.valueOf(CityProbe.seen));
        assertEquals("Denver", CityProbe.seen.get("child"), String.valueOf(CityProbe.seen));
        assertEquals("", CityProbe.seen.get("three"), String.valueOf(CityProbe.seen));

        String log = Files.readString(project.resolve(".pickleball/engine/engine.log"));
        assertTrue(log.contains("pre-step hook keys=2"), log);
        assertFalse(log.contains("Denver"), log);
        assertFalse(log.contains("hunter2"), log);

        String page = Files.readString(Path.of("workbench-ui/pages/index.html"));
        assertTrue(page.contains("[\"map\", \"show\""), page);
        assertTrue(page.contains("[\"map\", \"set\""), page);
        assertTrue(page.contains("[\"timeline\""), page);
        assertTrue(page.contains("artifact"), page);
        assertTrue(page.contains("id=\"map\""), page);
        assertTrue(page.contains("id=\"logs\""), page);
        assertTrue(page.contains("id=\"screenshots\""), page);
    }

    private static EngineScenario scenario() {
        return EngineScenario.of("Map hook", List.of(
                EngineStep.of("Given ", "note city one"),
                EngineStep.of("Given ", "note city two"),
                EngineStep.of("Given ", "note city child").withMetadata("metaText", ":"),
                EngineStep.of("Given ", "note city three")));
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

    private int mapDeltas(String sandbox) throws Exception {
        Path database = sandbox == null || sandbox.isBlank()
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, sandbox);
        if (!Files.isRegularFile(database)) return 0;
        try (Connection connection = SqliteOpen.open(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM deltas WHERE kind = 'map'");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
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

    public static class CityProbe {
        static final Map<String, String> seen = new LinkedHashMap<>();

        @Given("note city one")
        public void one() {
            remember("one");
        }

        @Given("note city two")
        public void two() {
            remember("two");
        }

        @Given("note city child")
        public void child() {
            remember("child");
        }

        @Given("note city three")
        public void three() {
            remember("three");
        }

        private static void remember(String label) {
            Object value = ParsingMap.getRunningParsingMap().get("city");
            String text = "";
            if (value instanceof com.fasterxml.jackson.databind.JsonNode node) {
                text = node.isNull() ? "" : node.asText("");
            } else if (value != null) {
                text = String.valueOf(value);
            }
            seen.put(label, text);
        }
    }
}
