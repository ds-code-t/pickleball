package tools.dscode.control.api;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PageElementQueryTest {
    @Test
    void countsRawAndDisplayedElementsWithoutClicking() {
        AtomicInteger clicks = new AtomicInteger();
        List<WebElement> elements = List.of(
                element(true, clicks),
                element(false, clicks),
                element(true, clicks)
        );
        WebDriver driver = driver(elements);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(3, hit.rawMatchCount());
        assertEquals(2, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertFalse(hit.xpath().isBlank(), hit.xpath());
        assertEquals(0, clicks.get());
    }

    private static WebDriver driver(List<WebElement> elements) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("findElements".equals(method.getName())) return elements;
                    if ("findElement".equals(method.getName())) {
                        throw new AssertionError("page query must not call findElement");
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static WebElement element(boolean displayed, AtomicInteger clicks) {
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisplayed" -> displayed;
                    case "click" -> {
                        clicks.incrementAndGet();
                        yield null;
                    }
                    case "getTagName" -> "button";
                    case "getText" -> "Save";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == String.class) return "";
        return null;
    }
}
