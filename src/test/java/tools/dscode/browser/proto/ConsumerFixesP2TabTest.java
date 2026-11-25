package tools.dscode.browser.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P2: the protocol-target warning is a pure string. No socket. */
class ConsumerFixesP2TabTest {
    @Test
    void targetWarningIsOnlyWhenTheTabsDiffer() {
        assertNull(CdpSession.targetWarning("same", "same"));
        assertNull(CdpSession.targetWarning("", "visible"));
        assertNull(CdpSession.targetWarning("proto", ""));
        assertNull(CdpSession.targetWarning(null, "visible"));
        assertNull(CdpSession.targetWarning("proto", null));
        assertEquals("protocol target proto differs from the visible tab visible",
                CdpSession.targetWarning("proto", "visible"));
    }
}
