package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.LiveTree;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One named browser session. An attached session detaches on close and does not kill the browser.
 * A launched session owns the Chrome process.
 */
public final class BrowserSession implements AutoCloseable {
    public final String id;
    public final String cdp;
    public final boolean launched;
    public final boolean process;
    public final long pid;
    public final Path profile;
    public final List<String> allow;
    private final Process chrome;
    private final CdpConnection connection;
    private final SessionEvents events;
    private volatile boolean closed;
    private volatile boolean killedProcess;

    private BrowserSession(
            String id,
            String cdp,
            boolean launched,
            boolean process,
            long pid,
            Path profile,
            List<String> allow,
            Process chrome,
            CdpConnection connection,
            SessionEvents events
    ) {
        this.id = id;
        this.cdp = cdp;
        this.launched = launched;
        this.process = process;
        this.pid = pid;
        this.profile = profile;
        this.allow = allow;
        this.chrome = chrome;
        this.connection = connection;
        this.events = events == null ? new SessionEvents() : events;
    }

    public static BrowserSession attach(String id, String cdp, boolean process, List<String> allow) {
        return attach(id, cdp, process, allow, null);
    }

    public static BrowserSession attach(String id, String cdp, boolean process, List<String> allow, String pageId) {
        String browser = normalize(cdp);
        Endpoint endpoint = discover(browser, pageId);
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        SessionEvents events = watch(connection);
        EngineLog.info("session", "attached " + id + " " + browser);
        return new BrowserSession(id, recordedCdp(endpoint), false, process, 0, null, allow, null, connection, events);
    }

    public static BrowserSession reopen(String id, String cdp, boolean launched, boolean process, long pid, String profile) {
        Endpoint endpoint = discover(normalize(cdp));
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        SessionEvents events = watch(connection);
        EngineLog.info("session", "reopened " + id + " " + endpoint.http);
        Path dir = profile == null || profile.isBlank() ? null : Path.of(profile);
        return new BrowserSession(id, recordedCdp(endpoint), launched, process, pid, dir, List.of(), null, connection, events);
    }

    public static BrowserSession launch(String id, String url, boolean headed, boolean process, Path profile, List<String> allow) {
        return launch(id, url, headed, process, profile, allow, List.of());
    }

    public static BrowserSession launch(
            String id,
            String url,
            boolean headed,
            boolean process,
            Path profile,
            List<String> allow,
            List<String> extra
    ) {
        Path dir = profile;
        try {
            if (dir == null) dir = ChromeProcess.newProfile();
            else Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new CdpException("profile: " + failure.getMessage());
        }
        String chrome = findChrome();
        if (chrome == null) {
            throw new CdpException("chrome not found on PATH (google-chrome, chromium, or chromium-browser)");
        }
        ChromeProcess launched = ChromeProcess.start(chrome, dir, !headed, extra, "about:blank");
        Process processHandle = launched.process();
        String http = launched.http();
        Endpoint endpoint = discover(http);
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        SessionEvents events = watch(connection);
        if (url != null && !url.isBlank()) {
            ObjectNode params = Jsons.object();
            params.put("url", url);
            connection.call("Page.navigate", params, Duration.ofSeconds(15));
            awaitDocument(connection, url);
        }
        EngineLog.info("session", "launched " + id + " pid=" + processHandle.pid());
        return new BrowserSession(id, endpoint.http, true, process, processHandle.pid(), dir, allow, processHandle, connection, events);
    }

    public CdpConnection connection() {
        if (closed || connection == null) throw new CdpException("session " + id + " is closed");
        return connection;
    }

    /** CDP events this session's socket has seen since attach or launch. */
    public SessionEvents events() {
        return events;
    }

