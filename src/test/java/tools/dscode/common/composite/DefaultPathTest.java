package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.SeleniumAdapter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPathTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeMode.KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void unsetAutoAndOffStayOnTheLibrary() {
        Path project = temp.resolve("project");
        ClassLoader empty = new ClassLoader(null) {
        };
        for (String mode : new String[]{null, "auto", "off"}) {
            CompositeRuntime.resetForTests();
            if (mode == null) {
                CompositeRuntime.use(project, java.util.Map.of(), empty, temp.resolve("home"));
            } else {
                CompositeRuntime.use(project, java.util.Map.of("PKB_COMPOSITE", mode), empty, temp.resolve("home"));
            }
            assertFalse(CompositeRuntime.primary(), String.valueOf(mode));
            assertFalse(CompositeRuntime.current().primary(), String.valueOf(mode));
        }
        CompositeRuntime.resetForTests();
        CompositeRuntime.use(
                project,
                java.util.Map.of("PKB_COMPOSITE", "on"),
                DefaultPathTest.class.getClassLoader(),
                temp.resolve("home")
        );
        assertTrue(CompositeRuntime.primary(), CompositeLog.lastFallbackForTests());
    }

    @Test
    void scriptedFindUsesSeleniumAdapterAndNotADirectCall() {
        System.setProperty(CompositeMode.KEY, "auto");
        CompositeRuntime.resetForTests();
        AtomicInteger finds = new AtomicInteger();
        AtomicInteger cdp = new AtomicInteger();
        WebElement element = element();
        WebDriver driver = driver(finds, cdp, element);
        List<WebElement> found = ElementOperations.findElements(driver, By.cssSelector("#go"));
        assertEquals(List.of(element), found);
        assertEquals(1, finds.get());
        assertEquals(0, cdp.get());
        BrowserFacade facade = ElementOperations.library(driver);
        assertInstanceOf(SeleniumAdapter.class, facade);
        assertEquals("selenium", facade.protocol());
        assertFalse(CompositeRuntime.primary());
    }

    private static WebElement element() {
        return (WebElement) java.lang.reflect.Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "element";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private static WebDriver driver(AtomicInteger finds, AtomicInteger cdp, WebElement element) {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findElements" -> {
                        finds.incrementAndGet();
                        yield List.of(element);
                    }
                    case "executeCdpCommand" -> {
                        cdp.incrementAndGet();
                        yield java.util.Map.of();
                    }
                    case "toString" -> "driver";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
