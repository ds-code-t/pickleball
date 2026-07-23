package com.example.pickleball.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback-only server that serves the test site and exposes local JSON endpoints.
 */
public final class LocalTestSite implements AutoCloseable {

    private static final String SITE_ROOT = "/site";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".html", "text/html; charset=UTF-8"),
            Map.entry(".css", "text/css; charset=UTF-8"),
            Map.entry(".js", "text/javascript; charset=UTF-8"),
            Map.entry(".json", "application/json; charset=UTF-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".ico", "image/x-icon")
    );

    private final HttpServer server;
    private final ExecutorService executor;

    private LocalTestSite(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static LocalTestSite start(int port) {
        try {
            InetSocketAddress address =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port);

            HttpServer server = HttpServer.create(address, 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "pickleball-local-test-site");
                thread.setDaemon(true);
                return thread;
            });

            server.setExecutor(executor);

            // HttpServer uses the longest matching context.
            server.createContext("/api/", LocalTestSite::handleApiRequest);
            server.createContext("/", LocalTestSite::handleStaticRequest);

            server.start();
            return new LocalTestSite(server, executor);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not start the local test site at http://127.0.0.1:" + port,
                    exception
            );
        }
    }

    private static void handleApiRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = Objects.requireNonNullElse(exchange.getRequestURI().getPath(), "");

            if ("/api/service-calls/inspect".equals(path)) {
                if (!allowMethods(exchange, method, "GET", "POST", "PUT", "PATCH")) {
                    return;
                }

                Map<String, String> query = parseQuery(exchange.getRequestURI());
                int responseStatus = responseStatus(query.get("status"));
                String requestBody = readRequestBody(exchange);
                String client = Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("X-Test-Client"),
                        ""
                );
                String traceId = Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("X-Test-Trace"),
                        ""
                );
                String cookie = Objects.requireNonNullElse(
                        exchange.getRequestHeaders().getFirst("Cookie"),
                        ""
                );

                String response = """
                        {
                          "status": %d,
                          "method": %s,
                          "include": %s,
                          "mode": %s,
                          "client": %s,
                          "traceId": %s,
                          "cookie": %s,
                          "body": %s
                        }
                        """.formatted(
                        responseStatus,
                        jsonString(method),
                        jsonString(query.getOrDefault("include", "")),
                        jsonString(query.getOrDefault("mode", "")),
                        jsonString(client),
                        jsonString(traceId),
                        jsonString(cookie),
                        jsonBodyOrString(requestBody)
                );

                exchange.getResponseHeaders().set(
                        "X-Service-Call-Test",
                        "inspect"
                );
                sendJson(exchange, responseStatus, response, method);
                return;
            }

            if (path.startsWith("/api/service-calls/no-content/")) {
                if (!allowMethods(exchange, method, "DELETE")) {
                    return;
                }

                String itemId = path.substring(
                        "/api/service-calls/no-content/".length()
                );
                if (itemId.isBlank() || itemId.contains("/")) {
                    sendJson(exchange, 404, """
                            {"error":"Item not found"}
                            """, method);
                    return;
                }

                exchange.getResponseHeaders().set(
                        "X-Service-Call-Test",
                        "no-content"
                );
                exchange.getResponseHeaders().set("X-Deleted-Item", itemId);
                sendNoContent(exchange);
                return;
            }

            if ("/api/health".equals(path)) {
                if (!allowMethods(exchange, method, "GET", "HEAD")) {
                    return;
                }

                sendJson(
                        exchange,
                        200,
                        """
                        {"status":"UP","service":"pickleball-local"}
                        """,
                        method
                );
                return;
            }

            if (path.startsWith("/api/users/")) {
                if (!allowMethods(exchange, method, "GET")) {
                    return;
                }

                String id = path.substring("/api/users/".length());
                if (id.isBlank() || id.contains("/")) {
                    sendJson(exchange, 404, """
                            {"error":"User not found"}
                            """, method);
                    return;
                }

                Map<String, String> query = parseQuery(exchange.getRequestURI());
                String include = query.getOrDefault("include", "");
                String client = exchange.getRequestHeaders()
                        .getFirst("X-Test-Client");

                String response = """
                        {
                          "id": %s,
                          "include": %s,
                          "client": %s
                        }
                        """.formatted(
                        jsonString(id),
                        jsonString(include),
                        jsonString(Objects.requireNonNullElse(client, ""))
                );

                sendJson(exchange, 200, response, method);
                return;
            }

            if ("/api/echo".equals(path)) {
                if (!allowMethods(exchange, method, "POST", "PUT", "PATCH")) {
                    return;
                }

                String requestBody = readRequestBody(exchange);
                String jsonBody = requestBody.isBlank() ? "null" : requestBody;

                String response = """
                        {
                          "method": %s,
                          "body": %s
                        }
                        """.formatted(jsonString(method), jsonBody);

                sendJson(exchange, 200, response, method);
                return;
            }

            if (path.startsWith("/api/status/")) {
                if (!allowMethods(exchange, method, "GET")) {
                    return;
                }

                String rawStatus = path.substring("/api/status/".length());
                try {
                    int status = Integer.parseInt(rawStatus);
                    if (status < 100 || status > 599) {
                        throw new NumberFormatException("Out of range");
                    }

                    sendJson(
                            exchange,
                            status,
                            """
                            {"status":%d}
                            """.formatted(status),
                            method
                    );
                } catch (NumberFormatException exception) {
                    sendJson(exchange, 400, """
                            {"error":"Invalid status code"}
                            """, method);
                }
                return;
            }

            sendJson(exchange, 404, """
                    {"error":"Endpoint not found"}
                    """, method);
        }
    }

    private static void handleStaticRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String requestPath = normalizedRequestPath(exchange.getRequestURI());
            if (requestPath == null) {
                sendText(exchange, 400, "Bad request", method);
                return;
            }

            String classpathResource = SITE_ROOT + requestPath;
            byte[] content = readResource(classpathResource);

            if (content == null) {
                sendText(exchange, 404, "Not found", method);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", contentType(requestPath));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, content.length);

            if (!"HEAD".equals(method)) {
                try (OutputStream responseBody = exchange.getResponseBody()) {
                    responseBody.write(content);
                }
            }
        }
    }

    private static boolean allowMethods(
            HttpExchange exchange,
            String actualMethod,
            String... allowedMethods
    ) throws IOException {
        boolean allowed = Arrays.stream(allowedMethods)
                .anyMatch(actualMethod::equals);

        if (allowed) {
            return true;
        }

        exchange.getResponseHeaders().set("Allow", String.join(", ", allowedMethods));
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();

        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }

        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";
            values.put(key, value);
        }

        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static int responseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return 200;
        }

        try {
            int status = Integer.parseInt(rawStatus);
            if (status < 100 || status > 599) {
                throw new NumberFormatException("Out of range");
            }
            return status;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "The service-call status query parameter must be between 100 and 599",
                    exception
            );
        }
    }

    private static String jsonBodyOrString(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return "null";
        }

        String trimmed = requestBody.trim();
        boolean object = trimmed.startsWith("{") && trimmed.endsWith("}");
        boolean array = trimmed.startsWith("[") && trimmed.endsWith("]");
        return object || array ? trimmed : jsonString(trimmed);
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String jsonString(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        return "\"" + escaped + "\"";
    }

    private static void sendJson(
            HttpExchange exchange,
            int status,
            String json,
            String method
    ) throws IOException {
        byte[] content = json.strip().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);

        if (!"HEAD".equals(method)) {
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        }
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1);
    }

    private static String normalizedRequestPath(URI requestUri) {
        String path = Objects.requireNonNullElse(requestUri.getPath(), "/");

        if (path.isBlank() || "/".equals(path)) {
            return "/index.html";
        }

        if (path.endsWith("/")) {
            path += "index.html";
        }

        if (!path.startsWith("/") || path.contains("..") || path.indexOf('\0') >= 0) {
            return null;
        }

        return path;
    }

    private static byte[] readResource(String resourcePath) throws IOException {
        try (InputStream input = LocalTestSite.class.getResourceAsStream(resourcePath)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text,
            String method
    ) throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, content.length);

        if (!"HEAD".equals(method)) {
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        }
    }

    private static String contentType(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);

        return CONTENT_TYPES.entrySet().stream()
                .filter(entry -> lowerPath.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("application/octet-stream");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
