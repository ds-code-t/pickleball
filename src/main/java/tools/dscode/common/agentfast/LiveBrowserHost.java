package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.control.protocol.LiveBridgeFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detached browser host for {@code start}. Binds an ephemeral port, launches Chrome,
 * and serves {@code GET /health} plus {@code POST /v1/agent/command}.
 */
public final class LiveBrowserHost {
    private static final ObjectMapper JSON = new ObjectMapper();

    private LiveBrowserHost() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("error: live browser host needs project, id, url, browser, token, headed");
            System.exit(2);
            return;
        }
        Path project = Path.of(args[0]);
        String id = args[1];
        String pageUrl = args[2];
        String browser = args[3];
        String token = args[4];
        boolean headed = Boolean.parseBoolean(args[5]);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<BrowserSession> chrome = new AtomicReference<>();
        HttpServer server = bindLoopback();
        int port = server.getAddress().getPort();
        HttpServer listening = server;
        server.createContext("/health", exchange -> health(exchange, token, headed, chrome.get()));
        server.createContext("/v1/agent/command", exchange -> command(exchange, token, project));
        server.createContext("/v1/stop", exchange -> {
            if (!authorized(exchange, token)) return;
            send(exchange, 200, Map.of("stopped", true));
            stopped.countDown();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> release(project, chrome.get(), listening)));
        int code = 0;
        try {
            System.err.println("browser=" + browser + " headed=" + headed);
            Path profile = project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("profile");
            Files.createDirectories(profile);
            BrowserSession session = BrowserSession.launch(id, "about:blank", headed, true, profile, List.of());
            chrome.set(session);
            if (pageUrl != null && !pageUrl.isBlank() && !"about:blank".equals(pageUrl)) {
                try {
                    var params = Jsons.object();
                    params.put("url", pageUrl);
                    session.connection().call("Page.navigate", params, Duration.ofSeconds(3));
                } catch (RuntimeException ignored) {
                    // A dead page URL leaves the browser up. start still reports the host healthy.
                }
            }
            long pid = ProcessHandle.current().pid();
            String base = "http://127.0.0.1:" + port;
            writeCliSession(project, base, token, pid, headed, session.pid);
            LiveBridgeFile.write(project, new LiveBridgeFile.Bridge("127.0.0.1", port, token, pid, id));
            stopped.await();
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            System.err.println("error: " + message);
            code = 1;
        } finally {
            release(project, chrome.get(), server);
        }
        if (code != 0) System.exit(code);
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
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The process is already leaving.
            }
        }
        LiveBridgeFile.delete(project);
        if (server != null) {
            try {
                server.stop(0);
            } catch (RuntimeException ignored) {
                // The socket is already closed.
            }
        }
    }

    private static void health(HttpExchange exchange, String token, boolean headed, BrowserSession session) throws IOException {
        if (!authorized(exchange, token)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("headed", headed);
        body.put("chromePid", session == null ? 0 : session.pid);
        send(exchange, 200, body);
    }

    private static void command(HttpExchange exchange, String token, Path project) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST"));
            return;
        }
        if (!authorized(exchange, token)) return;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", result.exit());
        body.put("body", result.body());
        send(exchange, 200, body);
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

    private static void writeCliSession(Path project, String url, String token, long pid, boolean headed, long chromePid) throws IOException {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        Files.createDirectories(file.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("token", token);
        body.put("pid", pid);
        body.put("mode", "cli-session");
        body.put("headed", headed);
        body.put("chromePid", chromePid);
        Files.writeString(file, JSON.writeValueAsString(body) + "\n");
    }
}
