package tools.dscode.common.composite.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.EngineQuery;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapter behavior on a real page: displayed, selected, hover, frame, stale, and limit.
 * Skips only when no Chrome binary is present.
 */
class JavaEngineFlagsLiveTest {
    private ChromeDriver browser;
    private HttpServer server;

    @BeforeEach
    void useTheEngine() {
        System.setProperty(CompositeMode.KEY, "on");
    }

    @AfterEach
    void release() {
        System.clearProperty(CompositeMode.KEY);
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
    void flagsHoverFrameAndStaleGoThroughTheAdapter() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        byte[] html = page().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flags", exchange -> {
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        browser = open(binary);
        DriverConstruction.ready(browser);
        browser.get("http://127.0.0.1:" + server.getAddress().getPort() + "/flags");

        var shown = ElementOperations.findElement(browser, By.id("ok"));
        var hidden = ElementOperations.findElement(browser, By.id("hid"));
        assertTrue(shown.isDisplayed());
        assertFalse(hidden.isDisplayed());
        assertTrue(ElementOperations.findElement(browser, By.id("box")).isSelected());
        assertFalse(ElementOperations.findElement(browser, By.id("off")).isSelected());

        var hover = ElementOperations.findElement(browser, By.id("hov"));
        ElementOperations.hover(browser, hover);
        assertEquals("hovered", ElementOperations.findElement(browser, By.id("hov")).getText());

        assertEquals(2, ElementOperations.describe(browser, EngineQuery.of("css=button.n").limit(2)).size());

        ElementOperations.enterFrame(browser, ElementOperations.findElement(browser, By.id("boxframe")));
        assertEquals("Inner", ElementOperations.findElement(browser, By.id("inner")).getText());
        ElementOperations.parentFrame(browser);

        var gone = ElementOperations.findElement(browser, By.id("stale"));
        browser.executeScript("document.getElementById('stale').remove()");
        assertThrows(StaleElementReferenceException.class, () -> ElementOperations.click(browser, gone));
    }

    private static String page() {
        return """
                <!doctype html>
                <button id="ok">Ok</button>
                <button id="hid" style="display:none">Hid</button>
                <button id="hov">ready</button>
                <button id="stale">Gone</button>
                <button class="n">1</button><button class="n">2</button><button class="n">3</button>
                <input id="box" type="checkbox" checked>
                <input id="off" type="checkbox">
                <iframe id="boxframe" srcdoc="<button id='inner'>Inner</button>"></iframe>
                <script>
                document.getElementById('hov').addEventListener('mouseover', function () {
                  this.textContent = 'hovered';
                });
                </script>
                """;
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
