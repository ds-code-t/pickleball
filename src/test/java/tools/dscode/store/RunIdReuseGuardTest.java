package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.Status;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A finished run id is not reused. Isolate must not turn a FAILED discover into PASSED. */
class RunIdReuseGuardTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        System.clearProperty("pkb_store_track");
        System.clearProperty("pkb_run_id");
        System.clearProperty("pkb_store_project");
        AgentCoordination.clearCurrent();
        StoreExecution.finishScenario("PASSED");
    }

    @Test
    void isolateOnAFinishedDiscoverRunDoesNotOverwriteTheFailure() throws Exception {
        AgentCoordination.Run discover = AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("discover-1", "agent-reuse", null, null, null, "first look", "discover"));
        AgentCoordination.finish(project, discover.runId(), "broke", "FAILED");
        String failed = Files.readString(discover.recordFile());
        assertTrue(failed.contains("\"status\" : \"FAILED\""), failed);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("discover-1", "agent-reuse", null, null, null, "isolate", "isolate"),
                java.time.Instant.now(),
                null,
                false));
        assertTrue(refused.getMessage().contains("already finished"), refused.getMessage());
        String kept = Files.readString(discover.recordFile());
        assertTrue(kept.contains("\"status\" : \"FAILED\""), kept);
        assertFalse(kept.contains("\"status\" : \"PASSED\""), kept);
        assertFalse(kept.contains("\"status\" : \"RUNNING\""), kept);

        System.setProperty("pkb_store_track", "true");
        System.setProperty("pkb_run_id", "discover-1");
        System.setProperty("pkb_store_project", project.toString());
        tools.dscode.engine.model.RunResult broken = PickleballEngine.run("""
                Feature: Discover
                  Scenario: One
                    * ASSERT: "a" equals "b"
                """, EngineOptions.of().project(project));
        assertEquals(Status.FAILED, broken.status(), broken.message());
        StoreExecution.finishScenario("FAILED");
        StoreHistory.Run stored = StoreHistory.find(project, "discover-1");
        assertEquals("FAILED", stored.status(), stored.toString());

        tools.dscode.engine.model.RunResult again = PickleballEngine.run("""
                Feature: Isolate
                  Scenario: One
                    * ASSERT: "1" equals "1"
                """, EngineOptions.of().project(project));
        assertEquals(Status.PASSED, again.status(), again.message());
        StoreExecution.finishScenario("PASSED");
        stored = StoreHistory.find(project, "discover-1");
        assertEquals("FAILED", stored.status(), stored.toString());
        assertTrue(stored.failed() > 0, stored.toString());
        kept = Files.readString(discover.recordFile());
        assertTrue(kept.contains("\"status\" : \"FAILED\""), kept);
    }

    @Test
    void aFinishedRunDoesNotStayTheNextInProcessId() {
        System.setProperty("pkb_agent_id", "agent-hold");
        try {
            AgentCoordination.Run run = AgentCoordination.openConsumerRun(project, new StringBuilder());
            assertEquals(run.runId(), System.getProperty("pkb_run_id"));
            AgentCoordination.finish(project, run.runId(), "done", "PASSED");
            assertNull(System.getProperty("pkb_run_id"));
            AgentCoordination.Run next = AgentCoordination.openConsumerRun(project, new StringBuilder());
            assertNotEquals(run.runId(), next.runId());
        } finally {
            System.clearProperty("pkb_agent_id");
            System.clearProperty("pkb_run_id");
            AgentCoordination.clearCurrent();
        }
    }
}
