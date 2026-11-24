package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.RpcChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * The per-project protocol host. CLI calls are short processes, but routes, intercepts, dialog policy, subscriptions
 * and the event ring must outlive one call, so the first protocol verb starts this host. It listens on 127.0.0.1 only,
 * on a random port, behind a random token in {@code .pickleball/proto/host.json} (owner-only where the OS allows),
 * holds the CDP, BiDi and Playwright connections, and answers one JSON command per request. Stopping it removes every
 * route and mock it installed.
 */
public final class ProtoHost {
    /** One host command. Throw IllegalStateException("error: ...\nfix: ...") for a failure with a fix. */
    @FunctionalInterface
    public interface Handler {
        ObjectNode handle(ProtoHost host, Map<String, String> flags) throws Exception;
    }

    static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

    public static void register(String verb, Handler handler) {
        HANDLERS.put(verb, handler);
    }

    static {
        EventCommands.register();
        DomCommands.register();
        ErrorCommands.register();
    }

    private final Path project;
    private final String cdpEndpoint;
    private final String bidiEndpoint;
    private final String run;
    private final String session;
    private final String token;
    private final BrowserEventHub hub;
    private final List<Runnable> cleanups = new CopyOnWriteArrayList<>();
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private volatile BrowserProtocols protocols;
    private volatile CdpSession cdp;
    private volatile RpcChannel bidi;
    private volatile PwBridge pw;
    private volatile HttpServer server;
    private volatile long lastRequest = System.currentTimeMillis();

    public ProtoHost(Path project, String cdpEndpoint, String bidiEndpoint, String run) {
        this.project = project;
        this.cdpEndpoint = blank(cdpEndpoint);
        this.bidiEndpoint = blank(bidiEndpoint);
        this.run = run == null || run.isBlank() ? "live" : run;
        this.session = "s" + Long.toString(System.currentTimeMillis(), 36);
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
        this.hub = new BrowserEventHub(project, session, this.run);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = flags(args);
        Path project = Path.of(flags.getOrDefault("project", "."));
        ProtoHost host = new ProtoHost(project, flags.get("cdp"), flags.get("bidi"), flags.get("run"));
        host.serve();
    }

