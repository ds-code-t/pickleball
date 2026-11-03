package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.ControlBridgeEventPage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeEventRecorderTest {
    @Test
    void executedStepIsOnTheSameEventStreamAsHooks() {
        ControlBridgeEventRecorder recorder = new ControlBridgeEventRecorder();
        recorder.recordExecutedStep("scene-1", "Named", "INFO: harness step", "SUCCESS");
        ControlBridgeEventPage page = recorder.page(null, 0L, 20);
        assertEquals(1, page.events().size());
        assertEquals("INFO: harness step", page.events().getFirst().stepText());
        assertEquals("EXECUTE_STEP", page.events().getFirst().hook());
        assertTrue(page.events().getFirst().signature().contains("SUCCESS"));
    }
}
