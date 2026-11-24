package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.WebDriver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-session capability registry (E1). Records which backends are live (Playwright, WebDriver BiDi, CDP, Selenium
 * classic, with versions) and resolves {@code capability -> backend} in one fallback order:
 * Playwright when the session is Playwright-attached; BiDi (always on Firefox, on Chromium when a BiDi socket exists);
 * CDP (Chromium only, never Firefox); Selenium classic; otherwise {@code error: unsupported}. An unknown-command reply
 * marks that capability unavailable on that backend for this session and the next backend is tried.
 */
public final class BrowserProtocols {
    public enum Backend {
        PLAYWRIGHT, BIDI, CDP, CLASSIC;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Backend of(String value) {
            if (value == null) return null;
            for (Backend backend : values()) if (backend.id().equals(value.trim().toLowerCase(Locale.ROOT))) return backend;
            return null;
        }
    }

    /** One live backend: its version text and endpoint (redacted when printed). */
    public record Live(String version, String endpoint) {
    }

    /** A capability served by one backend. */
    public record Served<T>(Backend via, T value) {
    }

    private static final List<Backend> P_B_C = List.of(Backend.PLAYWRIGHT, Backend.BIDI, Backend.CDP);
    private static final List<Backend> P_C = List.of(Backend.PLAYWRIGHT, Backend.CDP);
    private static final List<Backend> ALL = List.of(Backend.PLAYWRIGHT, Backend.BIDI, Backend.CDP, Backend.CLASSIC);

    /** Capabilities and the backends pickleball has code for, in fallback order. Doctor prints this table. */
    public static final Map<String, List<Backend>> CAPABILITIES = new LinkedHashMap<>();

    static {
        CAPABILITIES.put("events", P_B_C);
        CAPABILITIES.put("dom-mutation", P_B_C);
        CAPABILITIES.put("errors", P_B_C);
        CAPABILITIES.put("console", P_B_C);
        CAPABILITIES.put("logpoint", List.of(Backend.CDP));
        CAPABILITIES.put("net-observe", P_B_C);
        CAPABILITIES.put("net-body", P_B_C);
        CAPABILITIES.put("ws-observe", P_C);
        CAPABILITIES.put("har", P_B_C);
        CAPABILITIES.put("net-route", P_B_C);
        CAPABILITIES.put("ws-route", List.of(Backend.PLAYWRIGHT));
        CAPABILITIES.put("net-conditions", P_B_C);
        CAPABILITIES.put("net-cache", P_B_C);
        CAPABILITIES.put("net-headers", P_B_C);
        CAPABILITIES.put("net-auth", P_B_C);
        CAPABILITIES.put("security", List.of(Backend.CDP));
        CAPABILITIES.put("csp", List.of(Backend.BIDI, Backend.CDP));
        CAPABILITIES.put("emulate", P_B_C);
        CAPABILITIES.put("clock", P_C);
        CAPABILITIES.put("permissions", P_B_C);
        CAPABILITIES.put("contexts", P_B_C);
        CAPABILITIES.put("tabs", ALL);
        CAPABILITIES.put("history", ALL);
        CAPABILITIES.put("print", P_B_C);
        CAPABILITIES.put("storage", P_B_C);
        CAPABILITIES.put("storage-state", P_B_C);
        CAPABILITIES.put("downloads", P_B_C);
        CAPABILITIES.put("upload", ALL);
        CAPABILITIES.put("file-dialog", List.of(Backend.BIDI, Backend.CDP));
        CAPABILITIES.put("drop", P_B_C);
        CAPABILITIES.put("dialogs", ALL);
        CAPABILITIES.put("input", ALL);
        CAPABILITIES.put("find-role", P_B_C);
        CAPABILITIES.put("aria-snap", List.of(Backend.PLAYWRIGHT));
        CAPABILITIES.put("webauthn", P_C);
        CAPABILITIES.put("extension", List.of(Backend.BIDI, Backend.CDP));
        CAPABILITIES.put("webmcp", P_C);
        CAPABILITIES.put("perf", P_B_C);
        CAPABILITIES.put("trace", P_C);
        CAPABILITIES.put("memory", P_C);
        CAPABILITIES.put("audits", List.of(Backend.CDP));
        CAPABILITIES.put("workers", List.of(Backend.BIDI, Backend.CDP));
        CAPABILITIES.put("sw", List.of(Backend.CDP));
        CAPABILITIES.put("storage-events", List.of(Backend.CDP));
        CAPABILITIES.put("nav", P_B_C);
        CAPABILITIES.put("screencast", P_B_C);
        CAPABILITIES.put("share-browser", List.of(Backend.PLAYWRIGHT));
        CAPABILITIES.put("audit", List.of(Backend.CDP, Backend.PLAYWRIGHT));
    }

    private final String browser;
    private final String browserVersion;
    private final boolean playwrightAttached;
    private final EnumMap<Backend, Live> live = new EnumMap<>(Backend.class);
    private final Map<String, Set<Backend>> unavailable = new ConcurrentHashMap<>();

