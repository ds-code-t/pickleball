package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PowerShell splits an unquoted {@code -Dkey=value}. The opener must quote it. */
class OpenerQuoteTest {
    @Test
    void powershellQuotesIncludeScopeAndFallsBackToTargetClasses() {
        String ps = PickleballLocalStore.powershellOpenScript();
        assertTrue(ps.contains("\"-Dmdep.includeScope=test\""), ps);
        assertFalse(
                ps.contains("build-classpath -Dmdep.includeScope=test "),
                ps
        );
        assertTrue(ps.contains("target/test-classes"), ps);
        assertTrue(ps.contains("target/classes"), ps);
        assertTrue(ps.contains("tools.dscode.launcher.PickleballWorkbenchLauncher"), ps);
        assertTrue(ps.contains("-cp"), ps);
    }
}
