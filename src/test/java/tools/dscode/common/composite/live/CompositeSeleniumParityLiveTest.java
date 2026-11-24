package tools.dscode.common.composite.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7: the same find, act (click, type, select), inspect, and wait script on the element-state fixture
 * plus a small form page gives the same observations with pkb_composite=on (Java engine) and
 * pkb_composite=off (SeleniumAdapter).
 */
class CompositeSeleniumParityLiveTest {
    private HttpServer server;

    /** RunVars snapshots system properties when it loads; load it before this class sets pkb_composite so the mode does not leak. */
    @org.junit.jupiter.api.BeforeAll
    static void loadRunVarsFirst() {
        System.clearProperty(CompositeMode.KEY);
        tools.dscode.common.variables.RunVars.resolveFromVars(CompositeMode.KEY);
    }

    @AfterEach
    void release() {
        System.clearProperty(CompositeMode.KEY);
        CompositeRuntime.resetForTests();
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void compositeAndSeleniumAgreeOnFindActInspectAndWait() throws Exception {
        String binary = chromeBinary();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        byte[] states = Files.readAllBytes(Path.of("src/test/resources/element-state/states.html"));
        byte[] form = FORM.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/states", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, states.length);
            exchange.getResponseBody().write(states);
            exchange.close();
        });
        server.createContext("/form", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, form.length);
            exchange.getResponseBody().write(form);
            exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();

        Map<String, String> selenium = observe("off", binary, origin);
        Map<String, String> composite = observe("on", binary, origin);
        assertEquals("composite", composite.get("path"), "pkb_composite=on joined the Java engine: " + composite);
        assertEquals("selenium", selenium.get("path"), selenium.toString());
        List<String> differences = new ArrayList<>();
        for (String key : selenium.keySet()) {
            if ("path".equals(key)) continue;
            if (!selenium.get(key).equals(composite.get(key))) {
                differences.add(key + ": selenium=" + selenium.get(key) + " composite=" + composite.get(key));
            }
        }
        assertEquals(selenium.size(), composite.size(), "both paths observed the same steps");
        assertTrue(differences.isEmpty(), "composite and Selenium differ:\n" + String.join("\n", differences));
    }

    private static Map<String, String> observe(String mode, String binary, String origin) {
        System.setProperty(CompositeMode.KEY, mode);
        CompositeRuntime.resetForTests();
        Map<String, String> seen = new LinkedHashMap<>();
        ChromeDriver browser = open(binary);
        try {
            DriverConstruction.ready(browser);
            browser.get(origin + "/states");
            seen.put("path", CompositeRuntime.browserJoined() ? "composite" : "selenium");
            seen.put("find.buttons", String.valueOf(ElementOperations.findElements(browser, By.tagName("button")).size()));
            seen.put("find.xpath", ElementOperations.findElement(browser, By.xpath("//div[@id='filled']")).getText());
            seen.put("find.css.missing", String.valueOf(ElementOperations.findElements(browser, By.cssSelector("#nope")).size()));
            seen.put("find.nested", ElementOperations.findElement(
                    ElementOperations.findElement(browser, By.id("host")), By.tagName("button")).getText());
            WebElement disabled = ElementOperations.findElement(browser, By.id("on-disabled"));
            seen.put("inspect.disabled.enabled", String.valueOf(disabled.isEnabled()));
            seen.put("inspect.enabled", String.valueOf(ElementOperations.findElement(browser, By.id("off-button")).isEnabled()));
            seen.put("inspect.checkbox", String.valueOf(ElementOperations.findElement(browser, By.id("box")).isSelected()));
            seen.put("inspect.empty-checkbox", String.valueOf(ElementOperations.findElement(browser, By.id("empty-box")).isSelected()));
            seen.put("inspect.aria", ElementOperations.findElement(browser, By.id("open")).getAttribute("aria-expanded"));
            seen.put("inspect.value", ElementOperations.findElement(browser, By.id("needed")).getAttribute("value"));
            seen.put("inspect.tag", ElementOperations.findElement(browser, By.id("choice")).getTagName());
            ElementView view = ElementOperations.describe(browser, By.id("filled")).get(0);
            seen.put("inspect.view", view.tag() + "|" + view.text() + "|" + view.displayed() + "|" + view.enabled());

            browser.get(origin + "/form");
            WebElement go = ElementOperations.findElement(browser, By.id("go"));
            ElementOperations.click(browser, go);
            seen.put("act.click", ElementOperations.findElement(browser, By.id("go")).getText());
            WebElement name = ElementOperations.findElement(browser, By.id("name"));
            ElementOperations.sendKeys(browser, name, "Ann");
            seen.put("act.type", ElementOperations.findElement(browser, By.id("name")).getAttribute("value"));
            ElementOperations.clear(browser, ElementOperations.findElement(browser, By.id("name")));
            seen.put("act.clear", ElementOperations.findElement(browser, By.id("name")).getAttribute("value"));
            ElementOperations.click(browser, ElementOperations.findElement(browser, By.id("color")));
            ElementOperations.click(browser, ElementOperations.findElement(browser, By.cssSelector("#color option[value='blue']")));
            seen.put("act.select", String.valueOf(browser.executeScript("return document.getElementById('color').value")));
            seen.put("act.select.option", String.valueOf(
                    ElementOperations.findElement(browser, By.cssSelector("#color option[value='blue']")).isSelected()));
            seen.put("inspect.hidden", String.valueOf(ElementOperations.findElement(browser, By.id("hidden")).isDisplayed()));
            seen.put("inspect.visible", String.valueOf(ElementOperations.findElement(browser, By.id("go")).isDisplayed()));

            ElementOperations.click(browser, ElementOperations.findElement(browser, By.id("later")));
            WebElement late = ElementOperations.waitFor(browser, By.id("late"), Duration.ofSeconds(5));
            seen.put("wait.appeared", late.getText());
            seen.put("wait.absent", String.valueOf(ElementOperations.findElements(browser, By.id("never")).size()));
            seen.put("title", browser.getTitle());
        } finally {
            try {
                browser.quit();
            } catch (RuntimeException ignored) {
                // Observations are already recorded.
            }
            CompositeRuntime.resetForTests();
        }
        return seen;
    }

    private static final String FORM = """
            <!DOCTYPE html><html><head><title>Parity</title></head><body>
            <button id="go" onclick="this.textContent='clicked'; document.title='Parity clicked'">Go</button>
            <input id="name" aria-label="Name">
            <select id="color"><option value="red">Red</option><option value="blue">Blue</option></select>
            <div id="hidden" style="display:none">Hidden</div>
            <button id="later" onclick="setTimeout(function(){var d=document.createElement('div');d.id='late';d.textContent='Late';document.body.appendChild(d);},400)">Later</button>
            </body></html>""";

    private static ChromeDriver open(String binary) {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        return new ChromeDriver(options);
    }

    private static String chromeBinary() {
        String pinned = System.getenv("CHROME_BIN");
        if (pinned != null && !pinned.isBlank()) {
            Path file = Path.of(pinned.trim());
            if (Files.isRegularFile(file)) return file.toString();
        }
        return dev.domengine.session.BrowserSession.findChrome();
    }
}
