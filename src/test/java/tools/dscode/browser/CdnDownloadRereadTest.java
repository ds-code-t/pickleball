package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A successful {@code cdn} download is re-read from the Playwright cache.
 * The stub downloader only plants that cache. No browser is downloaded.
 */
class CdnDownloadRereadTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        BrowserVersionResolver.resetForTests();
        System.clearProperty("PLAYWRIGHT_BROWSERS_PATH");
    }

    @Test
    void successfulDownloadRereadsTheInstalledBinary() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            writeCache(cache, "chromium-1200", "131.0.6778.204");
            return new BrowserVersionResolver.Install(channel, version, null);
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "131", "cdn"));
        Path binary = cache.resolve("chromium-1200").resolve("chrome-win64").resolve("chrome.exe");
        assertEquals("131.0.6778.204", resolved.version());
        assertEquals(binary, resolved.binary());
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
