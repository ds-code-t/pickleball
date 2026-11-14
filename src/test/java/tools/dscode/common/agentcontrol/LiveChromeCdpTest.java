package tools.dscode.common.agentcontrol;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Chrome DevTools calls. Proxy drivers in {@link AgentLiveFixTest} do not prove these.
 * Skips only when this machine has no Chrome binary. Observe runs on the
 * in-repo Java engine unless PKB_LIVE_COMPOSITE names an external engine.
 * A binary that fails to open is a failure.
 */
class LiveChromeCdpTest {
    @TempDir
    Path temp;

    private ChromeDriver browser;

    @AfterEach
    void release() {
        PageSurfaces.clearDriver();
        CompositeRuntime.resetForTests();
        if (browser != null) {
            try {
                browser.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
            browser = null;
        }
    }

    @Test
    void overlayHighlightNodePaintsAResolvedOrdinaryNode() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: no Chrome binary on this machine");
        browser = open(binary);
        browser.get("data:text/html,<!doctype html><button id='save'>Save</button>");
        PageSurfaces.manage(browser, "chrome-headless-quiet", false);
        assertTrue(PageSurfaces.focus(browser), "page focus");

        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) browser.executeCdpCommand(
                "DOM.getDocument", Map.of("depth", 1, "pierce", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) document.get("root");
        int rootId = ((Number) root.get("nodeId")).intValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> found = (Map<String, Object>) browser.executeCdpCommand(
                "DOM.querySelector", Map.of("nodeId", rootId, "selector", "#save"));
        int nodeId = ((Number) found.get("nodeId")).intValue();
        assertTrue(nodeId > 0, "ordinary #save was not in the live document");
        @SuppressWarnings("unchecked")
        Map<String, Object> described = (Map<String, Object>) browser.executeCdpCommand(
                "DOM.describeNode", Map.of("nodeId", nodeId));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) described.get("node");
        int backend = ((Number) node.get("backendNodeId")).intValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) browser.executeCdpCommand(
                "DOM.resolveNode", Map.of("backendNodeId", backend));
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) resolved.get("object");
        assertNotNull(object.get("objectId"), "DOM.resolveNode did not resolve #save");

        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) browser.executeCdpCommand("Page.getFrameTree", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> frameTree = (Map<String, Object>) tree.get("frameTree");
        @SuppressWarnings("unchecked")
        Map<String, Object> frame = (Map<String, Object>) frameTree.get("frame");
        String frameId = String.valueOf(frame.get("id"));
        String handle = frameId + ":" + backend;
        PageSurfaces.bindHandle(browser, browser.getWindowHandle(), handle);

        String styleBefore = String.valueOf(((JavascriptExecutor) browser).executeScript(
                "return document.getElementById('save').getAttribute('style')"));
        Map<String, Object> painted = PageSurfaces.highlight(null, handle, "yellow", null, null, null, null, null);
        assertEquals("Overlay.highlightNode", painted.get("cdp"), painted.toString());
        String styleAfter = String.valueOf(((JavascriptExecutor) browser).executeScript(
                "return document.getElementById('save').getAttribute('style')"));
        assertEquals(styleBefore, styleAfter, "CDP highlight must not rewrite the style attribute");
        PageSurfaces.unhighlight(null, handle);
    }

    @Test
    void cleanupHidesHighlightOnAFreshCdpSession() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: no Chrome binary on this machine");
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--remote-allow-origins=*");
        browser = new ChromeDriver(options);
        browser.get("data:text/html,<!doctype html><button id='stay'>Stay</button>");
        PageSurfaces.manage(browser, "chrome-headed-quiet", true);
        Map<String, Object> cleaned = PageSurfaces.cleanup(null);
        assertEquals(true, cleaned.get("cleaned"), cleaned.toString());
    }

    @Test
    void observeReadsTheSettleSessionOfTheSelectedTab() throws Exception {
        String chrome = chromeBinary();
        Path engine = compositeBinary();
        Assumptions.assumeTrue(chrome != null, "skipped: no Chrome binary on this machine");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/picked-tab", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            browser = open(chrome);
            browser.get("about:blank");
            String tabA = browser.getWindowHandle();
            String targetA = targetId(browser);
            browser.switchTo().newWindow(WindowType.TAB);
            String tabB = browser.getWindowHandle();
            String targetB = targetId(browser);
            assertFalse(targetA.equals(targetB), targetA + " vs " + targetB);
            Files.createDirectories(temp.resolve("logs"));
            Map<String, String> env = new java.util.LinkedHashMap<>();
            if (engine != null) env.put("COMPOSITE_BIN", engine.toString());
            env.put("pkb_composite", "on");
            env.put("pkb_composite_log", "session,observe,cdp");
            env.put("pkb_composite_log_dir", temp.resolve("logs").toString());
            CompositeRuntime.use(temp, env, getClass().getClassLoader(), temp);
            assertTrue(CompositeRuntime.primary(), String.valueOf(tools.dscode.common.composite.CompositeLog.lastFallbackForTests()));
            assertNotNull(CompositeRuntime.client(), "engine client did not start");
            PageSurfaces.manage(browser, "chrome-headless-quiet", false);
            assertEquals(tabB, browser.getWindowHandle());
            assertTrue(PageSurfaces.focus(browser), "selected tab session did not open");
            String focused = String.valueOf(CompositeRuntime.focusedCdp());
            assertTrue(focused.contains(targetB), focused);
            assertFalse(focused.contains("/devtools/page/" + targetA), focused);
            String session = CompositeRuntime.focusedSessionId();
            assertNotNull(session, focused);
            browser.get("http://127.0.0.1:" + server.getAddress().getPort() + "/picked-tab");
            assertEquals(tabB, browser.getWindowHandle());
            List<ObserveEvents.Event> events = ObserveEvents.fromEngine(Map.of(
                    "session", session,
                    "wait-ms", "2000",
                    "url", "picked-tab"
            ));
            assertTrue(events.stream().anyMatch(event -> event.url() != null && event.url().contains("/picked-tab")),
                    events.toString());
            assertEquals(session, CompositeRuntime.focusedSessionId());
            assertTrue(String.valueOf(CompositeRuntime.focusedCdp()).contains(targetB));
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static String targetId(ChromeDriver driver) {
        Map<String, Object> info = (Map<String, Object>) driver.executeCdpCommand("Target.getTargetInfo", Map.of());
        Object raw = info.get("targetInfo");
        if (raw instanceof Map<?, ?> target) {
            Object id = target.get("targetId");
            if (id != null) return id.toString();
        }
        Object id = info.get("targetId");
        assertNotNull(id, info.toString());
        return id.toString();
    }

    private static ChromeDriver open(String binary) {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--remote-allow-origins=*"
        );
        return new ChromeDriver(options);
    }

    /**
     * Same Chrome lookup the rest of the suite uses, plus an explicit pin.
     * Dom-engine order: {@code DOMENGINE_CHROME}, then {@code google-chrome},
     * {@code google-chrome-stable}, {@code chromium}, {@code chromium-browser},
     * {@code chrome} on {@code PATH}. A missing binary skips. A binary that
     * fails to open is a failure.
     */
    private static String chromeBinary() {
        String pinned = firstExecutable(
                System.getenv("PKB_CHROME_BINARY"),
                System.getenv("CHROME_BINARY"),
                System.getProperty("pickleball.chrome.binary")
        );
        if (pinned != null) return pinned;
        String domengine = System.getenv("DOMENGINE_CHROME");
        if (domengine != null && !domengine.isBlank()) {
            String named = executableFile(domengine.trim());
            if (named != null) return named;
            return domengine.trim();
        }
        String onPath = firstOnPath(
                "google-chrome",
                "google-chrome-stable",
                "chromium",
                "chromium-browser",
                "chrome"
        );
        if (onPath != null) return onPath;
        return executableFile("/opt/pw-browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell");
    }

    private static String firstExecutable(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            String found = executableFile(candidate);
            if (found != null) return found;
        }
        return null;
    }

    private static String firstOnPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry == null || entry.isBlank()) continue;
                String found = executableFile(Path.of(entry).resolve(name).toString());
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String executableFile(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        Path file = Path.of(candidate.trim());
        if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
        return null;
    }

    /**
     * Optional external launcher named by PKB_LIVE_COMPOSITE. Null means the
     * in-repo Java control engine (dev.domengine).
     */
    private static Path compositeBinary() {
        String env = System.getenv("PKB_LIVE_COMPOSITE");
        Path[] candidates = {
                env == null || env.isBlank() ? null : Path.of(env.trim())
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
