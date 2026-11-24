package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Linux desktop control needs X11 or Xvfb; a Wayland-only session is refused with error and fix lines. */
class WaylandDetectionTest {

    @Test
    void waylandOnlySessionsAreDetected() {
        assertTrue(NativeDesktop.waylandOnly(Map.of("WAYLAND_DISPLAY", "wayland-0")));
        assertTrue(NativeDesktop.waylandOnly(Map.of("XDG_SESSION_TYPE", "wayland")));
        assertNotNull(NativeDesktop.waylandRefusal("linux", Map.of("WAYLAND_DISPLAY", "wayland-0")));
    }

    @Test
    void xwaylandAndX11AreAllowed() {
        assertFalse(NativeDesktop.waylandOnly(Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0", "XDG_SESSION_TYPE", "wayland")),
                "Xwayland: DISPLAY set means xdotool and AT-SPI can run");
        assertFalse(NativeDesktop.waylandOnly(Map.of("DISPLAY", ":99", "XDG_SESSION_TYPE", "x11")));
        assertFalse(NativeDesktop.waylandOnly(Map.of()));
        assertNull(NativeDesktop.waylandRefusal("linux", Map.of("DISPLAY", ":0")));
        assertNull(NativeDesktop.waylandRefusal("windows", Map.of("WAYLAND_DISPLAY", "wayland-0")),
                "the Wayland rule is Linux only");
    }

    @Test
    void liveAttachRefusesAWaylandOnlySessionWithErrorAndFix() {
        Assumptions.assumeTrue("linux".equals(NativeDesktop.family()), "the attach path refusal is Linux only");
        CdpException refused = assertThrows(CdpException.class,
                () -> DesktopTree.attachLive("wayland-test", "", Map.of("WAYLAND_DISPLAY", "wayland-0", "XDG_SESSION_TYPE", "wayland")));
        assertTrue(refused.getMessage().startsWith("error: Wayland session detected; desktop control needs X11 or Xvfb (set DISPLAY)"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("\nfix: "), refused.getMessage());
        assertNull(DesktopTree.open("wayland-test"), "a refused attach leaves no session");
    }
}
