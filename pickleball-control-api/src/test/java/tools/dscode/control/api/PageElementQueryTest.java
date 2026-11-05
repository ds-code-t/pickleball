package tools.dscode.control.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.CompositeBinaryLocator;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.control.protocol.DirectControlProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageElementQueryTest {
    @TempDir
    Path temp;

    @BeforeEach
    void isolate() {
        ElementOperations.resetBatchCallsForTests();
        CompositeRuntime.use(temp.resolve("project"), Map.of(), new ClassLoader(null) {
        }, temp.resolve("home"));
    }

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void countsRawAndDisplayedElementsWithoutClicking() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger finds = new AtomicInteger();
        List<WebElement> elements = List.of(
                element(true, clicks),
                element(false, clicks),
                element(true, clicks)
        );
        WebDriver driver = driver(elements, finds);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(3, hit.rawMatchCount());
        assertEquals(2, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertFalse(hit.xpath().isBlank(), hit.xpath());
        assertEquals(0, clicks.get());
        assertTrue(finds.get() > 0);
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void liveFindUsesCompositeInsteadOfTheDriver() throws Exception {
        Path binary = stub(temp.resolve("composite"));
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        AtomicInteger finds = new AtomicInteger();
        WebDriver driver = driver(List.of(), finds);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals(0, finds.get());
        assertTrue(CompositeRuntime.primary());
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void liveFindUsesOneScriptAndDoesNotReadEachElement() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = scriptDriver(scripts);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertEquals(1, scripts.get());
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void inspectGroupsTheInteractiveBatchWithoutASecondQuery() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = scriptDriver(scripts);
        List<PageElementQuery.Hit> hits = PageElementQuery.inspect(driver);
        assertEquals(1, scripts.get());
        assertEquals(1, ElementOperations.batchCallsForTests());
        assertEquals(1, hits.size());
        PageElementQuery.Hit hit = hits.get(0);
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertFalse(hit.xpath().isBlank(), hit.xpath());
    }

    private static WebDriver scriptDriver(AtomicInteger scripts) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    if ("executeScript".equals(method.getName())) {
                        scripts.incrementAndGet();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("tag", "button");
                        row.put("text", "Save");
                        row.put("displayed", true);
                        row.put("enabled", true);
                        row.put("selected", false);
                        row.put("attributes", Map.of("id", "save"));
                        row.put("value", "Save");
                        row.put("outerHtml", "<button id=\"save\">Save</button>");
                        row.put("x", 1);
                        row.put("y", 2);
                        row.put("width", 10);
                        row.put("height", 8);
                        return List.of(row);
                    }
                    if ("findElements".equals(method.getName()) || "findElement".equals(method.getName())
                            || "executeAsyncScript".equals(method.getName())) {
                        throw new AssertionError("page query must not call " + method.getName());
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static WebDriver driver(List<WebElement> elements, AtomicInteger finds) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("findElements".equals(method.getName())) {
                        finds.incrementAndGet();
                        return elements;
                    }
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

    private static Path stub(Path path) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif "query" in argv:
                        body = {"ok": True, "composite": [{
                            "id": "main:1",
                            "text": "Save",
                            "role": "button",
                            "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                            "attributes": {"id": "save"},
                            "states": ["enabled"]
                        }]}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """;
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }
}
