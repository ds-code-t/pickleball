package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenium fallback for a real browser driver: one script describes the match
 * list. Shadow text and an iframe are read through the same adapter.
 */
class ElementBatchEdgeTest {
    private HtmlUnitDriver driver;
    private Path logs;

    @BeforeEach
    void setUp() throws Exception {
        logs = Path.of("target", "element-batch-edge-logs");
        CompositeRuntime.use(Path.of("target", "element-batch-edge"), Map.of(
                CompositeLog.KEY, "batch",
                CompositeLog.DIR_KEY, logs.toString()
        ), new ClassLoader(null) {
        }, Path.of("target", "element-batch-edge-home"));
        driver = new HtmlUnitDriver(true);
        URL page = ElementBatchEdgeTest.class.getResource("/element-batch/edge.html");
        driver.get(java.util.Objects.requireNonNull(page, "edge.html").toString());
        ElementOperations.resetBatchCallsForTests();
        if (Files.isDirectory(logs)) {
            try (var files = Files.list(logs)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        CompositeRuntime.resetForTests();
    }

    @Test
    void oneScriptDescribesHiddenSelectedAndBounds() throws Exception {
        List<ElementView> buttons = ElementOperations.describe(driver, By.cssSelector("button"));
        assertEquals(2, buttons.size(), buttons.toString());
        ElementView shown = byId(buttons, "shown");
        ElementView hidden = byId(buttons, "hidden");
        assertTrue(shown.displayed(), shown.toString());
        assertFalse(hidden.displayed(), hidden.toString());
        assertEquals("button", shown.tag());
        assertTrue(shown.text().contains("Shown"), shown.toString());
        List<ElementView> boxes = ElementOperations.describe(driver, By.cssSelector("#box"));
        assertEquals(1, boxes.size());
        assertTrue(boxes.get(0).selected(), boxes.get(0).toString());
        assertEquals(2, ElementOperations.batchCallsForTests());
        String log = Files.readString(logs.resolve("composite-" + LocalDate.now(ZoneOffset.UTC) + ".log"));
        assertTrue(log.contains("mode=selenium-batch"), log);
        assertFalse(log.contains("mode=selenium-legacy"), log);
    }

    @Test
    void shadowTextAndFrameUseTheAdapter() {
        // HtmlUnit 5.0.0 has no Element.attachShadow, so a live open shadow root
        // cannot be built here. The scan script is still the adapter, and a host
        // whose getShadowRoot is already known is returned without another find.
        Object hosts = ElementOperations.script(driver,
                "var hosts = [];"
                        + "var all = document.querySelectorAll('*');"
                        + "for (var i = 0; i < all.length; i++) {"
                        + "  if (all[i].shadowRoot) hosts.push(all[i].id || all[i].tagName);"
                        + "}"
                        + "return hosts.length;");
        assertEquals(0, ((Number) hosts).intValue());
        SearchContext root = (SearchContext) Proxy.newProxyInstance(
                SearchContext.class.getClassLoader(),
                new Class<?>[]{SearchContext.class},
                (proxy, method, args) -> null
        );
        WebElement host = (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> {
                    if ("getShadowRoot".equals(method.getName())) return root;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
        assertTrue(ElementOperations.shadowRoot(host) == root);
        ElementOperations.script(driver,
                "var frame = document.getElementById('child');"
                        + "var doc = frame.contentDocument;"
                        + "doc.open();"
                        + "doc.write('<p id=\"inside\">Inside</p>');"
                        + "doc.close();");
        ElementOperations.enterFrame(driver, 0);
        List<ElementView> inside = ElementOperations.describe(driver, By.cssSelector("#inside"));
        assertEquals(1, inside.size(), inside.toString());
        assertEquals("Inside", inside.get(0).text().trim());
        ElementOperations.defaultContent(driver);
    }

    private static ElementView byId(List<ElementView> views, String id) {
        return views.stream()
                .filter(view -> id.equals(view.attribute("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " missing from " + views));
    }
}
