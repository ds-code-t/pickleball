package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonExecutionRecordingTest {
    @TempDir
    Path project;

    @Test
    void trackingStaysOffUnlessAsked() {
        String track = System.getProperty("pkb_dalton_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dalton_project");
        try {
            System.clearProperty("pkb_dalton_track");
            System.clearProperty("pkb_run_id");
            DaltonExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertFalse(Files.exists(project.resolve(".pickleball/dalton/dalton.db")));
            System.setProperty("pkb_dalton_track", "false");
            System.setProperty("pkb_run_id", "still-off");
            DaltonExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertFalse(Files.exists(project.resolve(".pickleball/dalton/dalton.db")));
            assertFalse(DaltonExecution.shouldTrack());
        } finally {
            restore("pkb_dalton_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dalton_project", root);
            DaltonExecution.finishScenario("PASSED");
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
        DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        String expected = DaltonStepCatalog.definitionId("java", "the total is {int}", "src/test/java/demo/Steps.java", 3);
        String track = System.getProperty("pkb_dalton_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton_track", "true");
            System.setProperty("pkb_run_id", "sample-run");
            System.setProperty("pkb_dalton_project", project.toAbsolutePath().normalize().toString());
            DaltonExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3);
            assertNotNull(DaltonRunContext.current());
            assertEquals(project.toAbsolutePath().normalize(), DaltonRunContext.current().project());
            DaltonExecution.afterStep("PASSED");
            assertEquals(null, DaltonRunContext.current());
            DaltonExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 4", 4);
            DaltonExecution.afterStep("FAILED");
            DaltonExecution.finishUnopenedStep(project, "Pay", "file:" + project.resolve("features/pay.feature").toUri().getPath(), 9,
                    "the total is 10", 3, "PASSED");
            DaltonExecution.finishUnopenedStep(project, "Pay", "features/other.feature", 9,
                    "the total is 4", 4, "FAILED");
            List<DaltonExecution.StepRow> steps = DaltonExecution.steps(project, "sample-run");
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
            DaltonExecution.finishScenario("FAILED");
        } finally {
            restore("pkb_dalton_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dalton_project", root);
            DaltonExecution.finishScenario("PASSED");
        }
    }

    @Test
    void theNativeEngineRecordsTheSampleRun() throws Exception {
        String track = System.getProperty("pkb_dalton_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton_track", "true");
            System.setProperty("pkb_run_id", "engine-sample");
            System.setProperty("pkb_dalton_project", project.toAbsolutePath().normalize().toString());
            RunResult result = PickleballEngine.run("""
                    Feature: Dalton sample
                      Scenario: Pay Ada
                        * ASSERT: "10" equals "10"
                    """, EngineOptions.of().project(project));
            assertEquals(Status.PASSED, result.status(), result.message());
            List<DaltonExecution.StepRow> steps = DaltonExecution.steps(project, "engine-sample");
            assertEquals(1, steps.size(), steps.toString());
            assertEquals("ASSERT: \"10\" equals \"10\"", steps.get(0).stepText());
            assertEquals("PASSED", steps.get(0).status());
            assertTrue(steps.get(0).scenarioExecId().contains("memory:"), steps.get(0).scenarioExecId());
        } finally {
            restore("pkb_dalton_track", track);
            restore("pkb_run_id", run);
            restore("pkb_dalton_project", root);
            DaltonExecution.finishScenario("PASSED");
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
