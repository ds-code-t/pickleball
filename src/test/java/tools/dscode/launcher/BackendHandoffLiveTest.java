package tools.dscode.launcher;

import com.microsoft.playwright.Page;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.browser.BackendHandoff;
import tools.dscode.browser.PlaywrightConnection;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D13 live: attach to a Selenium-started Chrome through its debuggerAddress, then hand the same browser
 * Selenium -> Playwright -> Selenium without restarting it. Skips only when this machine has no Chrome.
 */
class BackendHandoffLiveTest {
    @TempDir
    Path temp;

    private ChromeDriver selenium;

    @AfterEach
    void stop() {
        PlaywrightConnection.closeAll();
        if (selenium != null) selenium.quit();
    }

    @Test
    void seleniumDebuggerAddressAttachAndHandoffBothWaysKeepThePage() throws Exception {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: no Chrome binary on this machine");
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--remote-allow-origins=*");
        selenium = new ChromeDriver(options);
        selenium.get("data:text/html,<!doctype html><title>handoff</title><input id=name>");
        @SuppressWarnings("unchecked")
        Map<String, Object> chrome = (Map<String, Object>) selenium.getCapabilities().getCapability("goog:chromeOptions");
        String address = String.valueOf(chrome.get("debuggerAddress"));

        Capture attached = agent("attach", "--selenium=" + address, "--run-id=d13-selenium");
        assertEquals(0, attached.code, attached.err + attached.out);
        assertTrue(attached.err.contains("route=selenium"), attached.err);

        Page page = BackendHandoff.toPlaywright(selenium);
        assertEquals("handoff", page.title());
        page.fill("#name", "from playwright");

        PlaywrightConnection connection = PlaywrightConnection.connect(PlaywrightConnection.endpointFor(selenium));
        WebDriver back = BackendHandoff.toSelenium(connection, page);
        assertEquals("from playwright", back.findElement(By.id("name")).getAttribute("value"));
        back.findElement(By.id("name")).sendKeys(" and selenium");
        BackendHandoff.release(back);

        assertEquals("from playwright and selenium", page.inputValue("#name"), "the same live page, no restart");
        assertEquals("handoff", selenium.getTitle(), "the original Selenium session still drives the browser");
    }

    Capture agent(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
