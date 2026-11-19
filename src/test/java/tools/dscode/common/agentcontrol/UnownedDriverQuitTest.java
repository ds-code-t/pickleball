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
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import tools.dscode.common.agentfast.LiveChromeDriver;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B4: quit reaches an isolate or scenario driver that launch --start did not own. */
class UnownedDriverQuitTest {
    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AgentDriverHooks.host(false);
        AgentDriverHooks.template(null);
        try {
            PageSurfaces.quitAll();
        } catch (RuntimeException ignored) {
            // The assertion already ran.
        }
        PageSurfaces.clearDriver();
    }

    @Test
    void releaseUnownedCallsWebDriverQuit() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = proxy(quit, "unowned-driver");
        AgentDriverHooks.template(null);
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.releaseUnowned();
        assertTrue(quit.get(), "WebDriver.quit was not called");
    }

    @Test
    void quitLeavesTheHostAndReleaseHostQuitsIt() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = proxy(quit, "live-host");
        AgentDriverHooks.host(true);
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.host(false);
        AgentDriverHooks.quit();
        assertFalse(quit.get(), "shared quit detached the host page");
        try {
            AgentDriverHooks.releaseHost();
        } catch (IllegalStateException ignored) {
            // Cleanup on an empty tab set still reaches quitHost.
        }
        assertTrue(quit.get(), "releaseHost did not quit the host page");
    }

    @Test
    void templateNamedLiveIsNotTheHost() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = proxy(quit, "named-live");
        AgentDriverHooks.template("live");
        AgentDriverHooks.host(false);
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        try {
            AgentDriverHooks.quit();
        } catch (IllegalStateException failure) {
            // A proxy has no tabs. Cleanup still reaches the owned-driver quit.
        }
        assertTrue(quit.get(), "template name live was kept as the host page");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void quitReleasesTheScenarioPageAndLeavesTheHost() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        BrowserSession session = BrowserSession.launch(
                "quit-host", "about:blank", false, true, profile, java.util.List.of());
        BrowserSession scenarioSession = null;
        try {
            LiveChromeDriver host = new LiveChromeDriver(session);
            AgentDriverHooks.host(true);
            AgentDriverHooks.ready(host);
            AgentDriverHooks.host(false);
            String pageId = createPage(pageHttp(session.cdp), "about:blank");
            assertFalse(pageId.isBlank(), "Target.createTarget returned no id");
            scenarioSession = BrowserSession.attach("quit-scenario", pageHttp(session.cdp), false, java.util.List.of(), pageId);
            LiveChromeDriver scenario = new LiveChromeDriver(scenarioSession);
            AgentDriverHooks.template("scenario");
            AgentDriverHooks.ready(scenario);
            AgentDriverHooks.template(null);
            host.executeScript("document.title='host-page'");
            scenario.executeScript("document.title='scenario-page'");
            assertEquals("host-page", String.valueOf(host.executeScript("return document.title")));
            assertEquals("scenario-page", String.valueOf(scenario.executeScript("return document.title")));
            AgentDriverHooks.quit();
            assertThrows(WebDriverException.class, () -> scenario.executeScript("return document.title"));
            assertEquals("host-page", String.valueOf(host.executeScript("return document.title")));
        } finally {
            AgentDriverHooks.host(false);
            AgentDriverHooks.template(null);
            try {
                AgentDriverHooks.releaseHost();
            } catch (RuntimeException ignored) {
                // Chrome close still runs.
            }
            if (scenarioSession != null) scenarioSession.close();
            session.close();
        }
    }

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

    private static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static WebDriver proxy(AtomicBoolean quit, String name) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("getWindowHandles".equals(method.getName())) return Set.of();
                    if ("quit".equals(method.getName())) {
                        quit.set(true);
                        return null;
                    }
                    if ("toString".equals(method.getName())) return name;
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                }
        );
    }
}
