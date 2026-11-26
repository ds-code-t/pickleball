package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.coredefinitions.ObjectRegistrationSteps;
import tools.dscode.store.dashboard.DashboardHtml;
import tools.dscode.store.dashboard.DashboardSnapshot;
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

/** Report scripts name a head and step references. They do not embed screenshot bytes or cleartext secrets. */
class ReportScriptTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @BeforeEach
    void isolateScope() {
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
        ExceptionCluster.clear();
    }

    @AfterEach
    void release() {
        restore();
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreExecution.finishScenario("PASSED");
        StoreScope.clear();
        ExceptionCluster.clear();
    }

    @Test
    void scriptReplaysAnotherHeadAndHtmlHidesTheSecret() throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            exec(database, """
                    INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash)
                    VALUES ('current', '', 0, 0, '')
                    """);
            exec(database, """
                    INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash)
                    VALUES ('other', '', 0, 0, '')
                    """);
            insertRun(database, "run-a", "password=fixture-secret-r7", "current", "2026-11-26T01:00:00Z");
            insertRun(database, "run-b", "the card was declined", "current", "2026-11-26T01:05:00Z");
            exec(database, """
                    INSERT INTO run_snapshots(
                        run_id, commit_sha, head_id, sandbox, parent_commit, parent_head_id, enabled_hash, started_at)
                    VALUES ('run-a', 'abc', 'current', 'main', '', '', 'enabled', '2026-11-26T01:00:00Z')
                    """);
            exec(database, """
                    INSERT INTO step_definitions(
                        definition_id, language, pattern, method_name, source_path, source_line,
                        content_hash, hydrated_at)
                    VALUES ('def-keep', 'java', 'keep', 'keep', 'Keep.java', 1, 'hash-keep', '2026-11-26T00:00:00Z')
                    """);
            exec(database, """
                    INSERT INTO step_definitions(
                        definition_id, language, pattern, method_name, source_path, source_line,
                        content_hash, hydrated_at)
                    VALUES ('def-gone', 'java', 'gone', 'gone', 'Gone.java', 1, 'hash-gone', '2026-11-26T00:00:00Z')
                    """);
            exec(database, """
                    INSERT INTO scenario_executions(
                        exec_id, run_id, scenario_key, scenario_hash, started_at, status, sandbox)
                    VALUES ('exec-a', 'run-a', 'feature|1', 'h', '2026-11-26T01:00:00Z', 'FAILED', '')
                    """);
            exec(database, """
                    INSERT INTO scenario_executions(
                        exec_id, run_id, scenario_key, scenario_hash, started_at, status, sandbox)
                    VALUES ('exec-b', 'run-b', 'feature|2', 'h', '2026-11-26T01:05:00Z', 'PASSED', '')
                    """);
            exec(database, """
                    INSERT INTO step_executions(
                        step_exec_id, scenario_exec_id, step_ord, step_text, step_text_hash, definition_id,
                        started_at, status, sandbox)
                    VALUES ('step-a-keep', 'exec-a', 1, 'keep the step', 'text-keep', 'def-keep',
                            '2026-11-26T01:00:00Z', 'PASSED', '')
                    """);
            exec(database, """
                    INSERT INTO step_executions(
                        step_exec_id, scenario_exec_id, step_ord, step_text, step_text_hash, definition_id,
                        started_at, status, sandbox)
                    VALUES ('step-a-gone', 'exec-a', 2, 'gone step', 'text-gone', 'def-gone',
                            '2026-11-26T01:00:01Z', 'FAILED', '')
                    """);
            exec(database, """
                    INSERT INTO step_executions(
                        step_exec_id, scenario_exec_id, step_ord, step_text, step_text_hash, definition_id,
                        started_at, status, sandbox)
                    VALUES ('step-b-keep', 'exec-b', 1, 'keep the step', 'text-keep', 'def-keep',
                            '2026-11-26T01:05:00Z', 'PASSED', '')
                    """);
            Path png = project.resolve("reports/a.png");
            Files.createDirectories(png.getParent());
            Files.writeString(png, "PNGPIXELS", StandardCharsets.UTF_8);
            exec(database, """
                    INSERT INTO artifacts(artifact_id, run_id, kind, path, retained_at, sandbox)
                    VALUES ('art-a', 'run-a', 'screenshot', 'reports/a.png', '2026-11-26T01:00:02Z', '')
                    """);
        }

        int before = countRuns();
        Capture written = cli("store", "report", "write", "--run=run-a", "--out=report.pkb.json",
                "--project=" + project);
        assertEquals(0, written.code, written.out + written.err);
        String script = Files.readString(project.resolve("report.pkb.json"));
        assertTrue(script.contains("pickleball.report-script"), script);
        assertTrue(script.contains("reports/a.png"), script);
        assertTrue(script.contains("sha256"), script);
        assertFalse(script.contains("PNGPIXELS"), script);
        assertFalse(script.contains("fixture-secret-r7"), script);

        Capture replay = cli("store", "run", "--script=report.pkb.json", "--project=" + project);
        assertEquals(0, replay.code, replay.out + replay.err);
        assertTrue(replay.out.contains(" head current"), replay.out);
        assertEquals(before + 1, countRuns());
        assertEquals("password=fixture-secret-r7", noteOf("run-a"));

        Capture other = cli("store", "run", "--script=report.pkb.json", "--head=other", "--project=" + project);
        assertEquals(0, other.code, other.out + other.err);
        assertTrue(other.out.contains(" head other"), other.out);
        assertEquals(before + 2, countRuns());
        assertEquals("password=fixture-secret-r7", noteOf("run-a"));
        assertTrue(headIds().contains("other"), headIds().toString());

        Capture html = cli("store", "report", "export", "--run=run-a", "--html=page.html", "--project=" + project);
        assertEquals(0, html.code, html.out + html.err);
        String page = Files.readString(project.resolve("page.html"));
        assertTrue(page.contains("run-a"), page);
        assertTrue(page.contains("id=\"run-timeline\""), page);
        assertTrue(page.contains("the card was declined"), page);
        assertFalse(page.contains("fixture-secret-r7"), page);

        Capture diff = cli("store", "diff", "--run=run-a", "--run=run-b", "--json", "--project=" + project);
        assertEquals(0, diff.code, diff.out + diff.err);
        assertTrue(diff.out.contains("break"), diff.out);
    }

    @Test
    void staticTimelineOrdersByStartAndKeepsJsonOrder() {
        DashboardSnapshot.RunView later = new DashboardSnapshot.RunView(
                "later", "2026-11-26T02:00:00Z", "", "FAILED", "pay", 0, 1,
                "password=fixture-secret-r7", "", "", "",
                List.of(), List.of(), List.of(), List.of());
        DashboardSnapshot.RunView earlier = new DashboardSnapshot.RunView(
                "earlier", "2026-11-26T01:00:00Z", "", "FAILED", "pay", 0, 1,
                "the card was declined", "", "", "",
                List.of(), List.of(), List.of(), List.of());
        String page = DashboardHtml.render(new DashboardSnapshot(
                "2026-11-26T03:00:00Z", List.of(later, earlier), List.of(), List.of(), List.of(), List.of(), List.of()));
        int laterJson = page.indexOf("\"id\":\"later\"");
        int earlierJson = page.indexOf("\"id\":\"earlier\"");
        assertTrue(laterJson >= 0 && earlierJson > laterJson, page);
        int section = page.indexOf("id=\"run-timeline\"");
        int earlierList = page.indexOf("earlier", section);
        int laterList = page.indexOf("later", section);
        assertTrue(section > 0 && earlierList > section && laterList > earlierList, page);
        assertTrue(page.contains("the card was declined"), page);
        assertFalse(page.contains("fixture-secret-r7"), page);
        assertTrue(page.contains("~MASK;"), page);
    }

    @Test
    void recordEntityStoresMentionAndMasksASecret() throws Exception {
        track("entity-run");
        StoreExecution.beforeStep(project, "Record", "memory.feature", 2, "RECORD ENTITY: \"Ada\" as Account#ada", 3);
        ObjectRegistrationSteps.recordEntity("Ada", "Account", "ada");
        assertTrue(payload("Account", "ada").contains("Ada"), payload("Account", "ada"));
        assertEquals("mention", usage("Account", "ada"));
        assertEquals(1, snapshotEntities("entity-run", "Account", "ada"));
        StoreExecution.finishScenario("PASSED");

        track("secret-run");
        StoreExecution.beforeStep(project, "Record", "memory.feature", 2, "RECORD ENTITY: \"secret\" as Account#hidden", 3);
        ObjectRegistrationSteps.recordEntity("password=hunter2", "Account", "hidden");
        String secret = payload("Account", "hidden");
        assertTrue(secret.contains("~MASK;"), secret);
        assertFalse(secret.contains("hunter2"), secret);
    }

    private void track(String runId) {
        set("pkb_store", "true");
        set("pkb_store_track", "true");
        set("pkb_run_id", runId);
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
    }

    private static void insertRun(StoreDatabase database, String id, String note, String head, String started) throws Exception {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO runs(
                    run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox,
                    git_commit, feature_path, commit_sha, head_id, parent_commit, parent_head_id)
                VALUES (?, ?, ?, 'FAILED', 'pay', 0, 1, ?, 'main', '', '', 'abc', ?, '', '')
                """)) {
            statement.setString(1, id);
            statement.setString(2, started);
            statement.setString(3, started);
            statement.setString(4, note);
            statement.setString(5, head);
            statement.executeUpdate();
        }
    }

    private static void exec(StoreDatabase database, String sql) throws Exception {
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private int countRuns() throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM runs");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private String noteOf(String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement("SELECT note FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), runId);
                return rows.getString(1);
            }
        }
    }

    private List<String> headIds() throws Exception {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement("SELECT head_id FROM run_snapshots");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString(1));
        }
        return ids;
    }

    private String payload(String type, String id) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement(
                     "SELECT payload_json FROM entities WHERE entity_type = ? AND entity_id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), type + "#" + id);
                return rows.getString(1);
            }
        }
    }

    private String usage(String type, String id) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT operation FROM entity_usages WHERE entity_type = ? AND entity_id = ?")) {
            statement.setString(1, type);
            statement.setString(2, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), type + "#" + id);
                return rows.getString(1);
            }
        }
    }

    private int snapshotEntities(String runId, String type, String id) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM run_snapshot_entities
                     WHERE run_id = ? AND entity_type = ? AND entity_id = ?
                     """)) {
            statement.setString(1, runId);
            statement.setString(2, type);
            statement.setString(3, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }

    private record Capture(int code, String out, String err) {
    }
}
