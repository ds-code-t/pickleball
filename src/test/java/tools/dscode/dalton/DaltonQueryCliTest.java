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
