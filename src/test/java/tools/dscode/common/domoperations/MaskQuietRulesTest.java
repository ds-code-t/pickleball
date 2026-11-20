package tools.dscode.common.domoperations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaskQuietRulesTest {
    @Test
    void aStuckCoverFailsAndAMissDoesNot() {
        assertThrows(IllegalStateException.class, () -> MaskQuiet.failIfStuck(new MaskQuiet.Wait(50, false)));
        assertDoesNotThrow(() -> MaskQuiet.failIfStuck(new MaskQuiet.Wait(0, false)));
        assertDoesNotThrow(() -> MaskQuiet.failIfStuck(new MaskQuiet.Wait(40, true)));
        assertDoesNotThrow(() -> MaskQuiet.failIfStuck(null));
    }
}
