package tools.dscode.launcher;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.common.driver.DriverConstruction;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 live: a Selenium Chrome session built from DriverConstruction's options has a BiDi socket by default, doctor
 * --protocols lists bidi and cdp for it, and {@code pkb_bidi=false} removes bidi. Firefox runs only when a binary is
 * present (none on this machine shows a visible skip).
 */
class BrowserProtocolsLiveTest {
    @TempDir
    Path temp;

    private ChromeDriver driver;

    @AfterEach
    void quit() {
        System.clearProperty("pkb_bidi");
        if (driver != null) driver.quit();
    }

    private ChromeDriver chrome() {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions options = DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        driver = new ChromeDriver(options);
        return driver;
    }

    @Test
    void seleniumSessionHasBidiByDefaultAndDoctorListsBidiAndCdp() throws Exception {
        ChromeDriver chrome = chrome();
        Object ws = chrome.getCapabilities().getCapability("webSocketUrl");
        assertNotNull(ws, "webSocketUrl=true is asked for by default");
        assertTrue(String.valueOf(ws).startsWith("ws://"), String.valueOf(ws));
        BrowserProtocols protocols = ProtocolSelector.protocols(chrome);
        assertTrue(protocols.isLive(BrowserProtocols.Backend.BIDI), protocols.table().toString());
        assertTrue(protocols.isLive(BrowserProtocols.Backend.CDP), protocols.table().toString());
        assertEquals(BrowserProtocols.Backend.BIDI, protocols.resolve("events", "auto"));

        @SuppressWarnings("unchecked")
        Map<String, Object> goog = (Map<String, Object>) chrome.getCapabilities().getCapability("goog:chromeOptions");
        String cdp = "http://" + goog.get("debuggerAddress");
        PwCommandLiveTest.Capture doctor = PwCommandLiveTest.agentIn(temp.resolve("project"), "doctor", "--protocols",
                "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + ws);
        assertEquals(0, doctor.code(), doctor.err() + doctor.out());
        assertTrue(doctor.out().contains("protocols=chrome:"), doctor.out());
        assertTrue(doctor.out().contains("protocol.backend.bidi=yes"), doctor.out());
        assertTrue(doctor.out().contains("protocol.backend.cdp=yes"), doctor.out());
        assertTrue(doctor.out().contains("protocol.capability.logpoint=cdp"), doctor.out());
        assertTrue(doctor.out().contains("protocol.capability.events=bidi"), doctor.out());
    }

    @Test
    void pkbBidiFalseRemovesBidi() {
        System.setProperty("pkb_bidi", "false");
        ChromeDriver chrome = chrome();
        Object ws = chrome.getCapabilities().getCapability("webSocketUrl");
        assertFalse(ws instanceof String s && s.startsWith("ws"), "no BiDi socket with pkb_bidi=false: " + ws);
        BrowserProtocols protocols = ProtocolSelector.protocols(chrome);
        assertFalse(protocols.isLive(BrowserProtocols.Backend.BIDI), protocols.table().toString());
        assertTrue(protocols.isLive(BrowserProtocols.Backend.CDP), protocols.table().toString());
    }

    @Test
    void firefoxListsBidiAndNoCdpWhenPresent() {
        String firefox = System.getenv("FIREFOX_BIN");
        boolean present = firefox != null && !firefox.isBlank() || ConsumerReadyCommands.onPath("firefox");
        Assumptions.assumeTrue(present, "skipped: no Firefox binary on this machine");
        org.openqa.selenium.firefox.FirefoxOptions options = new org.openqa.selenium.firefox.FirefoxOptions();
        options.setCapability(DriverConstruction.WEB_SOCKET_URL, true);
        options.addArguments("-headless");
        org.openqa.selenium.firefox.FirefoxDriver fx = new org.openqa.selenium.firefox.FirefoxDriver(options);
        try {
            BrowserProtocols protocols = ProtocolSelector.protocols(fx);
            assertTrue(protocols.isLive(BrowserProtocols.Backend.BIDI), protocols.table().toString());
            assertFalse(protocols.isLive(BrowserProtocols.Backend.CDP), protocols.table().toString());
        } finally {
            fx.quit();
        }
    }
}
