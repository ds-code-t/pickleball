package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import tools.dscode.control.api.NativeDynamicSteps;
import tools.dscode.control.protocol.ControlBridgeCallResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeNativeStepTest {
    @Test
    void bridgeRunsAStepWhenNoScenarioLaneExists() {
        NativeDynamicSteps.useGlue(tools.dscode.control.api.DynamicControlNoScenarioTest.EchoGlue.class);
        try (ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                "native", 1L, List.of(), false, null
        )) {
            ControlBridgeCallResult result = coordinator.executeStep(null, "native control echo \"hi\"", "", 5);
            assertEquals("SUCCESS", result.status());
            assertTrue(result.valueText() != null && result.valueText().startsWith("hi"), result.valueText());
            assertTrue(result.valueText().contains("frame="), result.valueText());
        }
    }
}
