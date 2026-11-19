package tools.dscode.common.agentcontrol;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.agentfast.RuntimeInstall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoReinjectTest {
    @TempDir
    Path temp;

    private BrowserSession session;

    @AfterEach
    void closeChrome() {
        AgentDriverHooks.template(null);
        try {
            AgentDriverHooks.quit();
        } catch (RuntimeException ignored) {
            // The session close still runs.
        }
        if (session != null) session.close();
        PageSurfaces.resetRuntimeForTest();
    }

    @Test
    void secondCallOmitsTheRuntimeBytes() {
        PageSurfaces.resetRuntimeForTest();
        String first = PageSurfaces.isolatedFunction(7, true);
        String second = PageSurfaces.isolatedFunction(7, true);
        assertTrue(first.contains("raisingLayer"), first);
        assertFalse(second.contains("raisingLayer"), second);
        assertTrue(second.contains("__pkbUi"), second);
        PageSurfaces.resetRuntimeForTest();
        String again = PageSurfaces.isolatedFunction(7, true);
        assertTrue(again.contains("raisingLayer"), again);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void sameDocumentDoesNotResendAndAReloadStillTracks() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        session = BrowserSession.launch("noreinject", "about:blank", false, true, profile, java.util.List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        AgentDriverHooks.template("noreinject");
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        PageSurfaces.resetRuntimeForTest();
        Path page = temp.resolve("save.html");
        Files.writeString(page, "<!DOCTYPE html><html><body><button id=\"save\" data-pkb-handle=\"save\">Save</button></body></html>");
        driver.get(page.toUri().toString());
        Map<String, Object> first = PageSurfaces.place(null, "append", "save", null, "", "border", null, null, null, temp);
        int afterFirst = RuntimeInstall.inclusions();
        assertTrue(afterFirst >= 1, String.valueOf(first));
        Map<String, Object> second = PageSurfaces.place(null, "append", "save", null, "", "border", null, null, null, temp);
        assertEquals(afterFirst, RuntimeInstall.inclusions(), String.valueOf(second));
        driver.navigate().refresh();
        Map<String, Object> again = PageSurfaces.place(null, "append", "save", null, "", "border", null, null, null, temp);
        assertFalse(String.valueOf(again).contains("runtime-missing"), String.valueOf(again));
        String count = PageSurfaces.runtimeCall("raiseCount", null, false);
        assertNotNull(count, String.valueOf(again));
        assertTrue(count.replaceAll("[^0-9]", "").length() > 0, count);
    }
}
