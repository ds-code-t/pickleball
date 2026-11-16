package tools.dscode.common.agentsurface.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.agentcontrol.OverlayFixtures;
import tools.dscode.common.agentsurface.PopupBridge;
import tools.dscode.common.agentsurface.PopupLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The popup chat page in a real Chrome against a real {@link PopupBridge}. The page is the same
 * one the popup host opens; driving it with ChromeDriver lets the test press Enter and click Send.
 * Skips only when this machine has no Chrome binary.
 */
class PopupChatLiveTest {
    @TempDir
    Path project;

    private ChromeDriver browser;
    private PopupBridge bridge;

    @AfterEach
    void release() {
        if (browser != null) {
            try {
                browser.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
        }
        if (bridge != null) bridge.close();
    }

    private void open(String runId) throws Exception {
        browser = OverlayFixtures.chrome();
        Assumptions.assumeTrue(browser != null, "skipped: no Chrome binary on this machine");
        bridge = PopupBridge.start(project, runId);
        browser.get(bridge.baseUrl());
    }

    private String chatText() {
        return String.valueOf(browser.executeScript("var c=document.getElementById('chat'); return c ? c.innerText : '';"));
    }

    private static boolean waitFor(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** Audit item 2, cause 2: the stream ended after the first batch and the page never listened again. */
    @Test
    @Disabled("P1: audit item 2 cause 2 (stream ends, page stops listening)")
    void secondAgentMessageArrivesAfterTheFirstBatch() throws Exception {
        open("popup-stream");
        PopupLedger.append(project, "popup-stream", "chat", "chat", "", "", "first agent line", "", "");
        assertTrue(waitFor(() -> chatText().contains("first agent line"), 8000), "first: " + chatText());
        Thread.sleep(16_000);
        PopupLedger.append(project, "popup-stream", "chat", "chat", "", "", "second agent line", "", "");
        assertTrue(waitFor(() -> chatText().contains("second agent line"), 6000),
                "the popup stopped receiving after the first batch: " + chatText());
    }

    /** Audit item 2, cause 1: Enter stored the message but the pane never showed it. */
    @Test
    @Disabled("P1: audit item 2 cause 1 (human message not echoed)")
    void humanMessageEchoesInThePane() throws Exception {
        open("popup-echo");
        WebElement input = browser.findElement(By.id("text"));
        input.sendKeys("hello from a human");
        input.sendKeys(Keys.ENTER);
        Path chat = project.resolve(".pickleball/runs/popup-echo/agent/chat/run.jsonl");
        assertTrue(waitFor(() -> {
            try {
                return Files.isRegularFile(chat) && Files.readString(chat).contains("hello from a human");
            } catch (Exception failure) {
                return false;
            }
        }, 5000), "Enter did not store the message");
        assertTrue(waitFor(() -> chatText().contains("hello from a human"), 3000),
                "the human message was stored but not shown: " + chatText());
        assertFalse(String.valueOf(browser.executeScript("return document.getElementById('text').value")).contains("hello"));
    }
}
