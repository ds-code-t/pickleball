package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D12: Playwright selection in ProtocolSelector, its quiet fallbacks, and the no-endpoint error. */
class ProtocolSelectorPlaywrightTest {
    @TempDir
    Path temp;

    private final List<String> attached = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProtocolSelector.resetForTests();
        System.setProperty(BrowserLog.DIR_KEY, temp.toString());
        System.setProperty(BrowserLog.FALLBACK_KEY, "warn");
        System.setProperty(BrowserLog.KEY, "selector,fallback");
    }

    @AfterEach
    void clear() {
        System.clearProperty(BrowserLog.DIR_KEY);
        System.clearProperty(BrowserLog.FALLBACK_KEY);
        System.clearProperty(BrowserLog.KEY);
        System.clearProperty(ProtocolSelector.PROTOCOL_PROPERTY);
        System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        ProtocolSelector.resetForTests();
    }

    private BrowserFacade fake() {
        return new PlaywrightAdapter(new FakeSession());
    }

    private void endpoints(String cdp) {
        ProtocolSelector.useEndpointsForTests(driver -> cdp == null ? null : URI.create(cdp));
    }

    @Test
    void playwrightPreferenceAttachesOverTheDriversCdpEndpoint() {
        endpoints("ws://127.0.0.1:9/devtools/browser/abc");
        BrowserFacade made = fake();
        ProtocolSelector.useConnectorForTests((endpoint, url) -> {
            attached.add(endpoint + " " + url);
            return made;
        });
        assertSame(made, ProtocolSelector.forDriver(named("chrome", "chrome", "https://app.test/"), ProtocolSelector.Preference.PLAYWRIGHT));
        assertEquals(List.of("ws://127.0.0.1:9/devtools/browser/abc https://app.test/"), attached);
        assertEquals("playwright", made.protocol());
    }

    @Test
    void pkbProtocolSettingSelectsPlaywrightAndPkbCdpEndpointWins() {
        endpoints("ws://127.0.0.1:9/devtools/browser/abc");
        System.setProperty(ProtocolSelector.PROTOCOL_PROPERTY, "Playwright");
        System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, "http://127.0.0.1:9333");
        ProtocolSelector.useConnectorForTests((endpoint, url) -> {
            attached.add(endpoint);
            return fake();
        });
        assertInstanceOf(PlaywrightAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null)));
        assertInstanceOf(PlaywrightAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null), ProtocolSelector.Preference.DIRECT));
        assertEquals(List.of("http://127.0.0.1:9333", "http://127.0.0.1:9333"), attached);
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null), ProtocolSelector.Preference.LIBRARY),
                "LIBRARY stays Selenium");
    }

    @Test
    void withoutTheSettingThePlaywrightConnectorIsNeverCalled() {
        endpoints(null);
        ProtocolSelector.useConnectorForTests((endpoint, url) -> {
            throw new AssertionError("Playwright must not be selected by default");
        });
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null)));
    }

    @Test
    void attachFailureNoEndpointAndFirefoxFallBackQuietlyOncePerReason() {
        System.setProperty(ProtocolSelector.PROTOCOL_PROPERTY, "playwright");
        endpoints(null);
        ProtocolSelector.useConnectorForTests((endpoint, url) -> {
            throw new IllegalStateException("error: refused\nfix: start it");
        });
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null)));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null)));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("firefox", "firefox", null)));
        System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, "http://127.0.0.1:9");
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome", null)));
        List<String> lines = BrowserLog.linesForTests();
        assertEquals(1, lines.stream().filter(line -> line.contains("playwright-endpoint")).count(), lines.toString());
        assertEquals(1, lines.stream().filter(line -> line.contains("playwright-family")).count(), lines.toString());
        assertEquals(1, lines.stream().filter(line -> line.contains("playwright-connect") && line.contains("error: refused")).count(), lines.toString());
    }

    @Test
    void noEndpointIsAnErrorWithFixAndPlaywrightNeverDownloadsBrowsers() {
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> PlaywrightConnection.connect(null));
        assertTrue(none.getMessage().startsWith("error: no CDP endpoint for Playwright"), none.getMessage());
        assertTrue(none.getMessage().contains("\nfix: set pkb_cdp_endpoint"), none.getMessage());
        assertEquals(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"), PlaywrightConnection.DRIVER_ENV);
        assertEquals("http://127.0.0.1:9", PlaywrightConnection.configuredEndpoint("  http://127.0.0.1:9 "));
        assertEquals("ws://h/x?token=<redacted>&a=1", PlaywrightConnection.redact("ws://h/x?token=s3cret&a=1"));
        assertFalse(PlaywrightConnection.redact("ws://user:pw@h/x").contains("pw"));
        assertEquals("xpath=//a", PlaywrightConnection.selector("xpath", "//a"));
        assertEquals("css=[name=\"q\"]", PlaywrightConnection.selector("name", "q"));
        assertEquals("text=Save >> nth=0", PlaywrightConnection.selector("pw", "text=Save >> nth=0"));
    }

    private static WebDriver named(String label, String browserName, String url) {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", browserName);
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> caps;
                    case "getCurrentUrl" -> url;
                    case "toString" -> label;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    static final class FakeSession implements PlaywrightAdapter.Session {
        public Object evaluate(String worldName, String fn, Object[] args) { return null; }
        public void addInitScript(String script) { }
        public void exposeBinding(String name, BindingListener listener) { }
        public byte[] screenshot(Map<String, ?> options) { return new byte[0]; }
        public FrameNode frames() { return FrameNode.leaf("main", null, ""); }
        public Object send(String method, Map<String, ?> params) { return null; }
        public List<String> find(String using, String value) { return List.of(); }
        public void click(String handle) { }
        public void type(String handle, String text) { }
    }
}
