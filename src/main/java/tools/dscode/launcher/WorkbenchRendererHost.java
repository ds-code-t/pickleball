package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.RunView;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-lived process behind {@code open-window}. It starts the Node renderer
 * and writes {@code attach.json} ({@code ui-attach}) so a later command can
 * attach. The controller {@code ui} command is not started.
 */
public final class WorkbenchRendererHost {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(5);

    private final Path project;
    private final String token;
    private final HttpServer http;
    private final WorkbenchUiServer.Handle renderer;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Map<String, String> results = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    private WorkbenchRendererHost(
            Path project,
            String token,
            HttpServer http,
            WorkbenchUiServer.Handle renderer,
            ExecutorService executor
    ) {
        this.project = project;
        this.token = token;
        this.http = http;
        this.renderer = renderer;
        this.executor = executor;
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            System.err.println("error: missing project");
            System.err.println("fix: open-window --run-id");
            System.exit(2);
        }
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            String runId = flag(args, "run-id");
            if (!runId.isBlank()) writeShownRun(project, runId, flag(args, "agent-id"));
            serve(project);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            System.err.println(message);
            System.exit(1);
        }
    }

    static void serve(Path project) throws Exception {
        WorkbenchUiServer.Handle renderer = WorkbenchUiServer.start(project);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String token = token();
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "pickleball-renderer-attach");
            thread.setDaemon(true);
            return thread;
        });
        WorkbenchRendererHost host = new WorkbenchRendererHost(project, token, http, renderer, executor);
        http.createContext("/health", host::health);
        http.createContext("/commands", host::commands);
        http.createContext("/tools", host::tools);
        http.setExecutor(executor);
        http.start();
        try {
            host.writeAttach();
            host.closed.await();
        } finally {
            host.shutdown();
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!loopback(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("mode", "ui-attach");
        body.put("pid", ProcessHandle.current().pid());
        body.put("running", false);
        body.put("sleepGaps", java.util.List.of());
        send(exchange, 200, body);
    }

    private void commands(HttpExchange exchange) throws IOException {
        if (!loopback(exchange)) return;
        if (!authorized(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        if ("/commands".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            Map<String, Object> body = readObject(exchange);
            String op = string(body.get("op"));
            if ("close-window".equals(op)) {
                String payload = stored("close", "CLOSED");
                sendRaw(exchange, 200, payload);
                closed.countDown();
                return;
            }
            if ("show-run".equals(op)) {
                String runId = string(body.get("runId"));
                if (runId.isBlank()) runId = string(body.get("run-id"));
                if (!runId.isBlank()) {
                    String agent = string(body.get("agent"));
                    if (agent.isBlank()) agent = string(body.get("agentId"));
                    try {
                        writeShownRun(project, runId, agent);
                    } catch (RuntimeException failure) {
                        String message = failure.getMessage() == null ? "error: run was not loaded" : failure.getMessage();
                        send(exchange, 400, Map.of("error", message));
                        return;
                    }
                }
                sendRaw(exchange, 200, stored("show", "SHOWN"));
                return;
            }
            Cli cli = cliSession();
            if (cli != null) {
                forward(exchange, cli, body);
                return;
            }
            sendRaw(exchange, 200, stored("local", "NODE"));
            return;
        }
        if (path.startsWith("/commands/") && "GET".equals(exchange.getRequestMethod())) {
            exchange.getRequestBody().readAllBytes();
            String id = path.substring("/commands/".length());
            String stored = results.get(id);
            if (stored != null) {
                sendRaw(exchange, 200, stored);
                return;
            }
            Cli cli = cliSession();
            if (cli != null) {
                forward(exchange, cli, Map.of());
                return;
            }
            send(exchange, 404, Map.of("error", "Unknown command id"));
            return;
        }
        exchange.getRequestBody().readAllBytes();
        send(exchange, 405, Map.of("error", "POST /commands or GET /commands/{id}"));
    }

    private void tools(HttpExchange exchange) throws IOException {
        if (!loopback(exchange)) return;
        if (!authorized(exchange)) return;
        byte[] body = exchange.getRequestBody().readAllBytes();
        Cli cli = cliSession();
        if (cli != null) {
            forwardBytes(exchange, cli, body);
            return;
        }
        send(exchange, 200, Map.of("events", java.util.List.of()));
    }

    private String stored(String id, String playback) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playback", playback);
        result.put("startedTest", false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ack", true);
        payload.put("id", id);
        payload.put("status", "SUCCESS");
        payload.put("result", result);
        String text = JSON.writeValueAsString(payload);
        results.put(id, text);
        return text;
    }

    /**
     * The Node renderer reads this file on each page request. {@code open-window}
     * writes it before the server accepts traffic. {@code show-run} replaces it.
     */
    static void writeShownRun(Path project, String runId, String agentId) throws IOException {
        RunView.Loaded loaded = RunView.load(project, runId);
        Path file = project.resolve(".pickleball").resolve("workbench-ui").resolve("shown-run.json");
        Files.createDirectories(file.getParent());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", loaded.runId());
        payload.put("agentId", agentId == null ? "" : agentId);
        payload.put("record", loaded.recordText());
        Files.writeString(file, JSON.writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private static String flag(String[] args, String name) {
        String prefix = "--" + name + "=";
        if (args == null) return "";
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            if (arg != null && arg.startsWith(prefix)) return arg.substring(prefix.length());
        }
        return "";
    }

    private void writeAttach() throws IOException {
        Path file = PickleballLocalLayout.attachFile(project);
        Files.createDirectories(file.getParent());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", "http://127.0.0.1:" + http.getAddress().getPort());
        payload.put("token", token);
        payload.put("pid", ProcessHandle.current().pid());
        payload.put("project", project.toString());
        payload.put("mode", "ui-attach");
        payload.put("bind", "127.0.0.1");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private void shutdown() {
        http.stop(0);
        executor.shutdownNow();
        renderer.close();
        try {
            Files.deleteIfExists(PickleballLocalLayout.attachFile(project));
        } catch (IOException ignored) {
            // A dead pid is already unhealthy.
        }
    }

    private Cli cliSession() {
        Path file = PickleballLocalLayout.cliSessionState(project);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode root = JSON.readTree(file.toFile());
            long pid = root.path("pid").asLong(0L);
            String url = root.path("url").asText("");
            String sessionToken = root.path("token").asText("");
            if (pid <= 0 || url.isBlank() || sessionToken.isBlank()) return null;
            if (!"cli-session".equals(root.path("mode").asText(""))) return null;
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return null;
            return new Cli(url.endsWith("/") ? url.substring(0, url.length() - 1) : url, sessionToken);
        } catch (IOException failure) {
            return null;
        }
    }

    private void forward(HttpExchange exchange, Cli cli, Map<String, Object> body) throws IOException {
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(body);
        } catch (IOException failure) {
            bytes = new byte[0];
        }
        forwardBytes(exchange, cli, bytes);
    }

    private void forwardBytes(HttpExchange exchange, Cli cli, byte[] body) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        URI uri = URI.create(cli.url + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(FORWARD_TIMEOUT)
                .header("Authorization", "Bearer " + cli.token);
        if ("POST".equals(exchange.getRequestMethod())) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] responseBody = response.body() == null ? new byte[0] : response.body();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            send(exchange, 500, Map.of("error", "interrupted"));
        } catch (IOException failure) {
            send(exchange, 502, Map.of("error", "cli-session unavailable"));
        }
    }

    private boolean loopback(HttpExchange exchange) throws IOException {
        if (exchange.getRemoteAddress() != null
                && exchange.getRemoteAddress().getAddress() != null
                && exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            return true;
        }
        send(exchange, 403, Map.of("error", "loopback only"));
        return false;
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        String header = headers == null ? null : headers.getFirst("Authorization");
        String presented = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).strip();
        }
        String tokenHeader = headers == null ? null : headers.getFirst("X-Workbench-Token");
        if ((presented == null || presented.isBlank()) && tokenHeader != null) presented = tokenHeader.strip();
        if (same(token, presented)) return true;
        send(exchange, 401, Map.of("error", "Missing or invalid Workbench attach token"));
        return false;
    }

    private static boolean same(String expected, String presented) {
        if (expected == null || presented == null || expected.isEmpty() || presented.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) return Map.of();
        Object value = JSON.readValue(bytes, Object.class);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IOException("error: command body must be a JSON object\nfix: POST a JSON object");
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        sendBytes(exchange, status, bytes);
    }

    private static void sendRaw(HttpExchange exchange, int status, String body) throws IOException {
        sendBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String token() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }

    private record Cli(String url, String token) {
    }
}
