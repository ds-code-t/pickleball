package tools.dscode.control.bridge;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.dscode.common.control.ControlRuntime;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.StepResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The held external pause runs on AFTER_STEP of the step that opened the bridge.
 * Steps executed while that pause is held must be recorded after that step.
 * Cucumber emits the finish event before AFTER_STEP. The native trace must too.
 */
class PausedRuntimeStepOrderTest {
    @Test
    @Timeout(45)
    void nativeRecordsTheHeldStepBeforeStepsRunDuringThePause() throws Exception {
        ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                "paused-order", 11L, List.of(), false, null
        );
        OrderGlue.coordinator = coordinator;
        OrderGlue.nest = new AtomicReference<>();
        OrderGlue.asyncFailure = new AtomicReference<>();
        ControlRuntime.addObserver(coordinator);
        try {
            String feature = """
                    Feature: Paused order
                      Scenario: Paused runtime records the opening step first
                        * BEGIN PAUSED STEP ORDER
                        * PAUSED STEP ORDER AFTER
                    """;
            RunResult result = PickleballEngine.run(feature, EngineOptions.of(OrderGlue.class));
            List<String> texts = result.scenarios().getFirst().steps().stream().map(StepResult::text).toList();
            int begin = indexOf(texts, "BEGIN PAUSED STEP ORDER");
            int inner = indexOf(texts, "PAUSED STEP ORDER INNER");
            int after = indexOf(texts, "PAUSED STEP ORDER AFTER");
            ControlBridgeCallResult nest = OrderGlue.nest.get();
            assertTrue(
                    begin >= 0 && inner > begin && after > inner,
                    "step order " + texts
                            + " nest=" + (nest == null ? "none" : nest.status() + " " + nest.error())
                            + " async=" + OrderGlue.asyncFailure.get()
            );
        } finally {
            ControlRuntime.removeObserver(coordinator);
            coordinator.close();
            OrderGlue.coordinator = null;
        }
    }

    private static int indexOf(List<String> texts, String needle) {
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            if (text != null && text.contains(needle)) return index;
        }
        return -1;
    }

    public static class OrderGlue {
        static ControlBridgeCoordinator coordinator;
        static AtomicReference<ControlBridgeCallResult> nest;
        static AtomicReference<Throwable> asyncFailure;

        @Given("^BEGIN PAUSED STEP ORDER$")
        public void begin() throws Exception {
            CurrentScenarioState state = GlobalState.getCurrentScenarioState();
            String scenarioId = state.id.toString();
            coordinator.adoptRunningScenario();
            Thread worker = new Thread(() -> holdPauseAndRunInner(scenarioId), "paused-step-order");
            worker.setDaemon(true);
            worker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                boolean requested = coordinator.scenarios().stream().anyMatch(active ->
                        scenarioId.equals(active.scenarioId()) && active.pauseRequested());
                if (requested) return;
                Thread.sleep(20);
            }
            throw new AssertionError("Pause was not requested before BEGIN returned for " + scenarioId);
        }

        private static void holdPauseAndRunInner(String scenarioId) {
            try {
                ControlBridgeCallResult paused = coordinator.requestPause(scenarioId, 15, 20);
                if (paused == null || !"SUCCESS".equals(paused.status())) {
                    asyncFailure.set(new AssertionError("pause status " + (paused == null ? "null" : paused.status())));
                    coordinator.resume(scenarioId);
                    return;
                }
                nest.set(coordinator.executeStep(scenarioId, "PAUSED STEP ORDER NEST", "", 10));
                coordinator.resume(scenarioId);
            } catch (Throwable failure) {
                asyncFailure.set(failure);
                try {
                    coordinator.resume(scenarioId);
                } catch (Throwable ignored) {
                    // The scenario thread may already have left the pause.
                }
            }
        }

        @Given("^PAUSED STEP ORDER NEST$")
        public void nest() {
            CurrentScenarioState state = GlobalState.getCurrentScenarioState();
            StepExtension inner = new StepExtension("* ", "PAUSED STEP ORDER INNER");
            inner.setStepParsingMap(state.getParsingMap());
            state.runStep(inner);
        }

        @Given("^PAUSED STEP ORDER INNER$")
        public void inner() {
        }

        @Given("^PAUSED STEP ORDER AFTER$")
        public void after() {
        }
    }
}
