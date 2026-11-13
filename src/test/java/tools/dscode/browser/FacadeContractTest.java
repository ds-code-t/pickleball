package tools.dscode.browser;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadeContractTest {
    @Test
    void seleniumAdapterImplementsTheFacadeContract() {
        AtomicReference<String> script = new AtomicReference<>();
        AtomicReference<Boolean> clicked = new AtomicReference<>(false);
        AtomicReference<String> typed = new AtomicReference<>();
        WebElement element = element(clicked, typed);
        WebDriver driver = driver(script, element, "https://example.test/home", "window-1", "png".getBytes(StandardCharsets.UTF_8));
        BrowserFacade facade = new SeleniumAdapter(driver);

        assertEquals("selenium", facade.protocol());
        assertEquals(List.of("selenium-1"), facade.find("css", "#go"));
        facade.click("selenium-1");
        facade.type("selenium-1", "hi");
        assertEquals(Boolean.TRUE, clicked.get());
        assertEquals("hi", typed.get());

        assertEquals("ran", facade.evaluateIsolated("pkb-snapshot", "return 1"));
        assertEquals("return 1", script.get());
        facade.addPreload("preload()");
        assertEquals(List.of("preload()"), ((SeleniumAdapter) facade).preloads());
        assertEquals("preload()", script.get());

        byte[] png = facade.screenshot(null);
        assertArrayEquals("png".getBytes(StandardCharsets.UTF_8), png);

        FrameNode tree = facade.frameTree();
        assertEquals("window-1", tree.id());
        assertEquals("https://example.test/home", tree.url());
        assertTrue(tree.children().isEmpty());

        UnsupportedOperationException unsupported = assertThrows(
                UnsupportedOperationException.class,
                () -> facade.send("Page.captureScreenshot", null));
        assertTrue(unsupported.getMessage().startsWith("error:"), unsupported.getMessage());
        assertTrue(unsupported.getMessage().contains("fix:"), unsupported.getMessage());
    }

    @Test
    void unknownHandleAndLocatorFailWithAFix() {
        WebDriver driver = driver(new AtomicReference<>(), element(new AtomicReference<>(), new AtomicReference<>()),
                "about:blank", "w", new byte[0]);
        SeleniumAdapter adapter = new SeleniumAdapter(driver);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> adapter.click("nope"));
        assertTrue(missing.getMessage().contains("fix:"));
        IllegalArgumentException locator = assertThrows(IllegalArgumentException.class, () -> adapter.find("class", "x"));
        assertTrue(locator.getMessage().contains("fix:"));
    }

    @Test
    void selectorReturnsTheSeleniumAdapter() {
        WebDriver driver = driver(new AtomicReference<>(), element(new AtomicReference<>(), new AtomicReference<>()),
                "about:blank", "w", new byte[0]);
        BrowserFacade facade = ProtocolSelector.forDriver(driver);
        assertInstanceOf(SeleniumAdapter.class, facade);
        assertSame(driver, ((SeleniumAdapter) facade).driver());
    }

    private static WebDriver driver(
            AtomicReference<String> script,
            WebElement element,
            String url,
            String handle,
            byte[] png
    ) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class, TakesScreenshot.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findElements" -> List.of(element);
                    case "getCurrentUrl" -> url;
                    case "getWindowHandle" -> handle;
                    case "executeScript" -> {
                        script.set(String.valueOf(args[0]));
                        yield "ran";
                    }
                    case "getScreenshotAs" -> {
                        assertEquals(OutputType.BYTES, args[0]);
                        yield png;
                    }
                    case "toString" -> "fake-driver";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static WebElement element(AtomicReference<Boolean> clicked, AtomicReference<String> typed) {
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "click" -> {
                        clicked.set(true);
                        yield null;
                    }
                    case "sendKeys" -> {
                        typed.set(String.valueOf(((CharSequence[]) args[0])[0]));
                        yield null;
                    }
                    case "toString" -> "fake-element";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (!type.isPrimitive() && type.isAssignableFrom(List.class)) return List.of();
        if (type == By.class) return null;
        return null;
    }
}
