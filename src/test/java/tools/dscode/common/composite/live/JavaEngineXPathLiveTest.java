package tools.dscode.common.composite.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same XPath 1.0 set through ElementOperations, plus a text-only click. */
class JavaEngineXPathLiveTest {
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
        }
        if (server != null) server.stop(0);
    }

    @Test
    void adapterEvaluatesXpath10AndClicksTextOnlyElements() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        byte[] page = """
                <!doctype html><html><body>
                <button id="b1">One</button>
                <button id="b2" class="b">Two</button>
                <span id="vis" class="b">Visible</span>
                <ul><li>one</li><li> two </li><li><a id="inner">link</a></li></ul>
                <div id="host"><a id="child">child</a></div>
                <p>Only text</p>
                <div id="status">idle</div>
                <script>
                  document.querySelector('p').addEventListener('click', function () {
                    document.getElementById('status').textContent = 'clicked';
                  });
                </script>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        browser = open(binary);
        DriverConstruction.ready(browser);
        browser.get("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        assertTrue(CompositeRuntime.browserJoined());

        assertEquals(List.of("b1", "b2"), ids("//*[self::button or @role='button']"));
        assertEquals(List.of("vis"), ids("//*[@class='b' and @id='vis']"));
        assertEquals(List.of("vis"), ids("//*[normalize-space(.)='Visible']"));
        assertEquals(List.of("b2"), ids("(//button)[2]"));
        assertEquals(List.of("inner"), ids("//li/a"));
        assertEquals(List.of("host"), ids("//*[@id='child']/ancestor-or-self::div"));
        assertEquals(List.of("b2"), ids("//button[translate(@id,'B','b')='b2']"));
        assertEquals(List.of("b2", "vis"), ids("//*[contains(concat(' ', normalize-space(@class), ' '), ' b ')]"));
        assertEquals(1, ElementOperations.findElements(browser, By.xpath("//li[normalize-space()='two']")).size());

        WebElement host = ElementOperations.findElement(browser, By.id("host"));
        assertEquals("child", host.findElement(By.xpath(".//a")).getAttribute("id"));

        WebElement text = ElementOperations.findElement(browser, By.xpath("//*[normalize-space(.)='Only text']"));
        ElementOperations.click(browser, text);
        assertEquals("clicked", ElementOperations.findElement(browser, By.id("status")).getText());
    }

    private List<String> ids(String xpath) {
        List<String> ids = new java.util.ArrayList<>();
        for (WebElement element : ElementOperations.findElements(browser, By.xpath(xpath))) {
            ids.add(element.getAttribute("id"));
        }
        return ids;
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
