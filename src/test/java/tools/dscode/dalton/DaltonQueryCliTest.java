package tools.dscode.dalton;

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

class DaltonQueryCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void missingDatabaseStaysMissing() {
        Capture empty = run("dalton", "query", "--json", "--project=" + project);
        assertEquals(0, empty.code, empty.err);
        assertTrue(empty.out.startsWith("{\"command\":\"query\",\"count\":0,\"total\":0,\"limit\":50,\"offset\":0,\"rows\":[]}"), empty.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));
        Capture bad = run("dalton", "query", "--from=yesterday", "--project=" + project);
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
        DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        String definition = DaltonStepCatalog.definitionId("java", "the total is {int}", "src/test/java/demo/Steps.java", 3);
        run("dalton", "history", "record", "sample-run", "--started=2020-01-01T00:00:00Z",
                "--status=PASSED", "--scenario=Pay", "--message=opened", "--link=Account#ada",
                "--project=" + project);
        String passed = DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 1,
                "the total is 10", 3, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 2,
                "the total is 4", 4, "FAILED", "2020-01-03T00:00:00Z", "2020-01-03T00:00:01Z"));
        Path entity = project.resolve("dalton-entities/Account/ada.entity");
        Files.createDirectories(entity.getParent());
        Files.write(entity, DaltonEntities.canonicalFile("Account", "ada", 1,
                "{\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}"));
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton", "true");
            System.setProperty("pkb_dalton_project", project.toString());
            DaltonRunContext.activate(project, passed);
            assertEquals("Ada", DaltonEntities.lookup("entity:Account#ada.name"));
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton");
            else System.setProperty("pkb_dalton", previous);
            if (previousProject == null) System.clearProperty("pkb_dalton_project");
            else System.setProperty("pkb_dalton_project", previousProject);
            DaltonRunContext.clear();
        }

        Capture byEntity = run("dalton", "query", "--entity=Account#ada", "--json", "--project=" + project);
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

        Capture failed = run("dalton", "query", "--status=FAILED", "--json", "--project=" + project);
        JsonNode failedRows = JSON.readTree(failed.out).path("rows");
        assertTrue(failedRows.size() >= 1, failed.out);
        for (JsonNode row : failedRows) assertEquals("FAILED", row.path("status").asText());

        Capture defined = run("dalton", "query", "--step-def=" + definition, "--json", "--project=" + project);
        assertTrue(defined.out.contains(definition), defined.out);
        Capture scenario = run("dalton", "query", "--scenario=features/pay.feature|2", "--json", "--project=" + project);
        assertTrue(JSON.readTree(scenario.out).path("total").asInt() >= 2, scenario.out);
        Capture page = run("dalton", "query", "--scenario=features/pay.feature|2", "--limit=1", "--offset=1", "--json",
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
        DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        int line;
        try (DaltonDatabase database = DaltonDatabase.open(project);
             java.sql.Statement statement = database.connection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT line FROM scenarios WHERE file_path = 'features/pay.feature'")) {
            assertTrue(rows.next(), "scenario was not stored");
            line = rows.getInt(1);
        }
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|" + line, "Total", null, null, 1,
                "the total is 10", line, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "other-run", "features/other.feature|2", "Other", null, null, 1,
                "something else", 2, "PASSED", "2020-01-03T00:00:00Z", "2020-01-03T00:00:01Z"));
        Capture tagged = run("dalton", "query", "--tag=pay", "--json", "--project=" + project);
        assertEquals(0, tagged.code, tagged.err);
        assertTrue(tagged.out.contains("sample-run"), tagged.out);
        assertFalse(tagged.out.contains("other-run"), tagged.out);
        Capture featureFilter = run("dalton", "query", "--feature=features/pay.feature", "--json", "--project=" + project);
        assertTrue(featureFilter.out.contains("sample-run"), featureFilter.out);
        assertFalse(featureFilter.out.contains("other-run"), featureFilter.out);
        Capture byRun = run("dalton", "query", "--run=other-run", "--json", "--project=" + project);
        assertTrue(byRun.out.contains("other-run"), byRun.out);
        assertFalse(byRun.out.contains("sample-run"), byRun.out);
        Capture text = run("dalton", "query", "--text=something else", "--json", "--project=" + project);
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
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        int payLine = scenarioLine("features/pay.feature");
        int otherLine = scenarioLine("features/other.feature");
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "run-a", "features/pay.feature|" + payLine, "Pay", null, null, 1,
                "pay one", payLine, "PASSED", "2020-01-01T00:00:00Z", "2020-01-01T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "run-a", "features/other.feature|" + otherLine, "Other", null, null, 1,
                "other one", otherLine, "PASSED", "2020-01-01T00:00:02Z", "2020-01-01T00:00:03Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "run-b", "features/pay.feature|" + payLine, "Pay", null, null, 1,
                "pay two", payLine, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "run-b", "features/other.feature|" + otherLine, "Other", null, null, 1,
                "other two", otherLine, "PASSED", "2020-01-02T00:00:02Z", "2020-01-02T00:00:03Z"));

        JsonNode feature = JSON.readTree(run("dalton", "query", "--feature=other.feature", "--json", "--project=" + project).out);
        assertEquals(2, feature.path("total").asInt(), feature.toString());
        assertEquals(2, feature.path("count").asInt(), feature.toString());
        assertScoped(feature.path("rows"), "features/other.feature", "features/pay.feature");

        JsonNode tagged = JSON.readTree(run("dalton", "query", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(2, tagged.path("total").asInt(), tagged.toString());
        assertEquals(2, tagged.path("count").asInt(), tagged.toString());
        assertScoped(tagged.path("rows"), "features/pay.feature", "features/other.feature");

        JsonNode featureTimeline = JSON.readTree(run("dalton", "timeline", "--feature=other.feature", "--json", "--project=" + project).out);
        assertEquals(4, featureTimeline.path("total").asInt(), featureTimeline.toString());
        assertEquals(4, featureTimeline.path("count").asInt(), featureTimeline.toString());
        assertNoScenario(featureTimeline.path("events"), "features/pay.feature");
        assertEquals(2, countKind(featureTimeline.path("events"), "run"));
        assertEquals(2, countPrefix(featureTimeline.path("events"), "features/other.feature"));

        JsonNode tagTimeline = JSON.readTree(run("dalton", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(4, tagTimeline.path("total").asInt(), tagTimeline.toString());
        assertEquals(4, tagTimeline.path("count").asInt(), tagTimeline.toString());
        assertNoScenario(tagTimeline.path("events"), "features/other.feature");
        assertEquals(2, countKind(tagTimeline.path("events"), "run"));
        assertEquals(2, countPrefix(tagTimeline.path("events"), "features/pay.feature"));
    }

    private int scenarioLine(String path) throws Exception {
        try (DaltonDatabase database = DaltonDatabase.open(project);
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
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
