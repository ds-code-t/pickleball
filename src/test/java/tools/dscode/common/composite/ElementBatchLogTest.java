package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One describe is one adapter call. The trace line is off unless batch logging is asked for. */
class ElementBatchLogTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        CompositeRuntime.resetForTests();
        CompositeLog.resetForTests();
    }

    @Test
    void describeIsOneScriptAndOneTraceLine() throws Exception {
        Path logs = temp.resolve("logs");
        CompositeRuntime.use(temp.resolve("project"), Map.of(
                CompositeLog.KEY, "batch",
                CompositeLog.DIR_KEY, logs.toString()
        ), new ClassLoader(null) {
        }, temp.resolve("home"));
        AtomicInteger scripts = new AtomicInteger();
        AtomicInteger raw = new AtomicInteger();
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    if ("executeScript".equals(method.getName())) {
                        scripts.incrementAndGet();
                        return List.of(batchRow());
                    }
                    if ("findElements".equals(method.getName()) || "findElement".equals(method.getName())) {
                        raw.incrementAndGet();
                        throw new AssertionError(method.getName());
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
        ElementOperations.resetBatchCallsForTests();
        List<ElementView> views = ElementOperations.describe(driver, By.cssSelector("button"));
        assertEquals(1, views.size());
        assertEquals("button", views.get(0).tag());
        assertEquals("Save", views.get(0).text());
        assertTrue(views.get(0).displayed());
        assertEquals("save", views.get(0).attribute("id"));
        assertEquals(1, scripts.get());
        assertEquals(0, raw.get());
        assertEquals(1, ElementOperations.batchCallsForTests());
        Path file = logs.resolve("composite-" + LocalDate.now(ZoneOffset.UTC) + ".log");
        String text = Files.readString(file);
        assertTrue(text.contains("TRACE batch"), text);
        assertTrue(text.contains("mode=selenium-batch"), text);
        assertTrue(text.contains("nodes=1"), text);
        assertFalse(text.contains("mode=selenium-legacy"), text);
    }

    private static Map<String, Object> batchRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", "button");
        row.put("text", "Save");
        row.put("displayed", true);
        row.put("enabled", true);
        row.put("selected", false);
        row.put("attributes", Map.of("id", "save"));
        row.put("x", 0);
        row.put("y", 0);
        row.put("width", 1);
        row.put("height", 1);
        row.put("value", "");
        row.put("outerHtml", "<button>Save</button>");
        return row;
    }
}
