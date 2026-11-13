package tools.dscode.browser;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightAdapterTest {
    @TempDir
    Path temp;

    @BeforeEach
    void logs() {
        BrowserLog.resetForTests();
        System.setProperty(BrowserLog.DIR_KEY, temp.toString());
        System.setProperty(BrowserLog.FALLBACK_KEY, "warn");
    }

    @AfterEach
    void clear() {
        System.clearProperty(BrowserLog.DIR_KEY);
        System.clearProperty(BrowserLog.FALLBACK_KEY);
        BrowserLog.resetForTests();
    }

    @Test
    void theLibraryIsPresentInsideTheWideRange() {
        assertTrue(PlaywrightAdapter.libraryPresent());
        URL location = Playwright.class.getProtectionDomain().getCodeSource().getLocation();
        String path = location.toString();
        assertTrue(path.contains("playwright-1."), path);
        assertTrue(!path.contains("playwright-2."), path);
    }

    @Test
    void aSessionImplementsTheFacade() {
        AtomicReference<String> clicked = new AtomicReference<>();
        PlaywrightAdapter.Session session = new PlaywrightAdapter.Session() {
            @Override
            public Object evaluate(String worldName, String fn, Object[] args) {
                return worldName + ":" + fn;
            }

            @Override
            public void addInitScript(String script) {
            }

            @Override
            public void exposeBinding(String name, BindingListener listener) {
                listener.onEvent(name, "bound");
            }

            @Override
            public byte[] screenshot(Map<String, ?> options) {
                return new byte[]{1, 2};
            }

            @Override
            public FrameNode frames() {
                return FrameNode.leaf("page", null, "https://example.test");
            }

            @Override
            public Object send(String method, Map<String, ?> params) {
                throw new IllegalStateException("no cdp");
            }

            @Override
            public List<String> find(String using, String value) {
                return List.of(using + "=" + value);
            }

            @Override
            public void click(String handle) {
                clicked.set(handle);
            }

            @Override
            public void type(String handle, String text) {
                clicked.set(handle + ":" + text);
            }
        };
        PlaywrightAdapter adapter = new PlaywrightAdapter(session);
        assertEquals("playwright", adapter.protocol());
        assertEquals(List.of("css=#go"), adapter.find("css", "#go"));
        adapter.click("css=#go");
        assertEquals("css=#go", clicked.get());
        adapter.type("css=#go", "hi");
        assertEquals("css=#go:hi", clicked.get());
        assertEquals("pkb:1+1", adapter.evaluateIsolated("pkb", "1+1"));
        assertArrayEquals(new byte[]{1, 2}, adapter.screenshot(null));
        assertEquals("page", adapter.frameTree().id());
        AtomicReference<String> bound = new AtomicReference<>();
        adapter.addBinding("bridge", (name, value) -> bound.set(name + ":" + value));
        assertEquals("bridge:bound", bound.get());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> adapter.send("Page.captureScreenshot", null));
        assertEquals(1, BrowserLog.linesForTests().stream().filter(line -> line.contains("playwright-send")).count());
        assertTrue(failure.getMessage().contains("no cdp"));
    }

    @Test
    void openRejectsAnythingThatIsNotAPage() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> PlaywrightAdapter.open(null));
        assertTrue(missing.getMessage().contains("fix:"), missing.getMessage());
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> PlaywrightAdapter.open(new Object()));
        assertTrue(wrong.getMessage().contains("com.microsoft.playwright.Page"), wrong.getMessage());
    }
}