    static Map<String, String> flags(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String token : args) {
            if (token == null || !token.startsWith("--")) continue;
            int eq = token.indexOf('=');
            if (eq < 0) out.put(token.substring(2), "true");
            else out.put(token.substring(2, eq), token.substring(eq + 1));
        }
        return out;
    }

    public static Path dir(Path project) {
        return project.resolve(".pickleball").resolve("proto");
    }

    public static Path hostFile(Path project) {
        return dir(project).resolve("host.json");
    }

    void serve() throws IOException {
        Files.createDirectories(dir(project));
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "pkb-proto-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/cmd", this::onRequest);
        http.start();
        server = http;
        ObjectNode info = ProtoJson.obj();
        info.put("port", http.getAddress().getPort()).put("token", token).put("pid", ProcessHandle.current().pid())
                .put("session", session).put("run", run).put("started", OffsetDateTime.now().toString());
        if (cdpEndpoint != null) info.put("cdp", cdpEndpoint);
        if (bidiEndpoint != null) info.put("bidi", bidiEndpoint);
        Path file = hostFile(project);
        Path tmp = file.resolveSibling("host.json.tmp");
        Files.writeString(tmp, info.toString());
        ownerOnly(tmp);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        log("host started session=" + session + " port=" + http.getAddress().getPort());
        long idleMs = BrowserEventHub.intSetting("pkb_proto_host_idle_min", 240) * 60_000L;
        while (server != null) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                break;
            }
            if (System.currentTimeMillis() - lastRequest > idleMs) {
                log("host idle; stopping");
                stop();
            }
        }
        System.exit(0);
    }

    public static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file stays under the user's profile ACLs.
        }
    }

    private void onRequest(HttpExchange exchange) throws IOException {
        lastRequest = System.currentTimeMillis();
        ObjectNode reply;
        int status = 200;
        try {
            if (!token.equals(exchange.getRequestHeaders().getFirst("X-Pkb-Token"))) {
                status = 403;
                reply = ProtoJson.obj().put("ok", false).put("error", "bad token");
            } else {
                JsonNode body = ProtoJson.MAPPER.readTree(exchange.getRequestBody());
                String verb = body.path("verb").asText("");
                Map<String, String> flags = new LinkedHashMap<>();
                body.path("flags").fields().forEachRemaining(e -> flags.put(e.getKey(), e.getValue().asText()));
                reply = dispatch(verb, flags);
            }
        } catch (Exception failure) {
            reply = failure(failure);
        }
        byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public ObjectNode dispatch(String verb, Map<String, String> flags) {
        Handler handler = HANDLERS.get(verb);
        if (handler == null) {
            return ProtoJson.obj().put("ok", false).put("error", "unknown host command " + verb).put("fix", "update pickleball; the host and CLI must match");
        }
        try {
            ObjectNode out = handler.handle(this, flags);
            if (!out.has("ok")) out.put("ok", true);
            log(verb + " ok" + (out.has("via") ? " via=" + out.path("via").asText() : ""));
            return out;
        } catch (Exception failure) {
            log(verb + " failed: " + firstLine(failure.getMessage()));
            return failure(failure);
        }
    }

    static ObjectNode failure(Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        int fix = message.indexOf("\nfix: ");
        String error = (fix < 0 ? message : message.substring(0, fix)).replaceFirst("^error: ", "");
        ObjectNode out = ProtoJson.obj().put("ok", false).put("error", ProtoRedact.text(error));
        out.put("fix", fix < 0 ? "see help for this verb" : message.substring(fix + 6).trim());
        return out;
    }

    static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    // ---- shared state for handlers ----

    public Path project() {
        return project;
    }

    public String session() {
        return session;
    }

    public String run() {
        return run;
    }

    public BrowserEventHub hub() {
        return hub;
    }

    @SuppressWarnings("unchecked")
    public <T> T state(String key, java.util.function.Supplier<T> init) {
        return (T) state.computeIfAbsent(key, k -> init.get());
    }

    public Map<String, Object> stateMap() {
        return state;
    }

    /** Runs when the host stops (routes, intercepts, overrides are removed). */
    public void onStop(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    public synchronized BrowserProtocols protocols() {
        if (protocols == null) {
            BrowserProtocols probed = BrowserProtocols.probe(cdpEndpoint, bidiEndpoint);
            protocols = probed;
        }
        return protocols;
    }

    public boolean hasCdp() {
        return cdpEndpoint != null && !protocols().firefox();
    }

    public synchronized CdpSession cdp() {
        if (cdpEndpoint == null || protocols().firefox()) {
            throw new tools.dscode.browser.UnsupportedProtocolOperation("cdp", "cdp", "no CDP endpoint for this session");
        }
        if (cdp == null) cdp = CdpSession.connect(cdpEndpoint);
        return cdp;
    }

    public synchronized RpcChannel bidi() {
        if (bidiEndpoint == null) throw new tools.dscode.browser.UnsupportedProtocolOperation("bidi", "bidi", "no BiDi socket for this session");
        if (bidi == null) bidi = RpcChannel.open(URI.create(bidiEndpoint), RpcChannel.Dialect.BIDI).timeoutMs(15_000);
        return bidi;
    }

    public BidiModules bidiModules() {
        return BidiModules.over(bidi());
    }

    /** The top-level BiDi browsing context (the first tab, or the one whose URL contains {@code url}). */
    public String bidiContext(String url) {
        JsonNode tree = bidi().call("browsingContext.getTree", Map.of("maxDepth", 0));
        String first = null;
        for (JsonNode context : tree.path("contexts")) {
            String u = context.path("url").asText("");
            if (url != null && !url.isBlank() && u.contains(url)) return context.path("context").asText();
            if (first == null) first = context.path("context").asText();
        }
        if (first == null) throw new IllegalStateException("error: no BiDi browsing context\nfix: open a tab");
        return first;
    }

    public synchronized PwBridge pw() {
        if (cdpEndpoint == null || protocols().firefox()) {
            throw new tools.dscode.browser.UnsupportedProtocolOperation("playwright", "playwright", "Playwright attaches to Chromium over CDP only");
        }
        if (pw == null) pw = new PwBridge(cdpEndpoint);
        return pw;
    }

    public synchronized void stop() {
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (RuntimeException ignored) {
                // Best effort: the browser may already be gone.
            }
        }
        cleanups.clear();
        try {
            Files.deleteIfExists(hostFile(project));
        } catch (IOException ignored) {
            // Next start overwrites it.
        }
        if (pw != null) pw.close();
        if (cdp != null) cdp.close();
        if (bidi != null) bidi.close();
        HttpServer http = server;
        server = null;
        if (http != null) http.stop(0);
        log("host stopped");
    }

    /** {@code .pickleball/logs/proto-YYYY-MM-DD.log}, toggle {@code pkb_proto_log=off}, capped at 2 MB per day. */
    public void log(String line) {
        String toggle = BrowserProtocols.setting("pkb_proto_log");
        if (toggle != null && Set_OFF.contains(toggle.toLowerCase(Locale.ROOT))) return;
        try {
            Path file = project.resolve(".pickleball").resolve("logs").resolve("proto-" + LocalDate.now() + ".log");
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file) && Files.size(file) > 2_000_000) return;
            Files.writeString(file, OffsetDateTime.now() + " " + session + " " + ProtoRedact.text(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging never fails a command.
        }
    }

    private static final java.util.Set<String> Set_OFF = java.util.Set.of("off", "false", "no", "0");
}
