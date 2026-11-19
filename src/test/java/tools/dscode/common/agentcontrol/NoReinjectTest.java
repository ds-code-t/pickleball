package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoReinjectTest {
    @Test
    void secondCallOmitsTheRuntimeBytes() {
        PageSurfaces.resetRuntimeForTest();
        String first = PageSurfaces.isolatedFunction(7, true);
        String second = PageSurfaces.isolatedFunction(7, true);
        assertTrue(first.contains("raisingLayer"), first);
        assertFalse(second.contains("raisingLayer"), second);
        assertTrue(second.contains("__pkbUi"), second);
        PageSurfaces.resetRuntimeForTest();
        String again = PageSurfaces.isolatedFunction(7, true);
        assertTrue(again.contains("raisingLayer"), again);
    }
}
