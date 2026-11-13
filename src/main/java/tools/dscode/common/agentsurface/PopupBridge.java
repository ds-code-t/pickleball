package tools.dscode.common.agentsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Loopback HTTP and SSE for one popup host. Token and Host are required. */
public final class PopupBridge implements AutoCloseable {
    public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile boolean pollWarned;

    private final HttpServer server;
    private final String token;
    private final java.nio.file.Path project;
    private final String runId;

    private PopupBridge(HttpServer server, String token, java.nio.file.Path project, String runId) {
        this.server = server;
        this.token = token;
        this.project = project;
        this.runId = runId;
    }

    public static PopupBridge start(java.nio.file.Path project, String runId) throws IOException {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        return start(project, runId, HexFormat.of().formatHex(raw));
    }

    public static PopupBridge start(java.nio.file.Path project, String runId, String token) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        PopupBridge bridge = new PopupBridge(server, token, project, runId);
        server.createContext("/", bridge::handle);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "popup-bridge");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        PopupLog.log(project, runId, "bridge", runId, "listen " + bridge.port());
        return bridge;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port() + "/?t=" + token;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || !host.equals("127.0.0.1:" + port())) {
                send(exchange, 403, "text/plain", "forbidden");
                return;
            }
            String presented = tokenOf(exchange);
            if (!constantTime(presented, token)) {
                send(exchange, 403, "text/plain", "forbidden");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && ("/".equals(path) || path.isEmpty())) {
                String shell = new String(resource("agent-surface-popup.html"), StandardCharsets.UTF_8);
                String query = java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
                shell = shell.replace("assets/agent-surface-popup.css", "assets/agent-surface-popup.css?t=" + query)
                        .replace("assets/agent-surface-popup.js", "assets/agent-surface-popup.js?t=" + query);
                send(exchange, 200, "text/html; charset=utf-8", shell);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/assets/")) {
                byte[] body = asset(path.substring("/assets/".length()));
                if (body == null) {
                    send(exchange, 404, "text/plain", "missing");
                    return;
                }
                send(exchange, 200, contentType(path), body);
                return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                send(exchange, 200, "application/json", "{\"ok\":true}");
                return;
            }
            if ("GET".equals(method) && "/events".equals(path)) {
                events(exchange);
                return;
            }
            if ("GET".equals(method) && "/poll".equals(path)) {
                poll(exchange);
                return;
            }
            if ("POST".equals(method) && "/chat".equals(path)) {
                String text = bodyFields(exchange).getOrDefault("text", "");
                PopupSurfaceBridge.sendChat(project, runId, "run", "human", text);
                send(exchange, 200, "application/json", "{\"ok\":true}");
                return;
            }
            if ("POST".equals(method) && "/input".equals(path)) {
                Map<String, String> fields = bodyFields(exchange);
                Map<String, String> event = new LinkedHashMap<>();
                event.put("type", fields.getOrDefault("type", ""));
                event.put("id", fields.getOrDefault("id", ""));
                event.put("value", fields.getOrDefault("value", ""));
                PopupSurfaceBridge.recordInput(project, runId, event);
                send(exchange, 200, "application/json", "{\"ok\":true}");
                return;
            }
            if ("POST".equals(method) && "/rendered".equals(path)) {
                String line = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PopupSurfaceBridge.appendCapped(PopupLog.popupDir(project, runId).resolve("rendered.jsonl"), line);
                send(exchange, 200, "application/json", "{\"ok\":true}");
                return;
            }
            send(exchange, 404, "text/plain", "missing");
        } catch (Exception failure) {
            try {
                send(exchange, 500, "text/plain", "error");
            } catch (IOException ignored) {
                // The client went away or the status was already sent.
            }
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        long after = longOrZero(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        try (OutputStream out = exchange.getResponseBody()) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                boolean wrote = false;
                for (PopupLedger.Row row : PopupLedger.after(project, runId, after)) {
                    String data = JSON.writeValueAsString(PopupLedger.toMap(row));
                    out.write(("id: " + row.seq() + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    after = row.seq();
                    wrote = true;
                }
                if (wrote && after > 0) return;
                Thread.sleep(40);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private void poll(HttpExchange exchange) throws IOException {
        if (!pollWarned) {
            pollWarned = true;
            PopupLog.warn(project, runId, "sse-unavailable", "client used /poll");
        }
        String raw = query(exchange).getOrDefault("after", "0");
        List<PopupLedger.Row> rows = PopupLedger.after(project, runId, longOrZero(raw));
        send(exchange, 200, "application/json", JSON.writeValueAsString(rows.stream().map(PopupLedger::toMap).toList()));
    }

    private byte[] asset(String name) throws IOException {
        if (name.startsWith("surface/")) {
            String rest = name.substring("surface/".length());
            int dot = rest.lastIndexOf('.');
            if (dot > 0) {
                byte[] served = surface(rest.substring(0, dot), rest.substring(dot + 1));
                if (served != null) return served;
            }
        }
        String safe = name.replace("..", "");
        return resource(safe);
    }

    private static byte[] surface(String kind, String extension) {
        try {
            Class<?> type = Class.forName("tools.dscode.browser.SurfaceAssets");
            String method = "css".equals(extension) ? "css" : "js";
            Object value = type.getMethod(method, String.class).invoke(null, kind);
            return value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
        } catch (ClassNotFoundException failure) {
            return null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static byte[] resource(String name) throws IOException {
        String path = "tools/dscode/common/agentsurface/" + name;
        try (InputStream input = PopupBridge.class.getClassLoader().getResourceAsStream(path)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static void headers(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        return "application/octet-stream";
    }

    private String tokenOf(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Pkb-Popup-Token");
        if (header != null && !header.isBlank()) return header.trim();
        return query(exchange).getOrDefault("t", "");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            int split = part.indexOf('=');
            if (split < 0) continue;
            values.put(decode(part.substring(0, split)), decode(part.substring(split + 1)));
        }
        return values;
    }

    private static Map<String, String> bodyFields(HttpExchange exchange) throws IOException {
        Map<String, String> values = new LinkedHashMap<>(query(exchange));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.startsWith("{")) {
            try {
                var node = JSON.readTree(body);
                node.fieldNames().forEachRemaining(name -> values.put(name, node.path(name).asText("")));
            } catch (IOException ignored) {
                return values;
            }
            return values;
        }
        for (String part : body.split("&")) {
            int split = part.indexOf('=');
            if (split < 0) continue;
            values.put(decode(part.substring(0, split)), decode(part.substring(split + 1)));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static boolean constantTime(String presented, String expected) {
        byte[] left = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        byte[] right = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        int length = Math.max(left.length, right.length);
        byte[] a = java.util.Arrays.copyOf(left, length);
        byte[] b = java.util.Arrays.copyOf(right, length);
        return MessageDigest.isEqual(a, b) && left.length == right.length;
    }

    private static long longOrZero(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }
}
