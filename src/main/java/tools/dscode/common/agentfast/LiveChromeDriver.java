package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.logging.Logs;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Page driver for a Chrome process {@link BrowserSession} already launched.
 * {@code quit} detaches the page session and leaves the browser process to the host.
 */
public final class LiveChromeDriver implements WebDriver, TakesScreenshot, HasCapabilities, JavascriptExecutor {
    static final String HANDLE = "tab-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CALL = Duration.ofSeconds(15);

    private final BrowserSession session;
    private final Capabilities capabilities;
    private String pageTargetId;
    private String pageSessionId;
    /** True after the page socket is known. A null session id means this connection is already the page. */
    private boolean pageReady;

    public LiveChromeDriver(BrowserSession session) {
        if (session == null) throw new IllegalArgumentException("browser session is missing");
        this.session = session;
        String address = debuggerAddress(session.cdp);
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        caps.setCapability("goog:chromeOptions", Map.of("debuggerAddress", address));
        this.capabilities = caps;
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    /** CDP entry PageSurfaces uses to learn the page target and to take commands. */
    public Map<String, Object> executeCdpCommand(String command, Map<String, Object> params) {
        ensurePage();
        if ("Target.getTargetInfo".equals(command)) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", "page");
            info.put("targetId", pageTargetId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetInfo", info);
            return body;
        }
        ObjectNode node = Jsons.object();
        if (params != null) {
            params.forEach((key, value) -> {
                if (key != null) node.set(key, JSON.valueToTree(value));
            });
        }
        JsonNode result = session.connection().call(command, node, pageSessionId, CALL);
        if (result == null || result.isNull()) return Map.of();
        return JSON.convertValue(result, Map.class);
    }

    @Override
    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
        ensurePage();
        ObjectNode params = Jsons.object();
        params.put("format", "png");
        JsonNode shot = session.connection().call("Page.captureScreenshot", params, pageSessionId, CALL);
        String data = shot == null ? "" : shot.path("data").asText("");
        if (data.isBlank()) throw new WebDriverException("Page.captureScreenshot returned no data");
        return target.convertFromBase64Png(data);
    }

    @Override
    public Object executeScript(String script, Object... args) {
        ensurePage();
        String payload = script == null ? "" : script;
        String argsJson = "[]";
        if (args != null && args.length > 0) {
            try {
                argsJson = JSON.writeValueAsString(java.util.Arrays.asList(args));
            } catch (Exception failure) {
                throw new WebDriverException("script arguments: " + failure.getMessage(), failure);
            }
        }
        // Selenium runs the caller script as a function, so `return` is legal.
        String expression = "(function(){\n" + payload + "\n}).apply(null, " + argsJson + ")";
        ObjectNode params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = session.connection().call("Runtime.evaluate", params, pageSessionId, CALL);
        if (result != null && result.has("exceptionDetails")) {
            throw new WebDriverException(result.get("exceptionDetails").toString());
        }
        JsonNode value = result == null ? null : result.path("result").path("value");
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        return JSON.convertValue(value, Object.class);
    }

    @Override
    public Object executeAsyncScript(String script, Object... args) {
        throw new UnsupportedOperationException("executeAsyncScript");
    }

    @Override
    public void get(String url) {
        navigate().to(url);
    }

