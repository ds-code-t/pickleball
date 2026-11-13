package tools.dscode.common.composite.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real ChromeDriver through {@link DriverConstruction#ready} and
 * {@link ElementOperations}. Skips only when no Chrome binary is present.
 */
class JavaEngineAdapterLiveTest {
    private ChromeDriver browser;
    private HttpServer server;

    @AfterEach
    void release() {
        CompositeRuntime.resetForTests();
        if (browser != null) {
            try {
                browser.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
            browser = null;
        }
        if (server != null) server.stop(0);
    }

    @Test
    void findAndClickOnTheJoinedPageAndOnASecondTab() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] first = page("go", "Go").getBytes(StandardCharsets.UTF_8);
        byte[] second = page("other", "Other").getBytes(StandardCharsets.UTF_8);
        server.createContext("/first", exchange -> {
            exchange.sendResponseHeaders(200, first.length);
            exchange.getResponseBody().write(first);
            exchange.close();
        });
        server.createContext("/second", exchange -> {
            exchange.sendResponseHeaders(200, second.length);
            exchange.getResponseBody().write(second);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        browser = open(binary);
        DriverConstruction.ready(browser);
        browser.get("http://127.0.0.1:" + port + "/first");
        assertTrue(CompositeRuntime.browserJoined(), "attach did not join the page");
        var button = ElementOperations.findElement(browser, By.id("go"));
        assertEquals("Go", button.getText());
        ElementOperations.click(browser, button);
        assertEquals("clicked", ElementOperations.findElement(browser, By.id("go")).getText());
        assertFalse(String.valueOf(CompositeRuntime.focusedCdp()).contains("/devtools/browser/"),
                CompositeRuntime.focusedCdp());

        browser.switchTo().newWindow(WindowType.TAB);
        browser.get("http://127.0.0.1:" + port + "/second");
        assertTrue(CompositeRuntime.focusPage(browser, targetId(browser)), "second tab did not pin");
        assertEquals("Other", ElementOperations.findElement(browser, By.id("other")).getText());
    }

    @SuppressWarnings("unchecked")
    private static String targetId(ChromeDriver driver) {
        Map<String, Object> info = (Map<String, Object>) driver.executeCdpCommand("Target.getTargetInfo", Map.of());
        Object raw = info.get("targetInfo");
        if (raw instanceof Map<?, ?> target && target.get("targetId") != null) return target.get("targetId").toString();
        Object id = info.get("targetId");
        assertTrue(id != null, info.toString());
        return id.toString();
    }

    private static String page(String id, String label) {
        return "<!doctype html><button id='" + id + "'>" + label + "</button>"
                + "<script>document.getElementById('" + id + "').addEventListener('click',function(){"
                + "this.textContent='clicked';});</script>";
    }

    private static ChromeDriver open(String binary) {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        return new ChromeDriver(options);
    }

    private static String chromeBinary() {
        String pinned = System.getenv("DOMENGINE_CHROME");
        if (pinned == null || pinned.isBlank()) pinned = System.getenv("CHROME_BIN");
        if (pinned != null && !pinned.isBlank()) {
            Path file = Path.of(pinned.trim());
            if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String name : new String[] {"google-chrome", "google-chrome-stable", "chromium", "chrome"}) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) continue;
                Path file = Path.of(entry).resolve(name);
                if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
            }
        }
        return null;
    }
}
