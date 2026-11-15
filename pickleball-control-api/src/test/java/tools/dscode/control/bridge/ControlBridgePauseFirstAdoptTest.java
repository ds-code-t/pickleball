package tools.dscode.control.bridge;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.control.protocol.ControlBridgeScenarioStatus;
import tools.dscode.registry.GlobalRegistry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgePauseFirstAdoptTest {
    @Test
    void publishingARunningScenarioStillArmsPauseFirst() {
        withScenario(() -> {
            try (ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                    "pause-first", 7L, List.of(), true, null
            )) {
                coordinator.adoptRunningScenario();
                List<ControlBridgeScenarioStatus> scenarios = coordinator.scenarios();
                assertEquals(1, scenarios.size());
                assertTrue(scenarios.getFirst().pauseRequested());
                assertFalse(scenarios.getFirst().paused());
            }
        });
    }

    @Test
    void publishingARunningScenarioDoesNotPauseUnlessPauseFirst() {
        withScenario(() -> {
            try (ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                    "external", 8L, List.of(), false, null
            )) {
                coordinator.adoptRunningScenario();
                List<ControlBridgeScenarioStatus> scenarios = coordinator.scenarios();
                assertEquals(1, scenarios.size());
                assertFalse(scenarios.getFirst().pauseRequested());
            }
        });
    }

    private static void withScenario(Runnable body) {
        CurrentScenarioState previous = CurrentScenarioState.currentScenarioState.get();
        ConcurrentHashMap<String, Object> previousLocal = GlobalRegistry.LOCAL.get();
        if (MappingProcessor.getRunMap() == null) MappingProcessor.resetCommonMaps();
        CurrentScenarioState.currentScenarioState.set(new CurrentScenarioState("probe", "feature"));
        try {
            body.run();
        } finally {
            if (previous == null) {
                CurrentScenarioState.currentScenarioState.remove();
            } else {
                CurrentScenarioState.currentScenarioState.set(previous);
            }
            if (previousLocal == null) {
                GlobalRegistry.LOCAL.remove();
            } else {
                GlobalRegistry.LOCAL.set(previousLocal);
            }
        }
    }
}
