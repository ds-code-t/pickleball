package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cdn cache read-back that uses a different build than the one requested or
 * downloaded says so. A numeric prefix that matches the cached build stays quiet.
 * The stub downloader only plants the Playwright cache. No browser is downloaded.
 */
class CdnCacheFallbackTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        BrowserVersionResolver.resetForTests();
        BrowserLog.resetForTests();
        System.clearProperty("PLAYWRIGHT_BROWSERS_PATH");
        System.clearProperty("pkb_browser_log");
    }

    @Test
    void cacheReadBackNamesADifferentUsedVersion() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        System.setProperty("pkb_browser_log", "resolve");
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            writeCache(cache, "chromium-1200", "120.0.6099.71");
            return new BrowserVersionResolver.Install(channel, "131.0.6778.204", null);
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(
                block("chrome", "131.0.6778.204", "cdn"));
        assertEquals("120.0.6099.71", resolved.version());
        assertEquals(
                "requested=131.0.6778.204 downloaded=131.0.6778.204 used=120.0.6099.71",
                resolved.detail());
        String lines = String.join("\n", BrowserLog.linesForTests());
        assertTrue(lines.contains("requested=131.0.6778.204"), lines);
        assertTrue(lines.contains("downloaded=131.0.6778.204"), lines);
        assertTrue(lines.contains("used=120.0.6099.71"), lines);
    }

    @Test
    void prefixMatchStaysQuiet() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        System.setProperty("pkb_browser_log", "resolve");
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            writeCache(cache, "chromium-1200", "131.0.6778.204");
            return new BrowserVersionResolver.Install(channel, "131.0.6778.204", null);
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "131", "cdn"));
        assertEquals("131.0.6778.204", resolved.version());
        assertEquals("", resolved.detail());
        String lines = String.join("\n", BrowserLog.linesForTests());
        assertFalse(lines.contains("downloaded="), lines);
        assertFalse(lines.contains("used="), lines);
    }

    private static void writeCache(Path cache, String directory, String version) throws Exception {
        Path browser = cache.resolve(directory);
        Files.createDirectories(browser.resolve("chrome-win64"));
        Files.writeString(browser.resolve("INSTALLATION_COMPLETE"), "", StandardCharsets.UTF_8);
        Files.writeString(browser.resolve("chrome-win64").resolve("chrome.exe"), "", StandardCharsets.UTF_8);
        int dash = directory.lastIndexOf('-');
        String name = directory.substring(0, dash);
        String revision = directory.substring(dash + 1);
        Files.writeString(cache.resolve("browsers.json"), """
                {"browsers":[{"name":"%s","revision":"%s","browserVersion":"%s"}]}
                """.formatted(name, revision, version), StandardCharsets.UTF_8);
    }

    private static BrowserVersionResolver.Block block(String channel, String version, String source) {
        return new BrowserVersionResolver.Block(channel, null, version, source, "top", false);
    }
}
