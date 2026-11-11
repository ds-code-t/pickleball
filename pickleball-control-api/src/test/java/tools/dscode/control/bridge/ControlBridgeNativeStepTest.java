package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import tools.dscode.control.api.NativeDynamicSteps;
import tools.dscode.control.protocol.ControlBridgeCallResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlBridgeNativeStepTest {
    @Test
    void bridgeRunsAStepWhenNoScenarioLaneExists() {
        NativeDynamicSteps.useGlue(tools.dscode.control.api.DynamicControlNoScenarioTest.EchoGlue.class);
        try (ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                "native", 1L, List.of(), false, null
        )) {
            ControlBridgeCallResult result = coordinator.executeStep(null, "native control echo \"hi\"", "", 5);
            assertEquals("SUCCESS", result.status());
            assertEquals("hi", result.valueText());
        }
    }
}
