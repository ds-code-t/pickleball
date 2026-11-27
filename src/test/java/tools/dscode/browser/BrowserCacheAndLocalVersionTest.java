package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Production resolver path. The cache and the local install are directories on disk.
 * No browser is downloaded and no browser process is started.
 */
class BrowserCacheAndLocalVersionTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        BrowserVersionResolver.resetForTests();
        BrowserLog.resetForTests();
        System.clearProperty("pkb_browser_log");
        System.clearProperty("PLAYWRIGHT_BROWSERS_PATH");
        System.clearProperty("DOMENGINE_CHROME");
        System.clearProperty("DOMENGINE_EDGE");
    }

    @Test
    void cdnReadsThePlaywrightCacheBeforeAnyDownload() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        writeCache(cache, "chromium-1148", "131.0.6778.69");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            fail("cdn must use the cache before a download");
            return null;
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "131", "cdn"));
        assertEquals(0, downloads.get());
        assertEquals("131.0.6778.69", resolved.version());
        assertTrue(resolved.binary() != null && resolved.binary().startsWith(cache), String.valueOf(resolved.binary()));
    }

    @Test
    void cdnFailureUsesTheCachedBuildAndLogsCacheFallback() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        writeCache(cache, "chromium-1148", "131.0.1");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        System.setProperty("pkb_browser_log", "resolve");
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            throw new java.io.IOException("cdn down");
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "132", "cdn"));
        assertEquals("131.0.1", resolved.version());
        assertTrue(BrowserLog.linesForTests().stream().anyMatch(line -> line.contains("resolve cache-fallback")),
                BrowserLog.linesForTests().toString());
    }

    @Test
    void localDoesNotReadThePlaywrightCache() throws Exception {
        Path cache = temp.resolve("ms-playwright");
        writeCache(cache, "chromium-1148", "131.0.6778.69");
        System.setProperty("PLAYWRIGHT_BROWSERS_PATH", cache.toString());
        System.setProperty("DOMENGINE_CHROME", temp.resolve("missing-chrome.exe").toString());
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            fail("local must not download");
            return null;
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BrowserVersionResolver.resolve(block("chrome", "131", "local")));
        assertTrue(failure.getMessage().contains("error:"));
        assertTrue(failure.getMessage().contains("fix:"));
        assertEquals(0, downloads.get());
    }

    @Test
    void localVersionComesFromTheInstallDirectory() throws Exception {
        Path application = temp.resolve("Chrome").resolve("Application");
        Files.createDirectories(application.resolve("131.0.6778.69"));
        Files.createDirectories(application.resolve("131.0.6778.85"));
        Path exe = application.resolve("chrome.exe");
        Files.writeString(exe, "not-a-browser", StandardCharsets.UTF_8);
        System.setProperty("DOMENGINE_CHROME", exe.toString());
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            fail("local must not download");
            return null;
        });
        assertEquals("131.0.6778.85", BrowserVersionResolver.resolve(block("chrome", "131", "local")).version());
        assertEquals("131.0.6778.69", BrowserVersionResolver.resolve(block("chrome", "131.0.6778.69", "local")).version());
        assertEquals("131.0.6778.85", BrowserVersionResolver.resolve(block("chrome", "latest", "local")).version());
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> BrowserVersionResolver.resolve(block("chrome", "130", "local")));
        assertTrue(missing.getMessage().contains("error:"));
        assertTrue(missing.getMessage().contains("fix:"));
        assertEquals(0, downloads.get());
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
