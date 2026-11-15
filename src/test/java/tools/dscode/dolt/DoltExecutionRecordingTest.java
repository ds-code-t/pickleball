package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import tools.dscode.engine.adapter.CucumberAdapterRun;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltExecutionRecordingTest {
    @TempDir
    Path project;

    @Test
    void trackingStaysOffUnlessAsked() {
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        try {
            System.clearProperty("pkb_dolt_track");
            System.clearProperty("pkb_run_id");
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertFalse(Files.exists(DoltPaths.database(project)));
            System.setProperty("pkb_dolt_track", "false");
            System.setProperty("pkb_run_id", "still-off");
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertFalse(Files.exists(DoltPaths.database(project)));
            assertFalse(DoltExecution.shouldTrack());
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void aRunRecordsStepsAndLinksTheMatchingDefinition() throws Exception {
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
        String expected = DoltStepCatalog.definitionId("java", "the total is {int}", "src/test/java/demo/Steps.java", 3);
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt_track", "true");
            System.setProperty("pkb_run_id", "sample-run");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertNotNull(DoltRunContext.current());
            assertEquals(project.toAbsolutePath().normalize(), DoltRunContext.current().project());
            DoltExecution.afterStep("PASSED");
            assertEquals(null, DoltRunContext.current());
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 4", 4);
            DoltExecution.afterStep("FAILED");
            DoltExecution.finishUnopenedStep(project, "Pay", "file:" + project.resolve("features/pay.feature").toUri().getPath(), 9,
                    "the total is 10", 3, "PASSED");
            DoltExecution.finishUnopenedStep(project, "Pay", "features/other.feature", 9,
                    "the total is 4", 4, "FAILED");
            List<DoltExecution.StepRow> steps = DoltExecution.steps(project, "sample-run");
            assertEquals(2, steps.size(), steps.toString());
            assertEquals(1, steps.get(0).stepOrd());
            assertEquals("the total is 10", steps.get(0).stepText());
            assertEquals(expected, steps.get(0).definitionId());
            assertEquals("PASSED", steps.get(0).status());
            assertEquals(2, steps.get(1).stepOrd());
            assertEquals("FAILED", steps.get(1).status());
            assertEquals(expected, steps.get(1).definitionId());
            assertTrue(steps.get(0).stepExecId().contains("|1|"), steps.get(0).stepExecId());
            assertTrue(steps.get(0).scenarioExecId().startsWith("sample-run|features/pay.feature|2|"), steps.get(0).scenarioExecId());
            DoltExecution.finishScenario("FAILED");
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void theNativeEngineRecordsTheSampleRun() throws Exception {
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt_track", "true");
            System.setProperty("pkb_run_id", "engine-sample");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            RunResult result = PickleballEngine.run("""
                    Feature: Dolt sample
                      Scenario: Pay Ada
                        * ASSERT: "10" equals "10"
                    """, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, result.status(), result.message());
            List<DoltExecution.StepRow> steps = DoltExecution.steps(project, "engine-sample");
            assertEquals(1, steps.size(), steps.toString());
            assertEquals("ASSERT: \"10\" equals \"10\"", steps.get(0).stepText());
            assertEquals("PASSED", steps.get(0).status());
            assertTrue(steps.get(0).scenarioExecId().contains("memory:"), steps.get(0).scenarioExecId());
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void eachRunGetsItsOwnIdAndOutlineRowsStayDistinct() throws Exception {
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        try {
            System.setProperty("pkb_dolt_track", "true");
            System.clearProperty("pkb_run_id");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            List<String> firstProps = DoltRunWorker.runProperties(List.of(), null, project.toString());
            List<String> secondProps = DoltRunWorker.runProperties(List.of(), null, project.toString());
            String firstId = property(firstProps, "-Dpkb_run_id=");
            String secondId = property(secondProps, "-Dpkb_run_id=");
            assertNotEquals(firstId, secondId);
            assertFalse(firstId.contains("dolt-run"), firstId);
            assertTrue(firstId.matches("[0-9]{8}-[0-9]{6}Z-[0-9a-f]{8}"), firstId);
            assertTrue(DoltRunWorker.runProperties(List.of("-Dpkb_run_id=caller-run"), null, null)
                    .contains("-Dpkb_run_id=caller-run"));

            System.setProperty("pkb_run_id", firstId);
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            DoltExecution.afterStep("PASSED");
            DoltExecution.finishScenario("PASSED");
            System.clearProperty("pkb_run_id");
            System.setProperty("pkb_run_id", secondId);
            DoltExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 4", 4);
            DoltExecution.afterStep("FAILED");
            DoltExecution.finishScenario("FAILED");
            assertEquals(1, DoltExecution.steps(project, firstId).size());
            assertEquals("PASSED", DoltExecution.steps(project, firstId).get(0).status());
            assertEquals(1, DoltExecution.steps(project, secondId).size());
            assertEquals("FAILED", DoltExecution.steps(project, secondId).get(0).status());
            String timeline = DoltTrack.timeline(project, new DoltTrack.Filter(
                    null, null, null, null, null, null, null, null, 50, 0), true);
            assertTrue(timeline.contains(firstId), timeline);
            assertTrue(timeline.contains(secondId), timeline);

            System.setProperty("pkb_run_id", "outline-run");
            RunResult outline = PickleballEngine.run("""
                    Feature: Outline
                      Scenario Outline: Pay <who>
                        * ASSERT: "10" equals "10"
                        Examples:
                          | who |
                          | Ada |
                          | Bob |
                    """, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, outline.status(), outline.message());
            try (DoltDatabase database = DoltDatabase.open(project);
                 Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT examples_row FROM scenario_executions WHERE run_id = 'outline-run' ORDER BY examples_row")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertFalse(rows.next());
            }
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void configuredProjectReceivesNativeAndDirectRows() throws Exception {
        Path configured = Files.createTempDirectory("dolt-configured");
        Path other = Files.createTempDirectory("dolt-other");
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        String dolt = System.getProperty("pkb_dolt");
        try {
            System.setProperty("pkb_dolt", "true");
            System.setProperty("pkb_dolt_track", "true");
            System.setProperty("pkb_dolt_project", configured.toAbsolutePath().normalize().toString());
            System.setProperty("pkb_run_id", "configured-run");
            DoltExecution.beforeStep(other, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            DoltExecution.afterStep("PASSED");
            RunResult result = PickleballEngine.run("""
                    Feature: Dolt sample
                      Scenario: Pay Ada
                        * ASSERT: "10" equals "10"
                    """, EngineOptions.of().project(other));
            assertEquals(Status.PASSED, result.status(), result.message());
            assertFalse(DoltExecution.steps(configured, "configured-run").isEmpty());
            assertFalse(Files.exists(DoltPaths.database(other)));
            assertTrue(DoltPaths.ready(configured));
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            restore("pkb_dolt", dolt);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void cucumberEngineRecordsOutsideDoltRun() throws Exception {
        Path feature = project.resolve("features/pay.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Pay
                  Scenario: Total
                    * ASSERT: "10" equals "10"
                """, StandardCharsets.UTF_8);
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        String dolt = System.getProperty("pkb_dolt");
        String trace = System.getProperty("pkb_dolt_run_trace");
        try {
            System.setProperty("pkb_dolt", "true");
            System.setProperty("pkb_dolt_track", "true");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            System.clearProperty("pkb_run_id");
            System.clearProperty("pkb_dolt_run_trace");
            RunResult result = CucumberAdapterRun.run(feature, "tools.dscode.coredefinitions");
            assertEquals(Status.PASSED, result.status(), result.message());
            try (DoltDatabase database = DoltDatabase.open(project);
                 Statement statement = database.connection().createStatement()) {
                try (ResultSet scenarios = statement.executeQuery("SELECT COUNT(*) FROM scenario_executions")) {
                    assertTrue(scenarios.next());
                    assertTrue(scenarios.getInt(1) >= 1);
                }
                try (ResultSet steps = statement.executeQuery("SELECT COUNT(*) FROM step_executions")) {
                    assertTrue(steps.next());
                    assertTrue(steps.getInt(1) >= 1);
                }
            }
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            restore("pkb_dolt", dolt);
            restore("pkb_dolt_run_trace", trace);
            DoltExecution.finishScenario("PASSED");
        }
    }

    @Test
    void repeatedInProcessEngineRunsDoNotMerge() throws Exception {
        String track = System.getProperty("pkb_dolt_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dolt_project");
        String pass = """
                Feature: Pay
                  Scenario: Pay Ada
                    * ASSERT: "10" equals "10"
                """;
        String fail = """
                Feature: Pay
                  Scenario: Pay Ada
                    * ASSERT: "10" equals "11"
                """;
        try {
            System.setProperty("pkb_dolt_track", "true");
            System.clearProperty("pkb_run_id");
            System.setProperty("pkb_dolt_project", project.toAbsolutePath().normalize().toString());
            RunResult first = PickleballEngine.run(pass, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, first.status(), first.message());
            assertFalse(DoltExecution.activePresent());
            assertEquals(null, System.getProperty("pkb_run_id"));
            RunResult second = PickleballEngine.run(fail, EngineOptions.of().project(project));
            assertEquals(Status.FAILED, second.status(), second.message());
            assertFalse(DoltExecution.activePresent());
            assertEquals(null, System.getProperty("pkb_run_id"));
            RunResult third = PickleballEngine.run(pass, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, third.status(), third.message());
            assertFalse(DoltExecution.activePresent());
            assertEquals(null, System.getProperty("pkb_run_id"));

            List<String> runIds = new java.util.ArrayList<>();
            List<String> statuses = new java.util.ArrayList<>();
            List<String> keys = new java.util.ArrayList<>();
            try (DoltDatabase database = DoltDatabase.open(project);
                 Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT r.run_id, r.status, e.scenario_key
                         FROM runs r
                         JOIN scenario_executions e ON e.run_id = r.run_id
                         ORDER BY r.started_at, r.run_id, e.examples_row
                         """)) {
                while (rows.next()) {
                    runIds.add(rows.getString(1));
                    statuses.add(rows.getString(2));
                    keys.add(rows.getString(3));
                }
            }
            assertEquals(List.of("PASSED", "FAILED", "PASSED"), statuses, runIds.toString());
            assertEquals(3, runIds.stream().distinct().count(), runIds.toString());
            assertTrue(keys.get(0).startsWith("memory:"), keys.toString());
            assertEquals(keys.get(0), keys.get(2), keys.toString());
            assertEquals(1, DoltExecution.steps(project, runIds.get(0)).size());
            assertEquals(1, DoltExecution.steps(project, runIds.get(1)).size());
            assertEquals(1, DoltExecution.steps(project, runIds.get(2)).size());

            System.setProperty("pkb_run_id", "caller-x");
            RunResult fourth = PickleballEngine.run(pass, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, fourth.status(), fourth.message());
            assertEquals("caller-x", System.getProperty("pkb_run_id"));
            assertFalse(DoltExecution.activePresent());
            assertEquals(1, DoltExecution.steps(project, "caller-x").size());
            try (DoltDatabase database = DoltDatabase.open(project);
                 Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM scenario_executions WHERE run_id = 'caller-x'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (DoltDatabase database = DoltDatabase.open(project);
                 Statement statement = database.connection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM runs")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
        } finally {
            restore("pkb_dolt_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dolt_project", root);
            DoltExecution.finishScenario("PASSED");
        }
    }

    private static String property(List<String> properties, String prefix) {
        return properties.stream().filter(item -> item.startsWith(prefix)).findFirst().orElseThrow()
                .substring(prefix.length());
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