    public BrowserProtocols(String browser, String browserVersion, boolean playwrightAttached) {
        this.browser = browser == null || browser.isBlank() ? "unknown" : browser.trim().toLowerCase(Locale.ROOT);
        this.browserVersion = browserVersion == null ? "" : browserVersion;
        this.playwrightAttached = playwrightAttached;
    }

    public BrowserProtocols live(Backend backend, String version, String endpoint) {
        if (backend == Backend.CDP && firefox()) return this;
        live.put(backend, new Live(version == null ? "" : version, endpoint));
        return this;
    }

    public String browser() {
        return browser;
    }

    public String browserVersion() {
        return browserVersion;
    }

    public boolean firefox() {
        return browser.contains("firefox");
    }

    public boolean isLive(Backend backend) {
        return live.containsKey(backend);
    }

    public Live liveInfo(Backend backend) {
        return live.get(backend);
    }

    /** Playwright is first only when the session is Playwright-attached ({@code pkb_protocol=playwright} or attach). */
    public boolean playwrightAttached() {
        return playwrightAttached;
    }

    public void markUnavailable(String capability, Backend backend) {
        unavailable.computeIfAbsent(capability, key -> ConcurrentHashMap.newKeySet()).add(backend);
    }

    /** Backends to try for a capability, in order. {@code via} is auto, or one backend id. */
    public List<Backend> order(String capability, String via) {
        List<Backend> implemented = CAPABILITIES.getOrDefault(capability, List.of());
        Set<Backend> down = unavailable.getOrDefault(capability, Set.of());
        Backend forced = via == null || via.isBlank() || "auto".equalsIgnoreCase(via) ? null : Backend.of(via);
        List<Backend> out = new ArrayList<>();
        for (Backend backend : ALL) {
            if (!implemented.contains(backend) || down.contains(backend) || !live.containsKey(backend)) continue;
            if (backend == Backend.CDP && firefox()) continue;
            if (forced != null) {
                if (backend == forced) out.add(backend);
                continue;
            }
            if (backend == Backend.PLAYWRIGHT && !playwrightAttached) continue;
            out.add(backend);
        }
        return out;
    }

    public Backend resolve(String capability, String via) {
        List<Backend> order = order(capability, via);
        if (order.isEmpty()) throw unsupported(capability, via);
        return order.get(0);
    }

    /** Runs the first backend that serves the capability; unknown commands fall through to the next one. */
    public <T> Served<T> serve(String capability, String via, Function<Backend, T> impl) {
        List<Backend> order = order(capability, via);
        UnsupportedProtocolOperation last = null;
        List<String> why = new ArrayList<>();
        for (Backend backend : order) {
            try {
                return new Served<>(backend, impl.apply(backend));
            } catch (UnsupportedProtocolOperation unsupported) {
                markUnavailable(capability, backend);
                last = unsupported;
                if (!unsupported.detail().isBlank()) why.add(backend.id() + ": " + unsupported.detail());
            }
        }
        IllegalStateException failure = unsupported(capability, via);
        if (!why.isEmpty()) {
            String message = failure.getMessage();
            int fix = message.indexOf("\nfix: ");
            failure = new IllegalStateException(message.substring(0, fix) + " (" + String.join("; ", why) + ")" + message.substring(fix));
        }
        if (last != null) failure.addSuppressed(last);
        throw failure;
    }

    public IllegalStateException unsupported(String capability, String via) {
        String protocol = via == null || via.isBlank() || "auto".equalsIgnoreCase(via) ? "auto" : via.toLowerCase(Locale.ROOT);
        List<Backend> implemented = CAPABILITIES.getOrDefault(capability, List.of());
        String fix;
        if (implemented.isEmpty()) fix = "no backend implements " + capability;
        else if ("cdp".equals(protocol) && firefox()) fix = "Firefox has no CDP; use --via=bidi";
        else if (implemented.equals(List.of(Backend.PLAYWRIGHT))) fix = "use --via=playwright on a Chromium session";
        else fix = "use one of --via=" + String.join("|", implemented.stream().map(Backend::id).toList())
                + " with a live endpoint (doctor --protocols)";
        return new IllegalStateException("error: unsupported: " + capability + " on " + browser + "/" + protocol + "\nfix: " + fix);
    }

