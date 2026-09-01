package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class AgentBrowserLadderChecks {
    @Test
    void remoteProjectBrowserIsKeptAndUnusedSauceYamlIsIgnored() throws Exception {
        Path project = Files.createTempDirectory("pkb-browser-ladder-remote-");
        try {
            Path resources = project.resolve("src/test/resources");
            Files.createDirectories(resources.resolve("configs"));
            Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=SAUCE_CHROME\n");
            Files.writeString(resources.resolve("configs/GRID_CHROME.yaml"), "unused: true\n");

            AgentBrowserLadder.Decision decision = AgentBrowserLadder.select(project);
            assertEquals("SAUCE_CHROME", decision.browser());
            assertTrue(decision.remoteKept());
        } finally {
            deleteTree(project);
        }
    }

    @Test
    void localBrowserPrefersChromeHeadless() throws Exception {
        Path project = Files.createTempDirectory("pkb-browser-ladder-local-");
        try {
            Path resources = project.resolve("src/test/resources");
            Files.createDirectories(resources.resolve("configs"));
            Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");
            Files.writeString(resources.resolve("configs/SAUCE_CHROME.yaml"), "unused: true\n");

            AgentBrowserLadder.Decision decision = AgentBrowserLadder.select(project);
            assertEquals("CHROME_HEADLESS", decision.browser());
            assertFalse(decision.remoteKept());
            assertNull(AgentBrowserLadder.fallbackIfHeadlessCannotStart(decision));
        } finally {
            deleteTree(project);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
