package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.snapshot.SnapshotOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.browser.BindingListener;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.FrameNode;
import tools.dscode.common.composite.CompositeLog;

import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotFallbackWarnTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @AfterEach
    void reset() {
        SnapshotProducers.useFacadeForTests(null);
        CompositeLog.resetForTests();
    }

    @Test
    void adapterErrorWarnsOnceAndReturnsSeleniumFallback() {
        CompositeLog.resetForTests();
        SnapshotProducers.useFacadeForTests(driver -> new Broken());
        WebDriver driver = driver();
        JsonNode first = SnapshotProducers.page(driver, new SnapshotOptions());
        JsonNode second = SnapshotProducers.page(driver, new SnapshotOptions());
        assertEquals("selenium-fallback", first.path("transport").asText());
        assertEquals("selenium-fallback", second.path("transport").asText());
        assertTrue(first.path("screenshot").path("sha256").asText().length() > 10);
        assertTrue(first.path("premap").has("indexes"));
        assertFalse(first.path("nodes").isEmpty());
        JsonNode button = null;
        for (JsonNode node : first.path("nodes")) {
            if ("go".equals(node.path("attributes").path("id").asText())) button = node;
        }
        assertTrue(button != null);
        assertFalse(button.path("boxes").path("document_css_px").isNull());
        long warns = CompositeLog.linesForTests().stream()
                .filter(line -> line.equals("WARN snapshot fallback selenium reason=adapter-error:boom"))
                .count();
        assertEquals(1, warns, CompositeLog.linesForTests().toString());
    }

    @Test
    void missingAdapterWarnsOnce() {
        CompositeLog.resetForTests();
        SnapshotProducers.useFacadeForTests(driver -> new SeleniumProtocol());
        JsonNode json = SnapshotProducers.page(driver(), new SnapshotOptions());
        assertEquals("selenium-fallback", json.path("transport").asText());
        assertEquals(1, CompositeLog.linesForTests().stream()
                .filter(line -> line.equals("WARN snapshot fallback selenium reason=no-adapter"))
                .count());
    }

    private static WebDriver driver() {
        Map<String, Object> walk = canned();
        return (WebDriver) Proxy.newProxyInstance(
                SnapshotFallbackWarnTest.class.getClassLoader(),
                new Class<?>[] {WebDriver.class, JavascriptExecutor.class, TakesScreenshot.class, HasCapabilities.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "executeScript" -> walk;
                        case "getScreenshotAs" -> PNG;
                        case "getCurrentUrl" -> "http://fixture/";
                        case "getWindowHandle" -> "root";
                        case "getCapabilities" -> new ChromeOptions();
                        case "toString" -> "fake-driver";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type.equals(boolean.class)) return false;
        if (type.equals(int.class)) return 0;
        if (type.equals(long.class)) return 0L;
        return null;
    }

    private static Map<String, Object> canned() {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("tag", "button");
        button.put("attributes", Map.of("id", "go"));
        button.put("role", "button");
        button.put("name", "Go");
        button.put("states", List.of());
        button.put("style", Map.of("display", "block", "visibility", "visible", "opacity", "1"));
        button.put("rect", Map.of("x", 8, "y", 8, "width", 30, "height", 20));
        button.put("clientRects", List.of(Map.of("x", 8, "y", 8, "width", 30, "height", 20)));
        button.put("xpath", "/html[1]/body[1]/button[1]");
        button.put("children", List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tag", "body");
        body.put("attributes", Map.of());
        body.put("role", "");
        body.put("name", "");
        body.put("states", List.of());
        body.put("style", Map.of("display", "block", "visibility", "visible", "opacity", "1"));
        body.put("rect", Map.of("x", 0, "y", 0, "width", 800, "height", 600));
        body.put("clientRects", List.of());
        body.put("xpath", "");
        body.put("children", List.of(button));
        Map<String, Object> html = new LinkedHashMap<>(body);
        html.put("tag", "html");
        html.put("children", List.of(body));
        html.put("rect", Map.of("x", 0, "y", 0, "width", 800, "height", 600));
        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("url", "http://fixture/");
        walk.put("outerHTML", "<html><body><button id=\"go\">Go</button></body></html>");
        walk.put("scrollX", 0);
        walk.put("scrollY", 0);
        walk.put("innerWidth", 800);
        walk.put("innerHeight", 600);
        walk.put("devicePixelRatio", 1);
        walk.put("visual", Map.of("offsetX", 0, "offsetY", 0, "scale", 1, "width", 800, "height", 600));
        walk.put("root", html);
        return walk;
    }

    private static final class Broken implements BrowserFacade {
        @Override
        public String protocol() {
            return "cdp";
        }

        @Override
        public Object send(String method, Map<String, ?> params) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Object evaluateIsolated(String worldName, String fn, Object... args) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void addPreload(String script) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void addBinding(String name, BindingListener listener) {
        }

        @Override
        public byte[] screenshot(Map<String, ?> options) {
            throw new IllegalStateException("boom");
        }

        @Override
        public FrameNode frameTree() {
            throw new IllegalStateException("boom");
        }

        @Override
        public List<String> find(String using, String value) {
            return List.of();
        }

        @Override
        public void click(String handle) {
        }

        @Override
        public void type(String handle, String text) {
        }
    }

    private static final class SeleniumProtocol implements BrowserFacade {
        @Override
        public String protocol() {
            return "selenium";
        }

        @Override
        public Object send(String method, Map<String, ?> params) {
            return null;
        }

        @Override
        public Object evaluateIsolated(String worldName, String fn, Object... args) {
            return null;
        }

        @Override
        public void addPreload(String script) {
        }

        @Override
        public void addBinding(String name, BindingListener listener) {
        }

        @Override
        public byte[] screenshot(Map<String, ?> options) {
            return PNG;
        }

        @Override
        public FrameNode frameTree() {
            return FrameNode.leaf("root", null, "http://fixture/");
        }

        @Override
        public List<String> find(String using, String value) {
            return List.of();
        }

        @Override
        public void click(String handle) {
        }

        @Override
        public void type(String handle, String text) {
        }
    }
}