    @Override
    public String getCurrentUrl() {
        try {
            Object value = executeScript("return location.href");
            return value == null ? "about:blank" : String.valueOf(value);
        } catch (RuntimeException failure) {
            return "about:blank";
        }
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    public List<WebElement> findElements(By by) {
        return List.of();
    }

    @Override
    public WebElement findElement(By by) {
        throw new NoSuchElementException("LiveChromeDriver has no Selenium element lookup");
    }

    @Override
    public String getPageSource() {
        Object value = executeScript("return document.documentElement ? document.documentElement.outerHTML : ''");
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public void close() {
        quit();
    }

    @Override
    public void quit() {
        if (pageSessionId == null || pageSessionId.isBlank()) return;
        try {
            ObjectNode params = Jsons.object();
            params.put("sessionId", pageSessionId);
            session.connection().call("Target.detachFromTarget", params, CALL);
        } catch (RuntimeException ignored) {
            // The host still owns Chrome.
        }
        pageSessionId = null;
    }

    @Override
    public Set<String> getWindowHandles() {
        return Set.of(HANDLE);
    }

    @Override
    public String getWindowHandle() {
        return HANDLE;
    }

    @Override
    public TargetLocator switchTo() {
        return new TargetLocator() {
            @Override
            public WebDriver frame(int index) {
                return LiveChromeDriver.this;
            }

            @Override
            public WebDriver frame(String nameOrId) {
                return LiveChromeDriver.this;
            }

            @Override
            public WebDriver frame(WebElement frameElement) {
                return LiveChromeDriver.this;
            }

            @Override
            public WebDriver parentFrame() {
                return LiveChromeDriver.this;
            }

            @Override
            public WebDriver window(String nameOrHandle) {
                if (nameOrHandle == null || nameOrHandle.isBlank() || HANDLE.equals(nameOrHandle)) {
                    return LiveChromeDriver.this;
                }
                throw new NoSuchWindowException(nameOrHandle);
            }

            @Override
            public WebDriver newWindow(WindowType typeHint) {
                throw new UnsupportedOperationException("newWindow");
            }

            @Override
            public WebDriver defaultContent() {
                return LiveChromeDriver.this;
            }

            @Override
            public WebElement activeElement() {
                throw new UnsupportedOperationException("activeElement");
            }

            @Override
            public Alert alert() {
                throw new UnsupportedOperationException("alert");
            }
        };
    }

    @Override
    public Navigation navigate() {
        return new Navigation() {
            @Override
            public void back() {
            }

            @Override
            public void forward() {
            }

            @Override
            public void to(String url) {
                ensurePage();
                ObjectNode params = Jsons.object();
                params.put("url", url == null ? "about:blank" : url);
                session.connection().call("Page.navigate", params, pageSessionId, CALL);
            }

            @Override
            public void to(URL url) {
                to(url == null ? "about:blank" : url.toString());
            }

            @Override
            public void refresh() {
                ensurePage();
                session.connection().call("Page.reload", Jsons.object(), pageSessionId, CALL);
            }
        };
    }

    @Override
    public Options manage() {
        return new Options() {
            @Override
            public void addCookie(Cookie cookie) {
            }

            @Override
            public void deleteCookieNamed(String name) {
            }

            @Override
            public void deleteCookie(Cookie cookie) {
            }

            @Override
            public void deleteAllCookies() {
            }

            @Override
            public Set<Cookie> getCookies() {
                return Set.of();
            }

            @Override
            public Cookie getCookieNamed(String name) {
                return null;
            }

            @Override
            public Timeouts timeouts() {
                throw new UnsupportedOperationException("timeouts");
            }

            @Override
            public Window window() {
                throw new UnsupportedOperationException("window");
            }

            @Override
            public Logs logs() {
                throw new UnsupportedOperationException("logs");
            }
        };
    }

    private void ensurePage() {
        if (pageReady) return;
        if (!attachFlattenedPage()) useCurrentPageSocket();
        pageReady = true;
    }

    /**
     * Browser-level sockets need a flattened page session. A launch or attach
     * socket is already that page. {@code Target.getTargets} on a page can list
     * every tab and would pin this driver to a different one.
     */
    private boolean attachFlattenedPage() {
        try {
            JsonNode own = session.connection().call("Target.getTargetInfo", Jsons.object(), CALL);
            String ownType = own == null ? "" : own.path("targetInfo").path("type").asText("");
            if ("page".equals(ownType)) return false;
        } catch (RuntimeException ignored) {
            // Browser targets still resolve a page through Target.getTargets.
        }
        try {
            JsonNode targets = session.connection().call("Target.getTargets", Jsons.object(), CALL);
            String id = "";
            JsonNode infos = targets == null ? null : targets.get("targetInfos");
            if (infos != null && infos.isArray()) {
                for (JsonNode info : infos) {
                    if ("page".equals(info.path("type").asText(""))) id = info.path("targetId").asText("");
                }
            }
            if (id.isBlank()) return false;
            ObjectNode attach = Jsons.object();
            attach.put("targetId", id);
            attach.put("flatten", true);
            JsonNode attached = session.connection().call("Target.attachToTarget", attach, CALL);
            String sid = attached == null ? "" : attached.path("sessionId").asText("");
            if (sid.isBlank()) return false;
            pageTargetId = id;
            pageSessionId = sid;
            session.connection().call("Page.enable", Jsons.object(), pageSessionId, CALL);
            session.connection().call("Runtime.enable", Jsons.object(), pageSessionId, CALL);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void useCurrentPageSocket() {
        try {
            JsonNode info = session.connection().call("Target.getTargetInfo", Jsons.object(), CALL);
            String id = info == null ? "" : info.path("targetInfo").path("targetId").asText("");
            pageTargetId = id.isBlank() ? "page" : id;
        } catch (RuntimeException ignored) {
            pageTargetId = "page";
        }
        session.connection().call("Page.enable", Jsons.object(), CALL);
        session.connection().call("Runtime.enable", Jsons.object(), CALL);
    }

    private static String debuggerAddress(String cdp) {
        String raw = cdp == null ? "" : cdp.trim();
        if (raw.isBlank()) throw new IllegalArgumentException("cdp endpoint is missing");
        URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort();
        if (port < 0) throw new IllegalArgumentException("cdp endpoint has no port: " + raw);
        return host + ":" + port;
    }
}
