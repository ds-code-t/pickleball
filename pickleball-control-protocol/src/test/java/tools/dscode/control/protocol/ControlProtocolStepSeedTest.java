package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlProtocolStepSeedTest {
    @Test
    void encodesAndDecodesStepSeedReferencesWithoutKeywords() {
        String reference = ControlProtocol.stepSeedReference("Given a user is ready");
        assertTrue(reference.startsWith(ControlProtocol.STEP_SEED_REFERENCE_PREFIX));
        assertEquals("a user is ready", ControlProtocol.stepSeedText(reference));
        assertEquals(reference, ControlProtocol.stepSeedReference("When a user is ready"));
    }
}