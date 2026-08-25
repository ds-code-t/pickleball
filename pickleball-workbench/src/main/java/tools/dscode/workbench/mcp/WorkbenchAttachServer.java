package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.workbench.WorkbenchServices;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Localhost-only JSON facade over the same {@link WorkbenchMcpTools} used by
 * stdio MCP. UI mode cannot share process stdout with stdio MCP, so an agent
 * attaches to the visible Workbench through this endpoint.
 */
public final class WorkbenchAttachServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkbenchServices services;
    private final Path projectRoot;
    private final Path stateFile;
    private final String token;
    private final HttpServer http;
    private final WorkbenchMcpTools tools;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WorkbenchAttachServer(
            WorkbenchServices services,
            Path projectRoot,
            Path stateFile,
            String token,
            HttpServer http,
            WorkbenchMcpTools tools,
            ExecutorService executor
    ) {
        this.services = services;
        this.projectRoot = projectRoot;
        this.stateFile = stateFile;
        this.token = token;
        this.http = http;
        this.tools = tools;
        this.executor = executor;
    }

    public static WorkbenchAttachServer start(WorkbenchServices services, Path projectRoot) {
        Objects.requireNonNull(services, "services");
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String token = newToken();
            WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
            Path stateFile = attachStateFile(root);
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "pickleball-workbench-attach");
                thread.setDaemon(true);
                return thread;
            });
            WorkbenchAttachServer server = new WorkbenchAttachServer(
                    services, root, stateFile, token, http, tools, executor
            );
            http.createContext("/health", server::health);
            http.createContext("/lease", server::lease);
            http.createContext("/player", server::player);
            http.createContext("/tools", server::tools);
            http.setExecutor(executor);
            http.start();
            server.writeStateFile();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start the Workbench agent-attach endpoint.", failure);
        }
    }

    public static Path attachStateFile(Path projectRoot) {
        return projectRoot.resolve(".pickleball").resolve("workbench").resolve("attach.json");
    }

    public String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    public Path stateFile() {
        return stateFile;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        http.stop(0);
        executor.shutdownNow();
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException ignored) {
            // Disposable attach state.
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, Map.of(
                "status", "ok",
                "url", url(),
                "pid", ProcessHandle.current().pid()
        ));
    }

    private void lease(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, services.controlLeaseSnapshot());
    }

    private void player(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, services.playerState());
    }

    private void tools(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        String path = exchange.getRequestURI().getPath();
        if ("/tools".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            send(exchange, 200, Map.of("tools", tools.names()));
            return;
        }
        if (!path.startsWith("/tools/") || path.length() <= "/tools/".length()) {
            send(exchange, 404, Map.of("error", "Unknown attach path"));
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "POST a JSON argument object to invoke a tool"));
            return;
        }
        String name = path.substring("/tools/".length());
        Map<String, Object> arguments = readJsonObject(exchange.getRequestBody());
        try {
            Object value = tools.call(name, arguments);
            send(exchange, 200, value);
        } catch (RuntimeException failure) {
            send(exchange, 400, Map.of(
                    "error", failure.getClass().getSimpleName(),
                    "message", failure.getMessage() == null ? failure.toString() : failure.getMessage()
            ));
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String header = firstHeader(exchange.getRequestHeaders(), "Authorization");
        String tokenHeader = firstHeader(exchange.getRequestHeaders(), "X-Workbench-Token");
        String presented = tokenHeader;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).strip();
        }
        if (token.equals(presented)) return true;
        send(exchange, 401, Map.of("error", "Missing or invalid Workbench attach token"));
        return false;
    }

    private void writeStateFile() throws IOException {
        Files.createDirectories(stateFile.getParent());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url());
        payload.put("token", token);
        payload.put("pid", ProcessHandle.current().pid());
        payload.put("project", projectRoot.toString());
        payload.put("mode", "ui-attach");
        payload.put("bind", "127.0.0.1");
        Files.writeString(stateFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }

    private static String firstHeader(Headers headers, String name) {
        if (headers == null) return null;
        String value = headers.getFirst(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(InputStream input) throws IOException {
        byte[] bytes = input.readAllBytes();
        if (bytes.length == 0) return Map.of();
        Object value = JSON.readValue(bytes, Object.class);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Tool arguments must be a JSON object.");
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
