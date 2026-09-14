package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeStepResolutionTest {
    @Test
    void nullFieldsBecomeEmptyStrings() {
        ControlBridgeStepResolution resolution =
                new ControlBridgeStepResolution(null, null, null, null, null, null, null);
        assertEquals("", resolution.kind());
        assertEquals("", resolution.pattern());
        assertEquals("", resolution.className());
        assertEquals("", resolution.methodName());
        assertEquals("", resolution.sourcePath());
        assertEquals("", resolution.snippet());
        assertEquals("", resolution.detail());
    }

    @Test
    void unmatchedFactorySetsKindAndDetail() {
        ControlBridgeStepResolution resolution = ControlBridgeStepResolution.unmatched("no match");
        assertEquals(ControlBridgeStepResolution.UNMATCHED, resolution.kind());
        assertEquals("no match", resolution.detail());
        assertEquals("", resolution.className());
        assertTrue(ControlProtocol.WORKER_CAPABILITIES.contains("resolve_step"));
    }
}