    /** One line per backend, then one line per capability with the backend auto picks or "unsupported". */
    public List<String> table() {
        List<String> lines = new ArrayList<>();
        lines.add("browser=" + browser + (browserVersion.isBlank() ? "" : " " + browserVersion));
        for (Backend backend : ALL) {
            Live info = live.get(backend);
            String note = backend == Backend.PLAYWRIGHT && info != null && !playwrightAttached ? " (only with --via=playwright or pkb_protocol=playwright)" : "";
            lines.add("backend." + backend.id() + "=" + (info == null ? "no" : "yes " + info.version() + note));
        }
        for (String capability : CAPABILITIES.keySet()) {
            List<Backend> auto = order(capability, "auto");
            List<Backend> any = new ArrayList<>();
            for (Backend backend : ALL) if (!order(capability, backend.id()).isEmpty()) any.add(backend);
            lines.add("capability." + capability + "=" + (auto.isEmpty() ? (any.isEmpty() ? "unsupported" : "via " + any.get(0).id() + " only")
                    : auto.get(0).id()) + (any.size() > 1 ? " (also " + String.join(",", any.stream().filter(b -> !auto.isEmpty() && b != auto.get(0)).map(Backend::id).toList()) + ")" : ""));
        }
        return lines;
    }

    /** Short doctor line: live backends in fallback order. */
    public String summary() {
        List<String> parts = new ArrayList<>();
        for (Backend backend : ALL) if (live.containsKey(backend)) parts.add(backend.id());
        return browser + ":" + (parts.isEmpty() ? "none" : String.join(",", parts));
    }

    // ---- construction ----

    /** pkb_* setting: system property, then {@code pkb_runvars.<key>}, then env upper-case, then env as written. */
    public static String setting(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) value = System.getProperty("pkb_runvars." + key);
        if (value == null || value.isBlank()) value = System.getenv(key.toUpperCase(Locale.ROOT));
        if (value == null || value.isBlank()) value = System.getenv(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code pkb_bidi}: on unless false/off/no/0. */
    public static boolean bidiEnabled() {
        String value = setting("pkb_bidi");
        if (value == null) return true;
        String v = value.toLowerCase(Locale.ROOT);
        return !(v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0"));
    }

    /** From a Selenium session: its BiDi {@code webSocketUrl} and its CDP debugger endpoint, when published. */
    public static BrowserProtocols forDriver(WebDriver driver) {
        return ProtocolSelector.protocols(driver);
    }

    /** From endpoints only (CLI and protocol host). Probes the CDP endpoint over HTTP/WS and the BiDi socket with session.status. */
    public static BrowserProtocols probe(String cdpEndpoint, String bidiEndpoint) {
        String browser = "unknown";
        String version = "";
        String cdpVersion = null;
        if (cdpEndpoint != null && !cdpEndpoint.isBlank()) {
            try {
                JsonNode info = cdpVersion(cdpEndpoint);
                String product = info.path("Browser").asText(info.path("product").asText(""));
                if (!product.isBlank()) {
                    int slash = product.indexOf('/');
                    String family = slash < 0 ? product : product.substring(0, slash);
                    browser = family.toLowerCase(Locale.ROOT).contains("edg") ? "msedge"
                            : family.toLowerCase(Locale.ROOT).replace("headlesschrome", "chrome");
                    version = slash < 0 ? "" : product.substring(slash + 1);
                    cdpVersion = info.path("Protocol-Version").asText(info.path("protocolVersion").asText("1.3"));
                }
            } catch (RuntimeException ignored) {
                cdpVersion = null;
            }
        }
        String bidiStatus = null;
        if (bidiEndpoint != null && !bidiEndpoint.isBlank()) {
            try (RpcChannel channel = RpcChannel.open(URI.create(bidiEndpoint), RpcChannel.Dialect.BIDI).timeoutMs(3000)) {
                JsonNode status = channel.call("session.status", Map.of());
                bidiStatus = status.path("message").asText("ready");
                if ("unknown".equals(browser)) browser = "firefox";
            } catch (RuntimeException ignored) {
                bidiStatus = null;
            }
        }
        boolean pw = "playwright".equals(ProtocolSelector.configuredProtocol());
        BrowserProtocols protocols = new BrowserProtocols(browser, version, pw);
        if (bidiStatus != null) protocols.live(Backend.BIDI, bidiStatus.isBlank() ? "ready" : bidiStatus, bidiEndpoint);
        if (cdpVersion != null) {
            protocols.live(Backend.CDP, "protocol " + cdpVersion, cdpEndpoint);
            protocols.live(Backend.PLAYWRIGHT, playwrightVersion(), cdpEndpoint);
        }
        return protocols;
    }

    /** {@code /json/version} for an http endpoint, or Browser.getVersion over a ws endpoint. */
    static JsonNode cdpVersion(String endpoint) {
        String trimmed = endpoint.trim();
        if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
            try (RpcChannel channel = RpcChannel.open(URI.create(trimmed), RpcChannel.Dialect.CDP).timeoutMs(3000)) {
                return channel.call("Browser.getVersion", Map.of());
            }
        }
        try {
            String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(base + "/json/version")).timeout(Duration.ofSeconds(3)).build(),
                            HttpResponse.BodyHandlers.ofString());
            return RpcChannel.MAPPER.readTree(response.body());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("error: no DevTools endpoint at " + endpoint, failure);
        }
    }

    static String playwrightVersion() {
        Package pkg = com.microsoft.playwright.Playwright.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return "playwright " + (v == null ? "1.65.1" : v);
    }
}