    /**
     * Starts the session event buffer: the listener goes on before the domains
     * are enabled, so nothing after attach is missed. These are the domains the
     * Rust retained Session enabled for observe ({@code Page}, {@code Runtime},
     * {@code Network}, {@code Log}). A target that refuses a domain still opens;
     * observe then has fewer kinds, which is logged, not hidden.
     */
    static SessionEvents watch(CdpConnection connection) {
        SessionEvents events = new SessionEvents();
        connection.addEventListener(events::accept);
        for (String domain : List.of("Network.enable", "Page.enable", "Runtime.enable", "Log.enable")) {
            try {
                connection.call(domain, Jsons.object(), Duration.ofSeconds(5));
            } catch (CdpException failure) {
                EngineLog.info("observe", domain + " refused: " + failure.getMessage());
            }
        }
        return events;
    }

    public LiveTree tree() {
        return LiveTree.read(connection());
    }

    public boolean isClosed() {
        return closed;
    }

    public ObjectNode record() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", id);
        node.put("cdp", cdp == null ? "" : cdp);
        node.put("launched", launched);
        node.put("process", process);
        if (pid > 0) node.put("pid", pid);
        if (profile != null) node.put("profile", profile.toString());
        var allowlist = node.putArray("allowlist");
        for (String host : allow) allowlist.add(host);
        return node;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
        boolean killed = false;
        if (launched && chrome != null) {
            ChromeProcess.destroyTree(chrome);
            killed = !chrome.isAlive();
        } else if (launched && pid > 0) {
            try {
                ProcessHandle.of(pid).ifPresent(handle -> {
                    handle.destroy();
                    try {
                        handle.onExit().orTimeout(3, TimeUnit.SECONDS).join();
                    } catch (Exception ignored) {
                        handle.destroyForcibly();
                    }
                    if (handle.isAlive()) handle.destroyForcibly();
                });
                killed = ProcessHandle.of(pid).isEmpty() || ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
            } catch (Exception ignored) {
                killed = false;
            }
        }
        killedProcess = killed;
        EngineLog.info("session", "closed " + id + " killed=" + killed);
    }

    public boolean killedBrowser() {
        return killedProcess;
    }

    static String normalize(String cdp) {
        if (cdp == null || cdp.isBlank()) throw new CdpException("cdp endpoint is missing");
        String value = cdp.trim();
        if (value.startsWith("ws://") || value.startsWith("wss://")) return value;
        if (!value.contains("://")) value = "http://" + value;
        return value;
    }

    public static String findChrome() {
        String env = System.getenv("DOMENGINE_CHROME");
        if (env != null && !env.isBlank()) return env;
        for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")) {
            String path = onPath(name);
            if (path != null) return path;
        }
        return null;
    }

    private static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    /**
     * Polls until the current document is {@code complete} and its location is
     * the navigated URL. {@code about:blank} is already complete, so a ready
     * check that ignores the URL returns before the page's listeners exist.
     */
    static void awaitDocument(CdpConnection connection, String url) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        CdpException last = null;
        while (System.nanoTime() < deadline) {
            try {
                ObjectNode params = Jsons.object();
                params.put("expression", "document.readyState + '\\n' + String(location.href)");
                params.put("returnByValue", true);
                JsonNode result = connection.call("Runtime.evaluate", params, Duration.ofSeconds(2));
                if (result.has("exceptionDetails")) {
                    last = new CdpException("document not ready");
                } else {
                    String value = result.path("result").path("value").asText("");
                    int split = value.indexOf('\n');
                    String state = split < 0 ? "" : value.substring(0, split);
                    String href = split < 0 ? "" : value.substring(split + 1);
                    if ("complete".equals(state) && sameDocument(url, href)) {
                        EngineLog.debug("session", "document ready " + href);
                        return;
                    }
                }
            } catch (CdpException failure) {
                last = failure;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CdpException("document ready interrupted");
            }
        }
        throw new CdpException("timed out waiting for document ready: " + url
                + (last == null || last.getMessage() == null ? "" : " (" + last.getMessage() + ")"));
    }

    static boolean sameDocument(String requested, String href) {
        if (requested == null || href == null) return false;
        return stripFragment(requested).equals(stripFragment(href));
    }

    private static String stripFragment(String value) {
        String trimmed = value.trim();
        int hash = trimmed.indexOf('#');
        if (hash >= 0) trimmed = trimmed.substring(0, hash);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    /**
     * Endpoint a session reports as {@code cdp}. A session attached to one page
     * target keeps that page's devtools URL, so two tabs on one host:port stay
     * two sessions and callers can see which tab is selected. A browser-level
     * attach reports the http base.
     */
    static String recordedCdp(Endpoint endpoint) {
        String socket = endpoint.webSocket;
        if (socket != null && socket.contains("/devtools/page/") && endpoint.pinnedPage) return socket;
        return endpoint.http;
    }

    static Endpoint discover(String cdp) {
        return discover(cdp, null);
    }

    /**
     * A browser-level socket ({@code /devtools/browser/}) is never the connection.
     * {@code DOM.*} does not exist on that target. The page socket comes from
     * {@code /json/list}. {@code pageId} selects that tab; otherwise the first page is used.
     * A {@code /devtools/page/} URL stays that page.
     */
    static Endpoint discover(String cdp, String pageId) {
        if ((cdp.startsWith("ws://") || cdp.startsWith("wss://")) && cdp.contains("/devtools/page/")) {
            String http = "http" + cdp.substring(2);
            int slash = http.indexOf("/devtools");
            if (slash > 0) http = http.substring(0, slash);
            return new Endpoint(http, cdp, true);
        }
        if (cdp.startsWith("ws://") || cdp.startsWith("wss://")) {
            String http = "http" + cdp.substring(2);
            int slash = http.indexOf("/devtools");
            if (slash > 0) http = http.substring(0, slash);
            return discoverHttp(http, pageId);
        }
        return discoverHttp(stripPath(cdp), pageId);
    }

    private static Endpoint discoverHttp(String base, String pageId) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String http = stripPath(base);
        try {
            HttpRequest version = HttpRequest.newBuilder(URI.create(http + "/json/version")).GET().timeout(Duration.ofSeconds(5)).build();
            client.send(version, HttpResponse.BodyHandlers.ofString());
            HttpRequest list = HttpRequest.newBuilder(URI.create(http + "/json/list")).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> listBody = client.send(list, HttpResponse.BodyHandlers.ofString());
            JsonNode pages = Jsons.MAPPER.readTree(listBody.body());
            String pageSocket = null;
            boolean wanted = pageId != null && !pageId.isBlank();
            if (pages.isArray()) {
                for (JsonNode page : pages) {
                    if (!"page".equals(page.path("type").asText())) continue;
                    String socket = page.path("webSocketDebuggerUrl").asText("");
                    if (socket.isBlank() || socket.contains("/devtools/browser/")) continue;
                    String id = page.path("id").asText("");
                    if (wanted) {
                        if (pageId.equals(id) || socket.contains("/devtools/page/" + pageId)) {
                            pageSocket = socket;
                            break;
                        }
                        continue;
                    }
                    pageSocket = socket;
                    break;
                }
            }
            if (pageSocket == null || pageSocket.isBlank() || pageSocket.contains("/devtools/browser/")) {
                throw new CdpException(wanted
                        ? "cdp endpoint has no page target " + pageId
                        : "cdp endpoint has no page target");
            }
            EngineLog.info("cdp", "discovered " + http + (wanted ? " page=" + pageId : ""));
            return new Endpoint(http, pageSocket, wanted);
        } catch (CdpException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CdpException("cdp discover interrupted");
        } catch (Exception failure) {
            throw new CdpException("cdp discover failed: " + failure.getMessage());
        }
    }

    private static String stripPath(String cdp) {
        String value = cdp;
        int scheme = value.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = value.indexOf('/', start);
        if (slash > 0) value = value.substring(0, slash);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? value : "http://" + value;
    }

    public record Endpoint(String http, String webSocket, boolean pinnedPage) {
    }
}
