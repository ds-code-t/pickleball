package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Version ladder, Safari leaf, and Playwright connect failure. No browser process is started. */
class BrowserVersionResolverTest {
    @AfterEach
    void reset() {
        BrowserVersionResolver.resetForTests();
        BrowserLog.resetForTests();
        System.clearProperty("pkb_browser_log");
    }

    @Test
    void configPathPrefixIsADirectoryNotARawPath(@TempDir Path temp) throws Exception {
        Path configs = temp.resolve("My Projects").resolve("configs");
        Files.createDirectories(configs);
        Files.writeString(configs.resolve("CHROME_HEADLESS.yaml"), "framePolicy: iframe\n");
        String config = System.getProperty("pkb_configpath");
        String browser = System.getProperty("pkb_browser");
        System.clearProperty("pkb_browser");
        try {
            System.setProperty("pkb_configpath", "wd-file:" + configs.toAbsolutePath());
            assertEquals("iframe", BrowserVersionResolver.framePolicy(temp));
            System.setProperty("pkb_configpath", "wd-file:C:\\Users\\Dan\\My Projects\\configs");
            assertEquals("top", BrowserVersionResolver.framePolicy(temp));
            System.setProperty("pkb_configpath", "wd-file:configs");
            assertEquals("top", BrowserVersionResolver.framePolicy(temp));
        } finally {
            restore("pkb_configpath", config);
            restore("pkb_browser", browser);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void localMissDoesNotCallTheDownloader() {
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useInstallsForTests(channel -> List.of());
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            fail("local miss must not download");
            return null;
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BrowserVersionResolver.resolve(block("chrome", "131", "local")));
        assertTrue(failure.getMessage().contains("error:"));
        assertTrue(failure.getMessage().contains("fix:"));
        assertEquals(0, downloads.get());
    }

    @Test
    void cdnMissCallsTheDownloaderOnce() {
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useCacheForTests(channel -> List.of());
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            return new BrowserVersionResolver.Install(channel, "131.0.6778.69", null);
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "131", "cdn"));
        assertEquals(1, downloads.get());
        assertEquals("131.0.6778.69", resolved.version());
        assertEquals(BrowserVersionResolver.Adapter.PLAYWRIGHT, resolved.adapter());
    }

    @Test
    void cdnFailureWithACachedBuildReturnsTheCache() {
        System.setProperty("pkb_browser_log", "resolve");
        BrowserVersionResolver.useCacheForTests(channel -> List.of(new BrowserVersionResolver.Install("chrome", "131.0.1", null)));
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            throw new IOException("cdn down");
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "132", "cdn"));
        assertEquals("131.0.1", resolved.version());
        assertTrue(BrowserLog.linesForTests().stream().anyMatch(line -> line.contains("resolve cache-fallback")),
                BrowserLog.linesForTests().toString());
        assertTrue(BrowserLog.linesForTests().stream().noneMatch(line -> line.contains("http") || line.contains("token")));
    }

    @Test
    void versionPrecisionPrefersTheMatchingBuild() {
        BrowserVersionResolver.useInstallsForTests(channel -> List.of(
                new BrowserVersionResolver.Install("chrome", "131.0.1", null),
                new BrowserVersionResolver.Install("chrome", "131.0.2", null),
                new BrowserVersionResolver.Install("chrome", "130.0.9", null)));
        assertEquals("131.0.2", BrowserVersionResolver.resolve(block("chrome", "131", "local")).version());
        assertEquals("131.0.1", BrowserVersionResolver.resolve(block("chrome", "131.0.1", "local")).version());
    }

    @Test
    void latestLocalReturnsTheHighestInstall() {
        BrowserVersionResolver.useInstallsForTests(channel -> List.of(
                new BrowserVersionResolver.Install("chrome", "120.0.1", null),
                new BrowserVersionResolver.Install("chrome", "131.0.2", null)));
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.resolve(block("chrome", "latest", "local"));
        assertEquals("131.0.2", resolved.version());
    }

    @Test
    void safariSelectsSeleniumWithoutStartingAProcess() {
        AtomicInteger selenium = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        BrowserVersionResolver.useSeleniumFactoryForTests(resolution -> selenium.incrementAndGet());
        BrowserVersionResolver.useDownloaderForTests((channel, version) -> {
            downloads.incrementAndGet();
            fail("safari must not download");
            return null;
        });
        BrowserVersionResolver.Resolution resolved = BrowserVersionResolver.attach(block("safari", "", "local"));
        assertEquals(BrowserVersionResolver.Adapter.SELENIUM, resolved.adapter());
        assertEquals("safari", resolved.channel());
        assertEquals(0, selenium.get());
        assertEquals(0, downloads.get());
    }

    @Test
    void playwrightFailureDoesNotCallTheSeleniumFactory() {
        AtomicInteger selenium = new AtomicInteger();
        BrowserVersionResolver.useInstallsForTests(channel -> List.of(new BrowserVersionResolver.Install("chrome", "131.0.1", null)));
        BrowserVersionResolver.useSeleniumFactoryForTests(resolution -> selenium.incrementAndGet());
        BrowserVersionResolver.usePlaywrightConnectForTests(resolution -> {
            throw new IllegalStateException("connect failed");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BrowserVersionResolver.attach(block("chrome", "131", "local")));
        assertTrue(failure.getMessage().contains("error:"));
        assertTrue(failure.getMessage().contains("fix:"));
        assertFalse(failure.getMessage().contains("SeleniumAdapter"));
        assertEquals(0, selenium.get());
    }

    private static BrowserVersionResolver.Block block(String channel, String version, String source) {
        return new BrowserVersionResolver.Block(channel, null, version, source, "top", false);
    }
}
