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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSelectorTest {
    @TempDir
    Path temp;

    @BeforeEach
    void logs() {
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
        ProtocolSelector.resetForTests();
    }

    @Test
    void classNamesSelectChromiumAndFirefox() {
        assertEquals(ProtocolSelector.Family.CHROMIUM,
                ProtocolSelector.classify("org.openqa.selenium.chrome.ChromeDriver", null));
        assertEquals(ProtocolSelector.Family.CHROMIUM,
                ProtocolSelector.classify("org.openqa.selenium.edge.EdgeDriver", ""));
        assertEquals(ProtocolSelector.Family.FIREFOX,
                ProtocolSelector.classify("org.openqa.selenium.firefox.FirefoxDriver", null));
        assertEquals(ProtocolSelector.Family.OTHER,
                ProtocolSelector.classify("org.openqa.selenium.htmlunit.HtmlUnitDriver", "htmlunit"));
    }

    @Test
    void chromiumWithAWebsocketUsesCdpAndFirefoxUsesBidi() {
        ProtocolSockets.useFactoryForTests(uri -> new ScriptedSocket());
        ProtocolSelector.useEndpointsForTests(new ProtocolSelector.Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return "chrome".equals(driver.toString()) ? URI.create("ws://127.0.0.1:9/devtools/browser/x") : null;
            }

            @Override
            public URI bidi(WebDriver driver) {
                return "firefox".equals(driver.toString()) ? URI.create("ws://127.0.0.1:9/session") : null;
            }
        });
        assertInstanceOf(CdpAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome")));
        assertInstanceOf(BidiAdapter.class, ProtocolSelector.forDriver(named("firefox", "firefox")));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("htmlunit", "htmlunit")));
        assertTrue(BrowserLog.linesForTests().stream().noneMatch(line -> line.contains("htmlunit")),
                BrowserLog.linesForTests().toString());
    }

    @Test
    void missingEndpointFallsBackOnce() throws Exception {
        ProtocolSelector.useEndpointsForTests(new ProtocolSelector.Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return null;
            }

            @Override
            public URI bidi(WebDriver driver) {
                return null;
            }
        });
        WebDriver chrome = named("chrome", "chrome");
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(chrome));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(chrome));
        List<String> warns = BrowserLog.linesForTests().stream().filter(line -> line.contains("cdp-missing")).toList();
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).startsWith("WARN browser.fallback reason=cdp-missing"), warns.get(0));
        assertTrue(Files.list(temp).anyMatch(path -> path.getFileName().toString().startsWith("browser-")));
    }

    @Test
    void libraryPreferenceStaysOnSelenium() {
        ProtocolSockets.useFactoryForTests(uri -> new ScriptedSocket());
        ProtocolSelector.useEndpointsForTests(new ProtocolSelector.Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return URI.create("ws://127.0.0.1:9/devtools/browser/x");
            }
        });
        BrowserFacade facade = ProtocolSelector.forDriver(named("chrome", "chrome"), ProtocolSelector.Preference.LIBRARY);
        assertInstanceOf(SeleniumAdapter.class, facade);
        assertTrue(BrowserLog.linesForTests().stream().noneMatch(line -> line.contains("WARN")),
                BrowserLog.linesForTests().toString());
    }

    @Test
    void aFailedSocketFallsBackOnce() {
        ProtocolSockets.useFactoryForTests(uri -> {
            throw new IllegalStateException("refused");
        });
        ProtocolSelector.useEndpointsForTests(new ProtocolSelector.Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return URI.create("ws://127.0.0.1:9/devtools/browser/x");
            }
        });
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("chrome", "chrome")));
        assertEquals(1, BrowserLog.linesForTests().stream().filter(line -> line.contains("cdp-connect")).count());
    }

    @Test
    void nullDriverFailsWithAFix() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> ProtocolSelector.forDriver(null));
        assertTrue(failure.getMessage().startsWith("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    @Test
    void theLogFileIsDateStampedAndRolls() throws Exception {
        BrowserLog.testingCap(80);
        BrowserLog.info("selector", "one");
        BrowserLog.info("selector", "two two two two two");
        List<Path> files = Files.list(temp).toList();
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().matches("browser-\\d{4}-\\d{2}-\\d{2}\\.log")));
        assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().contains(".1.log")), files.toString());
    }

    private static WebDriver named(String label, String browserName) {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", browserName);
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> caps;
                    case "toString" -> label;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
