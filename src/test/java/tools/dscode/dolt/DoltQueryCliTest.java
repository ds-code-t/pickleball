package tools.dscode.dolt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltQueryCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void missingDatabaseStaysMissing() {
        Capture empty = run("dolt", "query", "--json", "--project=" + project);
        assertEquals(0, empty.code, empty.err);
        assertTrue(empty.out.startsWith("{\"command\":\"query\",\"count\":0,\"total\":0,\"limit\":50,\"offset\":0,\"rows\":[]}"), empty.out);
        assertFalse(Files.exists(DoltPaths.database(project)));
        Capture bad = run("dolt", "query", "--from=yesterday", "--project=" + project);
        assertEquals(1, bad.code, bad.out);
        assertTrue(bad.err.contains("ISO-8601"), bad.err);
    }

    @Test
    void filtersAndJsonStayStable() throws Exception {
        Path java = project.resolve("src/test/java/demo/Steps.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package demo;
                public class Steps {
                    @When("the total is {int}")
                    public void total(int amount) {}
                }
                """, StandardCharsets.UTF_8);
        DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        String definition = DoltStepCatalog.definitionId("java", "the total is {int}", "src/test/java/demo/Steps.java", 3);
        run("dolt", "history", "record", "sample-run", "--started=2020-01-01T00:00:00Z",
                "--status=PASSED", "--scenario=Pay", "--message=opened", "--link=Account#ada",
                "--project=" + project);
        String passed = DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 1,
                "the total is 10", 3, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 2,
                "the total is 4", 4, "FAILED", "2020-01-03T00:00:00Z", "2020-01-03T00:00:01Z"));
        Path entity = project.resolve("dolt-entities/Account/ada.entity");
        Files.createDirectories(entity.getParent());
        Files.write(entity, DoltEntities.canonicalFile("Account", "ada", 1,
                "{\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}"));
        String previous = System.getProperty("pkb_dolt");
        String previousProject = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt", "true");
            System.setProperty("pkb_dolt_project", project.toString());
            DoltRunContext.activate(project, passed);
            assertEquals("Ada", DoltEntities.lookup("entity:Account#ada.name"));
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt");
            else System.setProperty("pkb_dolt", previous);
            if (previousProject == null) System.clearProperty("pkb_dolt_project");
            else System.setProperty("pkb_dolt_project", previousProject);
            DoltRunContext.clear();
        }

        Capture byEntity = run("dolt", "query", "--entity=Account#ada", "--json", "--project=" + project);
        assertEquals(0, byEntity.code, byEntity.err);
        assertTrue(byEntity.out.indexOf("\"command\"") < byEntity.out.indexOf("\"count\""), byEntity.out);
        assertTrue(byEntity.out.indexOf("\"count\"") < byEntity.out.indexOf("\"total\""), byEntity.out);
        assertTrue(byEntity.out.indexOf("\"rows\"") < byEntity.out.indexOf("\"at\""), byEntity.out);
        assertTrue(byEntity.out.indexOf("\"at\"") < byEntity.out.indexOf("\"kind\""), byEntity.out);
        assertTrue(byEntity.out.indexOf("\"kind\"") < byEntity.out.indexOf("\"runId\""), byEntity.out);
        JsonNode rows = JSON.readTree(byEntity.out).path("rows");
        assertTrue(rows.size() >= 2, byEntity.out);
        assertTrue(rows.toString().contains("\"valueRef\":\"<entity:Account#ada.name>\""), rows.toString());
        assertFalse(rows.toString().contains("\"valueRef\":\"Ada\""), rows.toString());
        assertTrue(rows.toString().contains(definition), rows.toString());

        Capture failed = run("dolt", "query", "--status=FAILED", "--json", "--project=" + project);
        JsonNode failedRows = JSON.readTree(failed.out).path("rows");
        assertTrue(failedRows.size() >= 1, failed.out);
        for (JsonNode row : failedRows) assertEquals("FAILED", row.path("status").asText());

        Capture defined = run("dolt", "query", "--step-def=" + definition, "--json", "--project=" + project);
        assertTrue(defined.out.contains(definition), defined.out);
        Capture scenario = run("dolt", "query", "--scenario=features/pay.feature|2", "--json", "--project=" + project);
        assertTrue(JSON.readTree(scenario.out).path("total").asInt() >= 2, scenario.out);
        Capture page = run("dolt", "query", "--scenario=features/pay.feature|2", "--limit=1", "--offset=1", "--json",
                "--project=" + project);
        JsonNode paged = JSON.readTree(page.out);
        assertEquals(1, paged.path("count").asInt());
        assertTrue(paged.path("total").asInt() > 1, page.out);
    }

    @Test
    void tagFeatureRunAndTextFilters() throws Exception {
        Path feature = project.resolve("features/pay.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                @pay
                Feature: Pay
                  Scenario: Total
                    * the total is 10
                """, StandardCharsets.UTF_8);
        DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        int line;
        try (DoltDatabase database = DoltDatabase.open(project);
             java.sql.Statement statement = database.connection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT line FROM scenarios WHERE file_path = 'features/pay.feature'")) {
            assertTrue(rows.next(), "scenario was not stored");
            line = rows.getInt(1);
        }
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "sample-run", "features/pay.feature|" + line, "Total", null, null, 1,
                "the total is 10", line, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "other-run", "features/other.feature|2", "Other", null, null, 1,
                "something else", 2, "PASSED", "2020-01-03T00:00:00Z", "2020-01-03T00:00:01Z"));
        Capture tagged = run("dolt", "query", "--tag=pay", "--json", "--project=" + project);
        assertEquals(0, tagged.code, tagged.err);
        assertTrue(tagged.out.contains("sample-run"), tagged.out);
        assertFalse(tagged.out.contains("other-run"), tagged.out);
        Capture featureFilter = run("dolt", "query", "--feature=features/pay.feature", "--json", "--project=" + project);
        assertTrue(featureFilter.out.contains("sample-run"), featureFilter.out);
        assertFalse(featureFilter.out.contains("other-run"), featureFilter.out);
        Capture byRun = run("dolt", "query", "--run=other-run", "--json", "--project=" + project);
        assertTrue(byRun.out.contains("other-run"), byRun.out);
        assertFalse(byRun.out.contains("sample-run"), byRun.out);
        Capture text = run("dolt", "query", "--text=something else", "--json", "--project=" + project);
        assertTrue(text.out.contains("something else"), text.out);
        assertFalse(text.out.contains("the total is 10"), text.out);
    }

    @Test
    void featureAndTagSelectScenariosNotWholeRuns() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  @fast
                  Scenario: Pay
                    * the total is 10
                """);
        Files.writeString(project.resolve("features/other.feature"), """
                Feature: Other
                  Scenario: Other
                    * something else
                """);
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);
        int payLine = scenarioLine("features/pay.feature");
        int otherLine = scenarioLine("features/other.feature");
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "run-a", "features/pay.feature|" + payLine, "Pay", null, null, 1,
                "pay one", payLine, "PASSED", "2020-01-01T00:00:00Z", "2020-01-01T00:00:01Z"));
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "run-a", "features/other.feature|" + otherLine, "Other", null, null, 1,
                "other one", otherLine, "PASSED", "2020-01-01T00:00:02Z", "2020-01-01T00:00:03Z"));
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "run-b", "features/pay.feature|" + payLine, "Pay", null, null, 1,
                "pay two", payLine, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                "run-b", "features/other.feature|" + otherLine, "Other", null, null, 1,
                "other two", otherLine, "PASSED", "2020-01-02T00:00:02Z", "2020-01-02T00:00:03Z"));

        JsonNode feature = JSON.readTree(run("dolt", "query", "--feature=other.feature", "--json", "--project=" + project).out);
        assertEquals(2, feature.path("total").asInt(), feature.toString());
        assertEquals(2, feature.path("count").asInt(), feature.toString());
        assertScoped(feature.path("rows"), "features/other.feature", "features/pay.feature");

        JsonNode tagged = JSON.readTree(run("dolt", "query", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(2, tagged.path("total").asInt(), tagged.toString());
        assertEquals(2, tagged.path("count").asInt(), tagged.toString());
        assertScoped(tagged.path("rows"), "features/pay.feature", "features/other.feature");

        JsonNode featureTimeline = JSON.readTree(run("dolt", "timeline", "--feature=other.feature", "--json", "--project=" + project).out);
        assertEquals(4, featureTimeline.path("total").asInt(), featureTimeline.toString());
        assertEquals(4, featureTimeline.path("count").asInt(), featureTimeline.toString());
        assertNoScenario(featureTimeline.path("events"), "features/pay.feature");
        assertEquals(2, countKind(featureTimeline.path("events"), "run"));
        assertEquals(2, countPrefix(featureTimeline.path("events"), "features/other.feature"));

        JsonNode tagTimeline = JSON.readTree(run("dolt", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(4, tagTimeline.path("total").asInt(), tagTimeline.toString());
        assertEquals(4, tagTimeline.path("count").asInt(), tagTimeline.toString());
        assertNoScenario(tagTimeline.path("events"), "features/other.feature");
        assertEquals(2, countKind(tagTimeline.path("events"), "run"));
        assertEquals(2, countPrefix(tagTimeline.path("events"), "features/pay.feature"));
    }

    @Test
    void tagSelectsTheTaggedScenarioNotItsFileSibling() throws Exception {
        Path feature = writePaySiblings();
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);
        int pay = lineContaining(feature, "Scenario: Pay");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + pay, "Pay", null, "pay step", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:02Z");

        JsonNode tagged = JSON.readTree(run("dolt", "query", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(1, tagged.path("total").asInt(), tagged.toString());
        assertEquals("features/pay.feature|" + pay, tagged.path("rows").get(0).path("scenarioKey").asText());
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());
    }

    @Test
    void tagSelectsOutlineExampleRowsNotTheUntaggedSibling() throws Exception {
        Path feature = writeOutlineAndRefund();
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);
        int examples = lineContaining(feature, "Examples:");
        int ada = lineContaining(feature, "| Ada |");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + examples, "Pay Ada", 1, "outline header", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + ada, "Pay Bob", 2, "outline row", "2020-01-01T00:00:02Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:04Z");

        JsonNode tagged = JSON.readTree(run("dolt", "query", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(2, tagged.path("total").asInt(), tagged.toString());
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + examples), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + ada), tagged.toString());
    }

    @Test
    void tagSelectsOnlyTheTaggedExamplesBlock() throws Exception {
        Path feature = writeTaggedExamples();
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);
        int examples = lineContaining(feature, "Examples:");
        int ada = lineContaining(feature, "| Ada |");
        int bob = lineContaining(feature, "| Bob |");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + examples, "Pay header", 1, "outline header", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + ada, "Pay Ada", 2, "ada row", "2020-01-01T00:00:02Z");
        record("run-a", "features/pay.feature|" + bob, "Pay Bob", 3, "bob row", "2020-01-01T00:00:04Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:06Z");

        JsonNode tagged = JSON.readTree(run("dolt", "query", "--tag=@extag", "--json", "--project=" + project).out);
        assertEquals(2, tagged.path("total").asInt(), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + examples), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + ada), tagged.toString());
        assertFalse(tagged.toString().contains("features/pay.feature|" + bob), tagged.toString());
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());

        JsonNode outline = JSON.readTree(run("dolt", "query", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(3, outline.path("total").asInt(), outline.toString());
        assertTrue(outline.toString().contains("features/pay.feature|" + bob), outline.toString());
        assertFalse(outline.toString().contains("features/pay.feature|" + refund), outline.toString());
    }

    @Test
    void blankScenarioNameDoesNotLetALogRowMatchByKey() throws Exception {
        Path feature = writePaySiblings();
        assertEquals(0, run("dolt", "hydrate", "--project=" + project).code);
        int pay = lineContaining(feature, "Scenario: Pay");
        String dolt = System.getProperty("pkb_dolt");
        String track = System.getProperty("pkb_dolt_track");
        String runId = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt", "true");
            System.setProperty("pkb_dolt_track", "true");
            System.setProperty("pkb_run_id", "blank-name");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            DoltExecution.beforeStep(project, " ", "features/pay.feature", pay, "the total is 10", pay);
            DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                    "null-name", "features/pay.feature|" + pay, null, null, null, 1,
                    "the total is 10", pay, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
            try (DoltDatabase database = DoltDatabase.open(project);
                 java.sql.Statement statement = database.connection().createStatement()) {
                assertEquals("", scalar(statement, "SELECT scenario FROM runs WHERE run_id = 'blank-name'"));
                assertEquals("", scalar(statement, "SELECT scenario FROM runs WHERE run_id = 'null-name'"));
                statement.executeUpdate(
                        "INSERT INTO run_logs(run_id, logged_at, entity, message) VALUES ('blank-name', '2020-01-01T00:00:01Z', 'run', 'opened')");
                statement.executeUpdate(
                        "INSERT INTO log_entity_links(log_id, entity_type, entity_id) VALUES (last_insert_rowid(), 'Account', 'ada')");
            }
            JsonNode open = JSON.readTree(run("dolt", "timeline", "--json", "--project=" + project).out);
            assertTrue(countKind(open.path("events"), "log") >= 1, open.toString());

            JsonNode featureRows = JSON.readTree(run("dolt", "query", "--feature=features/pay.feature", "--json", "--project=" + project).out);
            assertEquals(0, countKind(featureRows.path("rows"), "log"), featureRows.toString());
            assertTrue(countKind(featureRows.path("rows"), "step") >= 1, featureRows.toString());

            JsonNode tagged = JSON.readTree(run("dolt", "query", "--tag=@fast", "--json", "--project=" + project).out);
            assertEquals(0, countKind(tagged.path("rows"), "log"), tagged.toString());

            JsonNode timeline = JSON.readTree(run("dolt", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
            assertEquals(0, countKind(timeline.path("events"), "log"), timeline.toString());
            assertTrue(countKind(timeline.path("events"), "run") >= 1, timeline.toString());
            assertFalse(timeline.toString().contains("\"scenarioKey\":\"features/pay.feature|" + pay + "\",\"stepExecId\":\"\",\"stepText\":\"\""),
                    timeline.toString());
        } finally {
            restore("pkb_dolt", dolt);
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", runId);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }


    private Path writePaySiblings() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario: Pay
                    * the total is 10
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private Path writeOutlineAndRefund() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario Outline: Pay <who>
                    * the total is 10
                    Examples:
                      | who |
                      | Ada |
                      | Bob |
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private Path writeTaggedExamples() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario Outline: Pay <who>
                    * the total is 10
                    @extag
                    Examples:
                      | who |
                      | Ada |
                    Examples:
                      | who |
                      | Bob |
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private static int lineContaining(Path feature, String text) throws Exception {
        java.util.List<String> lines = Files.readAllLines(feature);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(text)) return index + 1;
        }
        throw new AssertionError(text + " not in " + feature);
    }

    private void record(String runId, String key, String name, Integer examplesRow, String text, String at) throws Exception {
        DoltExecution.recordStep(project, new DoltExecution.StepDraft(
                runId, key, name, null, examplesRow, 1, text, null, "PASSED", at, at));
    }

    private int scenarioLine(String path) throws Exception {
        try (DoltDatabase database = DoltDatabase.open(project);
             java.sql.Statement statement = database.connection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT line FROM scenarios WHERE file_path = '" + path + "'")) {
            assertTrue(rows.next(), path);
            return rows.getInt(1);
        }
    }

    private static void assertScoped(JsonNode rows, String keep, String drop) {
        assertEquals(2, rows.size(), rows.toString());
        for (JsonNode row : rows) {
            String key = row.path("scenarioKey").asText();
            assertTrue(key.startsWith(keep), rows.toString());
            assertFalse(key.startsWith(drop), rows.toString());
        }
    }

    private static void assertNoScenario(JsonNode events, String prefix) {
        for (JsonNode event : events) {
            String key = event.path("scenarioKey").asText();
            assertFalse(key.startsWith(prefix), events.toString());
            if ("step".equals(event.path("kind").asText()) || "usage".equals(event.path("kind").asText())
                    || "log".equals(event.path("kind").asText())) {
                assertFalse(key.startsWith("features/") && !key.contains("|"), events.toString());
            }
        }
    }

    private static String scalar(java.sql.Statement statement, String sql) throws Exception {
        try (java.sql.ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }


    private static int countKind(JsonNode events, String kind) {
        int count = 0;
        for (JsonNode event : events) if (kind.equals(event.path("kind").asText())) count++;
        return count;
    }

    private static int countPrefix(JsonNode events, String prefix) {
        int count = 0;
        for (JsonNode event : events) if (event.path("scenarioKey").asText().startsWith(prefix)) count++;
        return count;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
