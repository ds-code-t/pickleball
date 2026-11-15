package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One path through catalog, execution, usage, link, artifact, query, timeline, and retain. */
class DaltonLinkedTrackingSmokeTest {
    private static final String SECRET = "cleartext-secret-99";

    @TempDir
    Path project;

    @Test
    void linkedRowsQueryTimelineAndRetainLeaveDefinitionsAndReports() throws Exception {
        String previousDalton = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        String previousLog = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton", "true");
            System.setProperty("pkb_dalton_project", project.toString());
            System.setProperty("pkb_dalton_log", "track");

            Path java = project.resolve("src/test/java/demo/Steps.java");
            Files.createDirectories(java.getParent());
            Files.writeString(java, """
                    package demo;
                    public class Steps {
                        @When("the total is {int}")
                        public void total(int amount) {}
                    }
                    """, StandardCharsets.UTF_8);
            Path entity = project.resolve("dalton-entities/Account/ada.entity");
            Files.createDirectories(entity.getParent());
            Files.write(entity, DaltonEntities.canonicalFile("Account", "ada", 1,
                    "{\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}"));
            Path report = project.resolve("reports/diagnostic-runs/old-run");
            Files.createDirectories(report);
            Path index = report.resolve("run-index.json");
            String indexJson = """
                    {"schemaVersion":1,"runId":"old-run","outcome":"PASSED","startedAt":"2020-01-01T00:00:00Z","counts":{"passed":1,"failed":0},"scenarios":[{"name":"Pay"}]}
                    """;
            Files.writeString(index, indexJson, StandardCharsets.UTF_8);
            Path shot = report.resolve("shot.png");
            byte[] png = new byte[] {(byte) 137, 80, 78, 71};
            Files.write(shot, png);

            Capture hydrated = run("dalton", "hydrate", "--project=" + project);
            assertEquals(0, hydrated.code, hydrated.err);
            assertTrue(count("SELECT COUNT(*) FROM step_definitions") > 0);

            Capture recorded = run("dalton", "history", "record", "old-run",
                    "--from=reports/diagnostic-runs/old-run",
                    "--started=2020-01-01T00:00:00Z", "--status=PASSED", "--scenario=Pay",
                    "--message=opened", "--link=Account#ada", "--project=" + project);
            assertEquals(0, recorded.code, recorded.err);
            assertTrue(recorded.out.contains("artifacts 1"), recorded.out);
            String step = DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    "old-run", "features/pay.feature|2", "Pay", null, null, 1,
                    "the total is 10", 3, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
            assertTrue(DaltonArtifacts.link(project, "old-run", shot, step));
            Capture used = run("dalton", "entity", "use", "Account", "ada",
                    "--field=password", "--operation=write", "--value-ref=" + SECRET,
                    "--step=" + step, "--project=" + project);
            assertEquals(0, used.code, used.err);
            assertTrue(used.out.contains("ref=~MASK;"), used.out);
            DaltonRunContext.activate(project, step);
            assertEquals("Ada", DaltonEntities.lookup("entity:Account#ada.name"));
            assertEquals("<password:qaDb>", DaltonEntities.lookup("entity:Account#ada.password"));

            Capture timeline = run("dalton", "timeline", "--entity=Account#ada", "--json", "--project=" + project);
            assertEquals(0, timeline.code, timeline.err);
            assertTrue(timeline.out.contains("\"entityId\":\"ada\""), timeline.out);
            Capture query = run("dalton", "query", "--entity=Account#ada", "--json", "--project=" + project);
            assertEquals(0, query.code, query.err);
            assertTrue(query.out.contains("\"operation\":\"write\""), query.out);
            assertFalse(timeline.out.contains(SECRET), timeline.out);
            assertFalse(query.out.contains(SECRET), query.out);
            String database = DaltonDatabase.storedText(project);
            assertFalse(database.contains(SECRET), "cleartext secret stored");
            String log = Files.readString(DaltonPaths.log(project), StandardCharsets.UTF_8);
            assertFalse(log.contains(SECRET), log);
            assertTrue(log.contains(" track "), log);
            assertTrue(log.startsWith("20"), log);

            run("dalton", "history", "record", "new-run", "--status=PASSED", "--scenario=Keep",
                    "--project=" + project);
            DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    "new-run", "features/pay.feature|9", "Keep", null, null, 1,
                    "the total is 1", 8, "PASSED", null, null));
            int definitions = count("SELECT COUNT(*) FROM step_definitions");
            Capture retained = run("dalton", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
            assertEquals(0, retained.code, retained.err);
            assertTrue(retained.out.contains("reports kept"), retained.out);
            assertEquals(png.length, Files.readAllBytes(shot).length);
            assertEquals(indexJson, Files.readString(index, StandardCharsets.UTF_8));
            assertEquals(definitions, count("SELECT COUNT(*) FROM step_definitions"));
            assertEquals(0, count("SELECT COUNT(*) FROM scenario_executions WHERE run_id='old-run'"));
            assertEquals(0, count("SELECT COUNT(*) FROM artifacts WHERE run_id='old-run'"));
            assertEquals(0, count("SELECT COUNT(*) FROM log_entity_links"));
            assertEquals(0, count("SELECT COUNT(*) FROM entity_usages WHERE step_exec_id='" + step + "'"));
            assertEquals(1, count("SELECT COUNT(*) FROM scenario_executions WHERE run_id='new-run'"));
            Capture after = run("dalton", "timeline", "--json", "--project=" + project);
            assertFalse(after.out.contains("old-run"), after.out);
            assertTrue(after.out.contains("new-run"), after.out);
        } finally {
            restore("pkb_dalton", previousDalton);
            restore("pkb_dalton_project", previousProject);
            restore("pkb_dalton_log", previousLog);
            DaltonRunContext.clear();
        }
    }

    @Test
    void hydrateTwiceTwoRunsAndConfiguredProject() throws Exception {
        Path configured = Files.createTempDirectory("dalton-smoke-configured");
        Path java = project.resolve("src/test/java/demo/Steps.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package demo;
                public class Steps {
                    @When("the total is {int}")
                    public void total(int amount) {}
                }
                """, StandardCharsets.UTF_8);
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        String track = System.getProperty("pkb_dalton_track");
        String run = System.getProperty("pkb_run_id");
        try {
            System.setProperty("pkb_dalton", "true");
            System.setProperty("pkb_dalton_project", project.toString());
            Capture first = run("dalton", "hydrate", "--project=" + project);
            assertEquals(0, first.code, first.err);
            String step = DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    "smoke-1", "features/pay.feature|2", "Pay", null, null, 1,
                    "the total is 10", 3, "PASSED", null, null));
            assertFalse(step.isBlank());
            Capture second = run("dalton", "hydrate", "--project=" + project);
            assertEquals(0, second.code, second.err);
            assertTrue(count("SELECT COUNT(*) FROM step_executions WHERE step_exec_id = '" + step + "'") == 1);
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM step_executions s
                    JOIN step_definitions d ON d.definition_id = s.definition_id
                    WHERE s.step_exec_id = '""" + step + "'"));

            System.setProperty("pkb_dalton_track", "true");
            System.setProperty("pkb_dalton_project", configured.toString());
            System.clearProperty("pkb_run_id");
            DaltonExecution.beforeStep(project, "One", "features/pay.feature", 2, "alpha", 3);
            String firstRun = System.getProperty("pkb_run_id");
            DaltonExecution.afterStep("PASSED");
            DaltonExecution.finishScenario("PASSED");
            System.clearProperty("pkb_run_id");
            DaltonExecution.beforeStep(project, "Two", "features/pay.feature", 4, "beta", 5);
            String secondRun = System.getProperty("pkb_run_id");
            DaltonExecution.afterStep("FAILED");
            DaltonExecution.finishScenario("FAILED");
            assertNotEquals(firstRun, secondRun);
            assertEquals(1, count(configured, "SELECT COUNT(*) FROM runs WHERE run_id = '" + firstRun + "' AND status = 'PASSED'"));
            assertEquals(1, count(configured, "SELECT COUNT(*) FROM runs WHERE run_id = '" + secondRun + "' AND status = 'FAILED'"));
            assertFalse(DaltonPaths.ready(project)
                    && count(project, "SELECT COUNT(*) FROM runs WHERE run_id = '" + firstRun + "'") > 0);
            String timeline = run("dalton", "timeline", "--json", "--project=" + configured).out;
            assertTrue(timeline.contains(firstRun), timeline);
            assertTrue(timeline.contains(secondRun), timeline);
        } finally {
            restore("pkb_dalton", previous);
            restore("pkb_dalton_project", previousProject);
            restore("pkb_dalton_track", track);
            restore("pkb_run_id", run);
            DaltonExecution.finishScenario("PASSED");
        }
    }

    private int count(String sql) throws Exception {
        return count(project, sql);
    }

    private static int count(Path root, String sql) throws Exception {
        try (DaltonDatabase database = DaltonDatabase.open(root);
             Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
