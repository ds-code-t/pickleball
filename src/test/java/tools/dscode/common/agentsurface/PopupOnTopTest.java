package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupOnTopTest {
    @Test
    void commandLinesMatchThePlatformLimit() {
        PopupOnTop.Attempt windows = PopupOnTop.plan("Windows 11", "", 42);
        assertTrue(windows.applied());
        assertTrue(String.join(" ", windows.command()).contains("SetWindowPos"));
        PopupOnTop.Attempt mac = PopupOnTop.plan("Mac OS X", "", 42);
        assertFalse(mac.applied());
        assertTrue(mac.reason().contains("macOS"));
        assertTrue(String.join(" ", mac.command()).contains("osascript"));
        PopupOnTop.Attempt wayland = PopupOnTop.plan("Linux", "wayland", 42);
        assertFalse(wayland.applied());
        assertTrue(wayland.reason().contains("Wayland"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err));
        try {
            PopupLog.warn(null, "ontop-test", "ontop-unavailable", wayland.reason());
            PopupLog.warn(null, "ontop-test", "ontop-unavailable", wayland.reason());
        } finally {
            System.setErr(previous);
        }
        String text = err.toString();
        assertEquals(1, count(text, "WARN: agent popup ontop-unavailable"));
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            int at = text.indexOf(token, from);
            if (at < 0) break;
            found++;
            from = at + token.length();
        }
        return found;
    }
}
