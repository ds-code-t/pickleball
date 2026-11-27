package tools.dscode.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import org.openqa.selenium.WebDriver;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Playwright over CDP only (D12). Playwright is created with {@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1}
 * unless {@code browser.source} is {@code cdn} or {@code any}. It never launches a browser: it joins an existing Chromium with
 * {@code chromium().connectOverCDP(endpoint)}. The endpoint is, in order, the explicit argument, the
 * {@code pkb_cdp_endpoint} property, env {@code PKB_CDP_ENDPOINT}, then the CDP endpoint of the Chrome pickleball
 * launched (its WebDriver {@code se:cdp} / {@code debuggerAddress}). With none, {@link #connect} fails with
 * {@link #NO_ENDPOINT}. One connection is kept per endpoint and closed at JVM exit.
 */
public final class PlaywrightConnection implements AutoCloseable {
    public static final String ENDPOINT_PROPERTY = "pkb_cdp_endpoint";
    public static final String ENDPOINT_ENV = "PKB_CDP_ENDPOINT";
    /** The environment every Playwright instance is created with. */
    public static final Map<String, String> DRIVER_ENV = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    public static final String NO_ENDPOINT = "error: no CDP endpoint for Playwright; pickleball never launches or downloads a Playwright browser.\n"
            + "fix: set pkb_cdp_endpoint (or PKB_CDP_ENDPOINT) to http://127.0.0.1:<port> of a Chromium started with --remote-debugging-port, "
            + "use attach --cdp-endpoint=<url>, or let pickleball launch Chrome and select pkb_protocol=playwright.";

    private static final Map<String, PlaywrightConnection> OPEN = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(PlaywrightConnection::closeAll, "pkb-playwright-close"));
    }

    private final String endpoint;
    private final Playwright playwright;
    private final Browser browser;
    private final int port;

    private PlaywrightConnection(String endpoint, Playwright playwright, Browser browser, int port) {
        this.endpoint = endpoint;
        this.playwright = playwright;
        this.browser = browser;
        this.port = port;
    }

    /** Explicit, then {@code pkb_cdp_endpoint}, then {@code PKB_CDP_ENDPOINT}; null when none is configured. */
    public static String configuredEndpoint(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String property = System.getProperty(ENDPOINT_PROPERTY);
        if (property != null && !property.isBlank()) return property.trim();
        String env = System.getenv(ENDPOINT_ENV);
        return env == null || env.isBlank() ? null : env.trim();
    }

    /** The configured endpoint, else the launched driver's CDP endpoint, else null. */
    public static String endpointFor(WebDriver driver) {
        String configured = configuredEndpoint(null);
        if (configured != null) return configured;
        if (driver == null) return null;
        URI uri = DriverEndpoints.cdp(driver);
        return uri == null ? null : uri.toString();
    }

    public static PlaywrightConnection connect(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalStateException(NO_ENDPOINT);
        String key = endpoint.trim();
        PlaywrightConnection current = OPEN.get(key);
        if (current != null && current.browser.isConnected()) return current;
        int port = portOf(key);
        synchronized (OPEN) {
            current = OPEN.get(key);
            if (current != null && current.browser.isConnected()) return current;
            if (port > 0) DebugPortOwner.attach(port, "playwright", Path.of("").toAbsolutePath());
            Playwright playwright = null;
            try {
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(BrowserVersionResolver.playwrightEnv()));
                Browser browser = playwright.chromium().connectOverCDP(key, new BrowserType.ConnectOverCDPOptions().setTimeout(15_000));
                BrowserLog.info("playwright", "connectOverCDP " + redact(key));
                PlaywrightConnection made = new PlaywrightConnection(key, playwright, browser, port);
                OPEN.put(key, made);
                return made;
            } catch (RuntimeException failure) {
                if (port > 0) DebugPortOwner.release(port, "playwright");
                if (playwright != null) playwright.close();
                throw new IllegalStateException("error: Playwright could not attach over CDP to " + redact(key) + ": "
                        + firstLine(failure.getMessage()) + "\nfix: check that a Chromium is listening there (curl <endpoint>/json/version); "
                        + "pickleball does not launch a Playwright browser.", failure);
            }
        }
    }

    /** Port of an {@code http://host:port} or {@code host:port} endpoint. Zero when the endpoint has none. */
    static int portOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return -1;
        String text = endpoint.trim();
        try {
            URI uri = URI.create(text.contains("://") ? text : "http://" + text);
            return uri.getPort();
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    /** Strips query strings and user info so a token in an endpoint URL never reaches a log. */
    public static String redact(String endpoint) {
        if (endpoint == null) return "";
        String out = endpoint.replaceAll("(?i)([?&](token|key|secret|auth)[^=]*=)[^&]*", "$1<redacted>");
        return out.replaceAll("//[^/@]+@", "//<redacted>@");
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    public String endpoint() {
        return endpoint;
    }

    public Browser browser() {
        return browser;
    }

    public Playwright playwright() {
        return playwright;
    }

    /** Every page in every context, in order. */
    public List<Page> pages() {
        List<Page> out = new ArrayList<>();
        for (BrowserContext context : browser.contexts()) out.addAll(context.pages());
        return out;
    }

    /** The page whose URL equals {@code url}, else the first page, else a new page in the default context. */
    public Page page(String url) {
        List<Page> pages = pages();
        if (url != null) for (Page page : pages) if (url.equals(page.url())) return page;
        if (!pages.isEmpty()) return pages.get(0);
        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        return context.newPage();
    }

    @Override
    public void close() {
        OPEN.remove(endpoint, this);
        if (port > 0) DebugPortOwner.release(port, "playwright");
        try {
            playwright.close();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    public static void closeAll() {
        for (PlaywrightConnection connection : List.copyOf(OPEN.values())) connection.close();
    }

    /** A typed {@link PlaywrightAdapter.Session} over a real Page; handles are Playwright selectors. */
    public static PlaywrightAdapter.Session session(Page page) {
        return new PageSession(page);
    }

    /** css, xpath, text, id, name, role, and raw Playwright selectors to one Playwright selector string. */
    static String selector(String using, String value) {
        String kind = using == null ? "css" : using.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "css", "css selector" -> "css=" + value;
            case "xpath" -> "xpath=" + value;
            case "text", "link text" -> "text=" + value;
            case "id" -> "id=" + value;
            case "name" -> "css=[name=\"" + value.replace("\"", "\\\"") + "\"]";
            case "role" -> "role=" + value;
            case "playwright", "locator", "pw" -> value;
            default -> kind + "=" + value;
        };
    }

    static final class PageSession implements PlaywrightAdapter.Session {
        private final Page page;
        private CDPSession cdp;

        PageSession(Page page) {
            this.page = page;
        }

        @Override
        public Object evaluate(String worldName, String fn, Object[] args) {
            if (args == null || args.length == 0) return page.evaluate(fn);
            return page.evaluate(fn, args.length == 1 ? args[0] : List.of(args));
        }

        @Override
        public void addInitScript(String script) {
            page.addInitScript(script);
        }

        @Override
        public void exposeBinding(String name, BindingListener listener) {
            page.exposeBinding(name, (source, values) -> {
                listener.onEvent(name, values == null || values.length == 0 ? "" : String.valueOf(values[0]));
                return null;
            });
        }

        @Override
        public byte[] screenshot(Map<String, ?> options) {
            Page.ScreenshotOptions shot = new Page.ScreenshotOptions().setType(ScreenshotType.PNG);
            if (options != null && Boolean.TRUE.equals(options.get("fullPage"))) shot.setFullPage(true);
            return page.screenshot(shot);
        }

        @Override
        public FrameNode frames() {
            return node(page.mainFrame(), null);
        }

        private FrameNode node(Frame frame, String parent) {
            String id = frame.name() == null || frame.name().isBlank() ? (parent == null ? "main" : frame.url()) : frame.name();
            List<FrameNode> children = new ArrayList<>();
            for (Frame child : frame.childFrames()) children.add(node(child, id));
            return new FrameNode(id, parent, frame.url(), children);
        }

        @Override
        public synchronized Object send(String method, Map<String, ?> params) {
            if (cdp == null) cdp = page.context().newCDPSession(page);
            JsonObject args = params == null ? null : JsonParser.parseString(new com.google.gson.Gson().toJson(params)).getAsJsonObject();
            JsonObject result = args == null ? cdp.send(method) : cdp.send(method, args);
            try {
                return JsonRpc.MAPPER.readValue(result.toString(), LinkedHashMap.class);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("error: CDP result was not JSON: " + failure.getMessage(), failure);
            }
        }

        @Override
        public List<String> find(String using, String value) {
            String selector = selector(using, value);
            Locator locator = page.locator(selector);
            int count = locator.count();
            List<String> handles = new ArrayList<>(count);
            for (int i = 0; i < count; i++) handles.add(selector + " >> nth=" + i);
            return handles;
        }

        @Override
        public void click(String handle) {
            page.locator(handle).click();
        }

        @Override
        public void type(String handle, String text) {
            page.locator(handle).fill(text);
        }
    }
}
