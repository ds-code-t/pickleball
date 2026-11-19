package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.AgentDriverHooks;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.control.protocol.AgentEvents;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Detached browser host for {@code start}. Binds an ephemeral port, launches Chrome,
 * registers a page driver, and serves {@code GET /health} plus {@code POST /v1/agent/command}.
 * State is {@link LiveHostFile}, not the Workbench worker bridge.
 */
public final class LiveBrowserHost {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean WAS_OK = new AtomicBoolean(false);
    private static final AtomicReference<BrowserSession> CHROME = new AtomicReference<>();
    private static volatile Path PROJECT = Path.of("");
    private static volatile String SESSION_ID = "";

    private LiveBrowserHost() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("pkb_composite", "on");
        if (args.length < 6) {
            System.err.println("error: live browser host needs project, id, url, browser, token, headed");
            System.exit(2);
            return;
        }
        Path project = Path.of(args[0]);
        PROJECT = project;
        String id = args[1];
        SESSION_ID = id;
        String pageUrl = args[2];
        String browser = args[3];
        String token = args[4];
        boolean headed = Boolean.parseBoolean(args[5]);
        String binary = args.length >= 7 ? args[6] : "";
        Files.deleteIfExists(project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("args.txt"));
        CountDownLatch stopped = new CountDownLatch(1);
        HttpServer server = bindLoopback();
        int port = server.getAddress().getPort();
        HttpServer listening = server;
        server.createContext("/health", exchange -> health(exchange, token, headed, CHROME.get(), project, id));
        server.createContext("/v1/agent/command", exchange -> command(exchange, token, project, id));
        server.createContext("/v1/stop", exchange -> {
            if (!authorized(exchange, token)) return;
            send(exchange, 200, Map.of("stopped", true));
            stopped.countDown();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> release(project, CHROME.get(), listening)));
        int code = 0;
        try {
            System.err.println("browser=" + browser + " headed=" + headed);
            pruneProfiles(project, id);
            Path profile = project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("profile");
            Files.createDirectories(profile);
            BrowserSession session = BrowserSession.launch(
                    id, "about:blank", headed, true, profile, List.of(), List.of(),
                    binary == null || binary.isBlank() ? null : binary
            );
            CHROME.set(session);
            boolean navigated = true;
            String navError = "";
            if (pageUrl != null && !pageUrl.isBlank() && !"about:blank".equals(pageUrl)) {
                try {
                    var params = Jsons.object();
                    params.put("url", pageUrl);
                    JsonNode result = session.connection().call("Page.navigate", params, Duration.ofSeconds(3));
                    String errorText = result == null ? "" : result.path("errorText").asText("");
                    if (!errorText.isBlank()) {
                        navigated = false;
                        navError = errorText;
                    }
                } catch (RuntimeException failure) {
                    navigated = false;
                    navError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                }
            }
            boolean pageAttached = attachPage(session);
            long pid = ProcessHandle.current().pid();
            String base = "http://127.0.0.1:" + port;
            LiveHostFile.write(project, new LiveHostFile.Host(
                    base, token, port, pid, session.pid, id, headed, browser, navigated, navError, pageAttached,
                    startMillis(ProcessHandle.current()), startMillis(ProcessHandle.of(session.pid).orElse(null))
            ));
            if (!navigated) System.err.println("error: navigation failed: " + navError);
            stopped.await();
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            System.err.println("error: " + message);
            code = 1;
        } finally {
            release(project, CHROME.get(), server);
        }
        if (code != 0) System.exit(code);
    }

    /** Removes old {@code live-browser/<id>} directories. {@code keepId} null removes every id directory. */
    public static void pruneProfiles(Path project, String keepId) {
        if (project == null) return;
        Path root = project.resolve(".pickleball").resolve("live-browser");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) continue;
                if (keepId != null && keepId.equals(String.valueOf(child.getFileName()))) continue;
                deleteTree(child);
            }
        } catch (IOException ignored) {
            // A leftover profile does not keep the browser up.
        }
    }

    private static boolean attachPage(BrowserSession session) {
        try {
            LiveChromeDriver driver = new LiveChromeDriver(session);
            AgentDriverHooks.template("live");
            AgentDriverHooks.ready(driver);
            AgentDriverHooks.template(null);
            CompositeRuntime.joinBrowser(session.cdp);
            return true;
        } catch (RuntimeException failure) {
            System.err.println("error: page attach " + failure.getMessage());
            AgentDriverHooks.template(null);
            return false;
        }
    }

    private static HttpServer bindLoopback() throws IOException {
        HttpServer server = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            if (server.getAddress().getPort() != 8765) return server;
            server.stop(0);
        }
        return server;
    }

    private static void release(Path project, BrowserSession session, HttpServer server) {
        try {
            AgentDriverHooks.releaseHost();
        } catch (RuntimeException ignored) {
            // Chrome close still runs.
        }
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The process is already leaving.
            }
        }
        LiveHostFile.delete(project);
        if (server != null) {
            try {
                server.stop(0);
            } catch (RuntimeException ignored) {
                // The socket is already closed.
            }
        }
    }

    private static void health(
            HttpExchange exchange,
            String token,
            boolean headed,
            BrowserSession session,
            Path project,
            String sessionId
    ) throws IOException {
        if (!authorized(exchange, token)) return;
        boolean chromeAlive = session != null && session.pid > 0
                && ProcessHandle.of(session.pid).map(ProcessHandle::isAlive).orElse(false);
        boolean cdpOk = false;
        String reason = "";
        if (!chromeAlive) {
            reason = "browser_lost";
        } else if (cdpAnswers(session)) {
            cdpOk = true;
        } else {
            reason = "browser_lost";
        }
        boolean ok = chromeAlive && cdpOk;
        if (ok) {
            WAS_OK.set(true);
        } else {
            noteLost(project, sessionId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "down");
        body.put("page", ok ? "attached" : "absent");
        body.put("headed", headed);
        body.put("chromePid", session == null ? 0 : session.pid);
        if (!ok) body.put("reason", reason.isBlank() ? "browser_lost" : reason);
        send(exchange, 200, body);
    }

    private static long startMillis(ProcessHandle handle) {
        if (handle == null) return 0L;
        return handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    private static boolean chromeAlive(BrowserSession session) {
        return session != null && session.pid > 0
                && ProcessHandle.of(session.pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Appends {@code browser_lost} once, on the first true-to-false health transition. */
    private static void noteLost(Path project, String sessionId) {
        if (WAS_OK.compareAndSet(true, false)) {
            AgentEvents.append(project, "browser_lost", sessionId);
        }
    }

    /** Browser domain on a browser socket, or a page-domain call when the socket is already the page. */
    private static boolean cdpAnswers(BrowserSession session) {
        try {
            session.connection().call("Browser.getVersion", Jsons.object(), Duration.ofSeconds(2));
            return true;
        } catch (RuntimeException browserDomain) {
            try {
                var params = Jsons.object();
                params.put("expression", "1");
                params.put("returnByValue", true);
                session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(2));
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private static void command(HttpExchange exchange, String token, Path project, String sessionId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        if (!authorized(exchange, token)) return;
        BrowserSession session = CHROME.get();
        if (!chromeAlive(session)) {
            noteLost(project, sessionId);
            send(exchange, 200, lostCommand());
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        Map<String, String> args = new LinkedHashMap<>();
        JsonNode rawArgs = request.get("args");
        if (rawArgs != null && rawArgs.isObject()) {
            rawArgs.fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText("")));
        }
        List<String> extra = new ArrayList<>();
        JsonNode rawExtra = request.get("extraArgs");
        if (rawExtra != null && rawExtra.isArray()) rawExtra.forEach(node -> extra.add(node.asText("")));
        AgentControl.Result result = AgentControl.execute(
                project,
                request.path("runId").asText(""),
                request.path("op").asText(""),
                args,
                extra,
                request.path("agentId").asText("")
        );
        Map<String, Object> payload = new LinkedHashMap<>(result.body());
        Object error = payload.get("error");
        String errorText = error == null ? "" : String.valueOf(error);
        int exit = result.exit();
        if ((errorText.contains("unknown handle") || errorText.contains("no live browser")) && !cdpAnswers(session)) {
            noteLost(project, sessionId);
            payload.put("ok", false);
            payload.put("error", "no live browser");
            exit = 1;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", exit);
        body.put("body", payload);
        send(exchange, 200, body);
    }

    private static Map<String, Object> lostCommand() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", "no live browser");
        body.put("body", payload);
        return body;
    }

    private static boolean authorized(HttpExchange exchange, String token) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.equals("Bearer " + token)) return true;
        send(exchange, 401, Map.of("error", "unauthorized"));
        return false;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The next start tries again.
                }
            }
        } catch (IOException ignored) {
            // The next start tries again.
        }
    }
}
