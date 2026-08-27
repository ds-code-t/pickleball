package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBrowserLadderTest {
    @TempDir
    Path tempDir;

    @Test
    void remoteProjectBrowserIsKept() throws Exception {
        writeProperties("pkb_browser=SAUCE_CHROME\n");
        Files.createDirectories(tempDir.resolve("src/test/resources/configs"));
        Files.writeString(tempDir.resolve("src/test/resources/configs/GRID_CHROME.yaml"), "unused: true\n");

        AgentBrowserLadder.Decision decision = AgentBrowserLadder.select(tempDir);

        assertEquals("SAUCE_CHROME", decision.browser());
        assertTrue(decision.remoteKept());
        assertTrue(AgentBrowserLadder.isRemoteBrowser("GRID_EDGE"));
        assertTrue(AgentBrowserLadder.isRemoteBrowser("REMOTE_CHROME"));
    }

    @Test
    void localBrowserPrefersChromeHeadlessAndIgnoresUnusedSauceYaml() throws Exception {
        writeProperties("pkb_browser=chrome\n");
        Path configs = tempDir.resolve("src/test/resources/configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("SAUCE_CHROME.yaml"), "unused: true\n");
        Files.writeString(configs.resolve("GRID_CHROME.yaml"), "unused: true\n");
        Files.writeString(configs.resolve("REMOTE_EDGE.yaml"), "unused: true\n");

        AgentBrowserLadder.Decision decision = AgentBrowserLadder.select(tempDir);

        assertEquals("CHROME_HEADLESS", decision.browser());
        assertFalse(decision.remoteKept());
        assertEquals("chrome", decision.projectBrowser());
        assertNull(AgentBrowserLadder.fallbackIfHeadlessCannotStart(decision));
    }

    @Test
    void missingBrowserPrefersBundledChromeHeadless() {
        AgentBrowserLadder.Decision decision = AgentBrowserLadder.select(tempDir);
        assertEquals("CHROME_HEADLESS", decision.browser());
        assertFalse(decision.remoteKept());
    }

    private void writeProperties(String body) throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), body);
    }
}
