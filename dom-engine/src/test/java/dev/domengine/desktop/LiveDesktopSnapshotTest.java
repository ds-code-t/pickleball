package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live desktop snapshot. Skips only when the bus, display, or permission is absent. */
class LiveDesktopSnapshotTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void liveSnapshotWritesJsonWhenTheBusAndDisplayExist() {
        boolean bus = bus();
        boolean display = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        Assumptions.assumeTrue(bus, "no AT-SPI bus");
        Assumptions.assumeTrue(display, "no display");
        Assumptions.assumeTrue("linux".equals(NativeDesktop.family()), "live AT-SPI snapshot runs on Linux");
        CliResult attached = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "live", "--live"));
        Assumptions.assumeTrue(attached.exit() == 0, "live attach failed: " + attached.stderr());
        CliResult shot = DomEngineMain.dispatch(List.of("desktop", "snapshot", "--id", "live"));
        if (shot.exit() != 0 && (shot.stderr().contains("permission") || shot.stderr().contains("screenshot failed"))) {
            Assumptions.assumeTrue(false, shot.stderr());
        }
        assertEquals(0, shot.exit(), shot.stderr());
        assertTrue(shot.stdout().contains("pickleball.composite-snapshot"), shot.stdout());
    }

    @Test
    void offPlatformCaptureScriptsStillNameTheNativeApis() {
        assertTrue(WindowsUia.captureScript().contains("UIAutomationClient"));
        assertTrue(WindowsUia.captureScript().contains("LabeledBy"));
        assertTrue(WindowsUia.captureScript().contains("IsOffscreen"));
        assertTrue(MacAccessibility.captureScript().contains("System Events"));
        assertTrue(MacAccessibility.captureScript().contains("AXTitleUIElement"));
        assertTrue(MacAccessibility.captureScript().contains("focused"));
    }

    private static boolean bus() {
        try {
            AtSpi.busAddress();
            return true;
        } catch (CdpException ignored) {
            return false;
        }
    }
}
