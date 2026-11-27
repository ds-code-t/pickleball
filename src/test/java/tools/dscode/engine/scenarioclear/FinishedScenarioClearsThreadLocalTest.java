package tools.dscode.engine.scenarioclear;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.adapter.cucumber.CucumberAdapterRun;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A finished Cucumber scenario must not leave its state on the thread. */
class FinishedScenarioClearsThreadLocalTest {
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void finishedScenarioLeavesTheThreadLocalEmpty(@TempDir Path dir) throws Exception {
        Path feature = dir.resolve("clear.feature");
        Files.writeString(feature, """
                Feature: Clear
                  Scenario: One
                    * scenario state is live
                """, StandardCharsets.UTF_8);
        ScenarioStateProbe.liveDuringStep = false;
        String store = System.getProperty("pkb_store");
        CurrentScenarioState.currentScenarioState.remove();
        try {
            System.setProperty("pkb_store", "false");
            RunResult result = CucumberAdapterRun.run(feature, "tools.dscode.engine.scenarioclear");
            assertEquals(Status.PASSED, result.status(), result.toString());
            assertTrue(ScenarioStateProbe.liveDuringStep, "the step ran with no scenario state");
            assertNull(CurrentScenarioState.currentScenarioState.get());
        } finally {
            CurrentScenarioState.currentScenarioState.remove();
            if (store == null) System.clearProperty("pkb_store");
            else System.setProperty("pkb_store", store);
        }
    }
}
