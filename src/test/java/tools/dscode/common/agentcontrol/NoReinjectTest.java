package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.agentfast.RuntimeInstall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void secondTabInstallsOnceAndFocusBackDoesNot() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path profile = temp.resolve("profile-tab");
        Files.createDirectories(profile);
        session = BrowserSession.launch("noreinject-tab", "about:blank", false, true, profile, java.util.List.of());
        LiveChromeDriver first = new LiveChromeDriver(session);
        AgentDriverHooks.template("noreinject");
        AgentDriverHooks.ready(first);
        AgentDriverHooks.template(null);
        PageSurfaces.resetRuntimeForTest();
        Path page = temp.resolve("save-tab.html");
        Files.writeString(page, "<!DOCTYPE html><html><body><button id=\"save\" data-pkb-handle=\"save\">Save</button></body></html>");
        Path other = temp.resolve("other-tab.html");
        Files.writeString(other, "<!DOCTYPE html><html><body><button id=\"other\" data-pkb-handle=\"other\">Other</button></body></html>");
        first.get(page.toUri().toString());
        Map<String, Object> painted = PageSurfaces.place(null, "append", "save", null, "", "border", null, null, null, temp);
        int afterFirst = RuntimeInstall.inclusions();
        assertTrue(afterFirst >= 1, String.valueOf(painted));
        String http = pageHttp(session.cdp);
        String firstId = targetId(first);
        assertFalse(firstId.isBlank(), "first tab has no target id");
        // window.open's return value is a Window. Runtime.evaluate returnByValue serializes it and does not come back.
        String pageId = createPage(http, other.toUri().toString());
        assertFalse(pageId.isBlank(), "Target.createTarget returned no id");
        assertFalse(pageId.equals(firstId), "createTarget reused " + firstId);
        BrowserSession secondSession = BrowserSession.attach("noreinject-tab-2", http, false, java.util.List.of(), pageId);
        try {
            LiveChromeDriver second = new LiveChromeDriver(secondSession);
            AgentDriverHooks.template("noreinject-tab");
            AgentDriverHooks.ready(second);
            AgentDriverHooks.template(null);
            assertTrue(PageSurfaces.focus(second), "second tab did not focus");
            // createTarget's navigation does not wait, and a loaded file URL can still be about:blank under load.
            second.get(other.toUri().toString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String ready = "no";
            String href = "";
            while (System.nanoTime() < deadline) {
                href = String.valueOf(second.executeScript("return location.href"));
                ready = String.valueOf(second.executeScript("return document.getElementById('other') ? 'yes' : 'no'"));
                if ("yes".equals(ready)) break;
                Thread.sleep(100);
            }
            assertEquals("yes", ready, "second tab did not load the other button href=" + href);
            second.executeScript("document.documentElement.setAttribute('data-pkb-tab','two')");
            assertEquals("two", String.valueOf(second.executeScript("return document.documentElement.getAttribute('data-pkb-tab')")));
            assertFalse("two".equals(String.valueOf(
                    first.executeScript("return document.documentElement.getAttribute('data-pkb-tab')"))));
            String bordersBefore = String.valueOf(first.executeScript(
                    "return document.querySelectorAll('[data-pkb-kind=border]').length"));
            int beforeSecond = RuntimeInstall.inclusions();
            Map<String, Object> onSecond = PageSurfaces.place(null, "append", "other", null, "", "border", null, null, null, temp);
            assertEquals(beforeSecond + 1, RuntimeInstall.inclusions(), String.valueOf(onSecond));
            assertEquals("1", String.valueOf(second.executeScript(
                    "return document.querySelectorAll('[data-pkb-kind=border]').length")), String.valueOf(onSecond));
            assertEquals(bordersBefore, String.valueOf(first.executeScript(
                    "return document.querySelectorAll('[data-pkb-kind=border]').length")), "second tab place painted the first document");
            Map<String, Object> again = PageSurfaces.place(null, "append", "other", null, "", "border", null, null, null, temp);
            assertEquals(beforeSecond + 1, RuntimeInstall.inclusions(), String.valueOf(again));
            AgentDriverHooks.ready(second);
            assertEquals(beforeSecond + 1, RuntimeInstall.inclusions(), "ready refocused an installed document");
            assertTrue(PageSurfaces.focus(first), "first tab did not focus");
            Map<String, Object> back = PageSurfaces.place(null, "append", "save", null, "", "border", null, null, null, temp);
            assertEquals(beforeSecond + 1, RuntimeInstall.inclusions(), String.valueOf(back));
        } finally {
            secondSession.close();
        }
    }

    /** Opens a second page on the browser target. A page socket cannot create tabs. */
    private static String createPage(String http, String url) throws Exception {
        String version = httpGet(http + "/json/version");
        String socket = jsonText(version, "webSocketDebuggerUrl");
        assertTrue(socket.contains("/devtools/browser/"), version);
        CdpConnection browser = CdpConnection.connect(URI.create(socket), Duration.ofSeconds(10));
        try {
            ObjectNode params = Jsons.object();
            params.put("url", url);
            JsonNode created = browser.call("Target.createTarget", params, Duration.ofSeconds(10));
            return created.path("targetId").asText("");
        } finally {
            browser.close();
        }
    }

    private static String jsonText(String json, String field) {
        String needle = "\"" + field + "\"";
        int key = json.indexOf(needle);
        if (key < 0) return "";
        int colon = json.indexOf(':', key + needle.length());
        if (colon < 0) return "";
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return "";
        int end = json.indexOf('"', quote + 1);
        return end < 0 ? "" : json.substring(quote + 1, end);
    }

    private static String pageHttp(String cdp) {
        String raw = cdp != null && cdp.startsWith("ws") ? "http" + cdp.substring(2) : String.valueOf(cdp);
        int slash = raw.indexOf("/devtools");
        return slash > 0 ? raw.substring(0, slash) : raw;
    }

    private static String targetId(LiveChromeDriver driver) {
        Object info = driver.executeCdpCommand("Target.getTargetInfo", Map.of());
        if (!(info instanceof Map<?, ?> map)) return "";
        Object body = map.get("targetInfo") instanceof Map<?, ?> nested ? nested : map;
        if (!(body instanceof Map<?, ?> target)) return "";
        Object id = target.get("targetId");
        return id == null ? "" : String.valueOf(id);
    }

    private static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
