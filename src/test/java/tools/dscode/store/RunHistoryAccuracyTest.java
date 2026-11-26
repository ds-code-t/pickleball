package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.DiagnosticQuery;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Store is the run-history source of truth. Query is a view of that store. */
class RunHistoryAccuracyTest {
    @TempDir
    Path project;

    @AfterEach
    void clearTracking() {
        tools.dscode.store.sql.SqlConnections.closeProject(project);
        restore("pkb_store_track", null);
        restore("pkb_run_id", null);
        restore("pkb_store_project", null);
        AgentCoordination.clearCurrent();
        tools.dscode.store.StoreExecution.finishScenario("PASSED");
    }

    @Test
    void aSkippedStepIsNotStoredAsPassed() {
        track("skip-run");
        RunResult result = PickleballEngine.run("""
                Feature: Skip
                  Scenario: One
                    * ASSERT: "a" equals "b"
                    * ASSERT: "1" equals "1"
                """, EngineOptions.of().project(project));
        assertEquals(Status.FAILED, result.status(), result.message());
        List<StoreExecution.StepRow> steps = rows("skip-run");
        assertTrue(steps.stream().anyMatch(step -> "FAILED".equals(step.status())), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "SKIPPED".equals(step.status())
                && step.stepText().contains("1")), steps.toString());
        assertFalse(steps.stream().anyMatch(step -> "PASSED".equals(step.status())
                && step.stepText().contains("1")), steps.toString());
        StoreHistory.Run run = history("skip-run");
        assertEquals("FAILED", run.status(), run.toString());
        assertTrue(run.failed() > 0, run.toString());
    }

    @Test
    void nestedRunScenarioStepsAreStoredAndFailTheRun() {
        track("nested-run");
        RunResult result = PickleballEngine.run("""
                Feature: Modular
                  Scenario: Caller
                    * RUN SCENARIO: Worker
                  Scenario: Worker
                    * ASSERT: "a" equals "b"
                """, EngineOptions.of().project(project).scenario("Caller"));
        assertEquals(Status.FAILED, result.status(), result.message());
        List<StoreExecution.StepRow> steps = rows("nested-run");
        assertTrue(steps.stream().anyMatch(step -> step.stepText().contains("Worker")), steps.toString());
        assertTrue(steps.stream().anyMatch(step -> "FAILED".equals(step.status())
                && step.stepText().contains("equals")), steps.toString());
        StoreHistory.Run run = history("nested-run");
        assertEquals("FAILED", run.status(), run.toString());
        assertTrue(run.failed() > 0, run.toString());
        assertNotEqualsPassed(run);

        DiagnosticQuery.Outcome querySteps = DiagnosticQuery.query(project, Map.of(
                "op", "query", "runId", "nested-run", "kind", "step", "limit", 20));
        assertEquals(0, querySteps.exit(), querySteps.json());
        assertTrue(querySteps.json().contains("FAILED"), querySteps.json());
        assertTrue(querySteps.json().contains("Worker") || querySteps.json().contains("equals"), querySteps.json());
        assertTrue(querySteps.json().contains("runstate.db"), querySteps.json());

        DiagnosticQuery.Outcome got = DiagnosticQuery.query(project, Map.of(
                "op", "get", "runId", "nested-run", "kind", "run"));
        assertEquals(0, got.exit(), got.json());
        assertTrue(got.json().contains("\"outcome\":\"FAILED\""), got.json());
        assertEquals("FAILED", run.status());
    }

    @Test
    void anAgentRunAppearsInTheCatalogAndQueryAgreesWithTheStore() throws Exception {
        AgentCoordination.Run agent = AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("agent-history-1", "agent-history", null, null, null, "history check", "discover"));
        String catalog = Files.readString(project.resolve("reports/diagnostic-runs/run-catalog.json"), StandardCharsets.UTF_8);
        assertTrue(catalog.contains("agent-history-1"), catalog);
        DiagnosticQuery.Outcome listed = DiagnosticQuery.query(project, Map.of("op", "runs", "limit", 20));
        assertEquals(0, listed.exit(), listed.json());
        assertTrue(listed.json().contains("agent-history-1"), listed.json());

        track(agent.runId());
        RunResult result = PickleballEngine.run("""
                Feature: Agent
                  Scenario: One
                    * ASSERT: "a" equals "b"
                """, EngineOptions.of().project(project));
        assertEquals(Status.FAILED, result.status(), result.message());
        StoreHistory.Run run = history(agent.runId());
        assertEquals("FAILED", run.status(), run.toString());
        catalog = Files.readString(project.resolve("reports/diagnostic-runs/run-catalog.json"), StandardCharsets.UTF_8);
        assertTrue(catalog.contains("agent-history-1"), catalog);
        assertTrue(catalog.contains("FAILED"), catalog);
        DiagnosticQuery.Outcome got = DiagnosticQuery.query(project, Map.of(
                "op", "get", "runId", agent.runId(), "kind", "run"));
        assertEquals(0, got.exit(), got.json());
        assertTrue(got.json().contains("\"outcome\":\"FAILED\""), got.json());
        assertTrue(got.json().contains("runstate.db"), got.json());
    }

    @Test
    void historyShowJsonContainsSnapshotHeadId() throws Exception {
        track("json-snap");
        RunResult result = PickleballEngine.run("""
                Feature: Snap
                  Scenario: One
                    * ASSERT: "a" equals "a"
                """, EngineOptions.of().project(project));
        assertEquals(Status.PASSED, result.status(), result.message());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(new String[] {
                "store", "history", "show", "json-snap", "--json", "--project=" + project
        }, new PrintStream(out), new PrintStream(err));
        String json = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + json);
        JsonNode node = new ObjectMapper().readTree(json);
        JsonNode head = node.path("items").path(0).path("snapshot").path("head_id");
        assertFalse(head.asText("").isBlank(), json);
        assertTrue(json.contains("\"snapshot\""), json);
        assertTrue(json.contains("\"head_id\""), json);
    }

    private void assertNotEqualsPassed(StoreHistory.Run run) {
        assertFalse("PASSED".equals(run.status()) && run.failed() == 0, run.toString());
    }

    private void track(String runId) {
        System.setProperty("pkb_store_track", "true");
        System.setProperty("pkb_run_id", runId);
        System.setProperty("pkb_store_project", project.toAbsolutePath().normalize().toString());
    }

    private List<StoreExecution.StepRow> rows(String runId) {
        try {
            return StoreExecution.steps(project, runId);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private StoreHistory.Run history(String runId) {
        try {
            StoreHistory.Run run = StoreHistory.find(project, runId);
            assertNotNull(run, runId);
            return run;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
