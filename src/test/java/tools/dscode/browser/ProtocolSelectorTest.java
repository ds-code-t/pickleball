package tools.dscode.browser;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSelectorTest {
    @Test
    void chromeLikeDriverUsesTheSeleniumAdapter() {
        WebDriver chrome = named("org.openqa.selenium.chrome.ChromeDriver");
        BrowserFacade facade = ProtocolSelector.forDriver(chrome);
        assertInstanceOf(SeleniumAdapter.class, facade);
        assertEquals("selenium", facade.protocol());
        assertSame(chrome, ((SeleniumAdapter) facade).driver());
    }

    @Test
    void firefoxAndEdgeAlsoStayOnTheLibraryAdapter() {
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("org.openqa.selenium.firefox.FirefoxDriver")));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("org.openqa.selenium.edge.EdgeDriver")));
        assertInstanceOf(SeleniumAdapter.class, ProtocolSelector.forDriver(named("org.openqa.selenium.htmlunit.HtmlUnitDriver")));
    }

    @Test
    void nullDriverFailsWithAFix() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> ProtocolSelector.forDriver(null));
        assertTrue(failure.getMessage().startsWith("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    /**
     * A proxy whose runtime class is not a real driver class. The selector
     * still returns the library adapter. Class-name checks land with the
     * socket adapters; this phase does not open a socket for any name.
     */
    private static WebDriver named(String ignored) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> ignored;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
