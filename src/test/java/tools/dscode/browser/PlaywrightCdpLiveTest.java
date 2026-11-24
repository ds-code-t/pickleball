package tools.dscode.browser;

import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D12 live: Chrome is started the existing way (DOMENGINE_CHROME, then PATH) with a debugging port, and Playwright
 * attaches with connectOverCDP. Nothing is downloaded. Skips visibly only when this machine has no Chrome.
 */
class PlaywrightCdpLiveTest {
    static final String PAGE = "<!doctype html><title>pw</title><input id=name><button id=go onclick=\"setTimeout(()=>{document.getElementById('out').textContent='hello '+document.getElementById('name').value},150)\">Go</button><p id=out></p>";

    @TempDir
    Path temp;

    private Process chrome;
    private HttpServer server;

    @AfterEach
    void stop() {
        PlaywrightConnection.closeAll();
        if (chrome != null) stopChrome(chrome);
        if (server != null) server.stop(0);
    }

    static void stopChrome(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts Chrome with --remote-debugging-port=0 and returns its http endpoint from DevToolsActivePort. */
    static String startChrome(Path profile, List<Process> started) throws Exception {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: no Chrome binary on this machine");
        Process process = new ProcessBuilder(binary, "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank")
                .redirectErrorStream(true).redirectOutput(profile.resolveSibling(profile.getFileName() + ".log").toFile()).start();
        started.add(process);
        Path port = profile.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(port)) {
                List<String> lines = Files.readAllLines(port);
                if (!lines.isEmpty() && !lines.get(0).isBlank()) return "http://127.0.0.1:" + lines.get(0).trim();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Chrome did not publish DevToolsActivePort");
    }

    private String serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void connectOverCdpFindsClicksTypesWaitsAndSendsCdp() throws Exception {
        java.util.ArrayList<Process> started = new java.util.ArrayList<>();
        String endpoint = startChrome(temp.resolve("profile"), started);
        chrome = started.get(0);
        String url = serve();
        PlaywrightConnection connection = PlaywrightConnection.connect(endpoint);
        assertTrue(connection.browser().isConnected());
        Page page = connection.page(null);
        page.navigate(url);
        PlaywrightAdapter adapter = PlaywrightAdapter.open(page);
        assertEquals("playwright", adapter.protocol());
        List<String> inputs = adapter.find("css", "#name");
        assertEquals(List.of("css=#name >> nth=0"), inputs);
        adapter.type(inputs.get(0), "Dan");
        adapter.click(adapter.find("text", "Go").get(0));
        page.locator("#out").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("hello Dan")).waitFor();
        assertEquals("hello Dan", page.locator("#out").textContent());
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluated = (Map<String, Object>) adapter.send("Runtime.evaluate", Map.of("expression", "document.title", "returnByValue", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) evaluated.get("result");
        assertEquals("pw", result.get("value"));
        assertEquals("main", adapter.frameTree().id());
        assertTrue(adapter.screenshot(null).length > 100);
        assertTrue(PlaywrightConnection.connect(endpoint) == connection, "one connection per endpoint");
        assertFalse(Files.exists(temp.resolve("ms-playwright")));
    }

    @Test
    void pkbProtocolPlaywrightSelectsTheAttachedPage() throws Exception {
        java.util.ArrayList<Process> started = new java.util.ArrayList<>();
        String endpoint = startChrome(temp.resolve("profile2"), started);
        chrome = started.get(0);
        String url = serve();
        PlaywrightConnection.connect(endpoint).page(null).navigate(url);
        System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, endpoint);
        try {
            BrowserFacade facade = ProtocolSelector.forDriver(selenium(url), ProtocolSelector.Preference.PLAYWRIGHT);
            assertEquals("playwright", facade.protocol());
            assertEquals(1, facade.find("css", "#go").size());
        } finally {
            System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        }
    }

    private static org.openqa.selenium.WebDriver selenium(String url) {
        org.openqa.selenium.MutableCapabilities caps = new org.openqa.selenium.MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        return (org.openqa.selenium.WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                PlaywrightCdpLiveTest.class.getClassLoader(),
                new Class<?>[]{org.openqa.selenium.WebDriver.class, org.openqa.selenium.HasCapabilities.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> caps;
                    case "getCurrentUrl" -> url;
                    case "toString" -> "chrome";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
