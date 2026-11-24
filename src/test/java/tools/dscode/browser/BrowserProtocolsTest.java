package tools.dscode.browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.browser.BrowserProtocols.Backend.BIDI;
import static tools.dscode.browser.BrowserProtocols.Backend.CDP;
import static tools.dscode.browser.BrowserProtocols.Backend.CLASSIC;
import static tools.dscode.browser.BrowserProtocols.Backend.PLAYWRIGHT;

/** E1: fallback order with injected backend availability. */
class BrowserProtocolsTest {
    private static BrowserProtocols chrome(boolean playwrightAttached) {
        return new BrowserProtocols("chrome", "156", playwrightAttached)
                .live(PLAYWRIGHT, "playwright 1.65.1", "ws://x").live(BIDI, "ready", "ws://b").live(CDP, "1.3", "ws://c").live(CLASSIC, "selenium", null);
    }

    @Test
    void playwrightFirstOnlyWhenAttachedThenBidiThenCdpThenClassic() {
        assertEquals(List.of(BIDI, CDP, CLASSIC), chrome(false).order("tabs", "auto"));
        assertEquals(List.of(PLAYWRIGHT, BIDI, CDP, CLASSIC), chrome(true).order("tabs", "auto"));
        assertEquals(BIDI, chrome(false).resolve("events", null));
        BrowserProtocols cdpOnly = new BrowserProtocols("chrome", "156", false).live(CDP, "1.3", "ws://c");
        assertEquals(CDP, cdpOnly.resolve("events", "auto"));
    }

    @Test
    void firefoxNeverUsesCdp() {
        BrowserProtocols firefox = new BrowserProtocols("firefox", "150", false).live(BIDI, "ready", "ws://b").live(CDP, "x", "ws://c");
        assertTrue(!firefox.isLive(CDP));
        assertEquals(List.of(BIDI), firefox.order("events", "auto"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> firefox.resolve("logpoint", "cdp"));
        assertTrue(failure.getMessage().startsWith("error: unsupported: logpoint on firefox/cdp"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix: Firefox has no CDP"), failure.getMessage());
    }

    @Test
    void viaForcesOneBackendAndSaysWhenItCannot() {
        assertEquals(List.of(PLAYWRIGHT), chrome(false).order("net-route", "playwright"));
        assertEquals(List.of(CDP), chrome(false).order("net-route", "cdp"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> chrome(false).resolve("ws-route", "cdp"));
        assertTrue(failure.getMessage().contains("fix: use --via=playwright"), failure.getMessage());
    }

    @Test
    void unknownCommandMarksTheBackendAndFallsThrough() {
        BrowserProtocols protocols = chrome(false);
        List<String> tried = new ArrayList<>();
        BrowserProtocols.Served<String> served = protocols.serve("emulate", "auto", backend -> {
            tried.add(backend.id());
            if (backend == BIDI) throw new UnsupportedProtocolOperation("emulate", "bidi", "unknown command");
            return "ok";
        });
        assertEquals(CDP, served.via());
        assertEquals(List.of("bidi", "cdp"), tried);
        assertEquals(List.of(CDP), protocols.order("emulate", "auto"), "bidi is cached as unavailable for emulate");
        assertThrows(IllegalStateException.class, () -> protocols.serve("emulate", "bidi", backend -> "never"));
    }

    @Test
    void tableListsBackendsAndCapabilities() {
        List<String> table = chrome(false).table();
        assertEquals("browser=chrome 156", table.get(0));
        assertTrue(table.contains("backend.bidi=yes ready"), table.toString());
        assertTrue(table.stream().anyMatch(l -> l.startsWith("backend.playwright=yes playwright 1.65.1 (only with")), table.toString());
        assertTrue(table.contains("capability.logpoint=cdp"), table.toString());
        assertTrue(table.stream().anyMatch(l -> l.startsWith("capability.ws-route=via playwright only")), table.toString());
        assertEquals("chrome:playwright,bidi,cdp,classic", chrome(false).summary());
    }

    @Test
    void unknownCommandShapesAreRecognised() {
        assertTrue(UnsupportedProtocolOperation.unknownCommand("unknown command: emulation.setTouchOverride"));
        assertTrue(UnsupportedProtocolOperation.unknownCommand("'Foo.bar' wasn't found (-32601)"));
        assertTrue(!UnsupportedProtocolOperation.unknownCommand("invalid argument"));
    }
}
