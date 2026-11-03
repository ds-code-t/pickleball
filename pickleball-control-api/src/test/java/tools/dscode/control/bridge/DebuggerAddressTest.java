package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.MutableCapabilities;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DebuggerAddressTest {
    @Test
    void prefersSeCdpThenChromeDebuggerAddress() {
        MutableCapabilities both = new MutableCapabilities();
        both.setCapability("browserName", "chrome");
        both.setCapability("se:cdp", "ws://127.0.0.1:9222/devtools/browser/abc");
        both.setCapability("goog:chromeOptions", Map.of("debuggerAddress", "127.0.0.1:9222"));
        assertEquals("ws://127.0.0.1:9222/devtools/browser/abc", DebuggerAddress.from(both));
        assertEquals("chrome", DebuggerAddress.browserType(both));

        MutableCapabilities address = new MutableCapabilities();
        address.setCapability("browserName", "chrome");
        address.setCapability("goog:chromeOptions", Map.of("debuggerAddress", "127.0.0.1:9333"));
        assertEquals("127.0.0.1:9333", DebuggerAddress.from(address));
    }

    @Test
    void edgeUsesEdgeOptionsAndMissingStaysNull() {
        MutableCapabilities edge = new MutableCapabilities();
        edge.setCapability("browserName", "msedge");
        edge.setCapability("ms:edgeOptions", Map.of("debuggerAddress", "127.0.0.1:9444"));
        assertEquals("127.0.0.1:9444", DebuggerAddress.from(edge));
        assertEquals("edge", DebuggerAddress.browserType(edge));
        assertNull(DebuggerAddress.from(new MutableCapabilities()));
        assertNull(DebuggerAddress.browserType(null));
    }
}
