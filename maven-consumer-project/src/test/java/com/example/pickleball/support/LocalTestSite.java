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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback-only test server for the static site and the consumer project's
 * REST and SOAP service-call tests.
 */
public final class LocalTestSite implements AutoCloseable {

    private static final String SITE_ROOT = "/site";
    private static final String ITEMS_PATH = "/api/items";
    private static final String SOAP_PATH = "/soap/calculator";
    private static final Pattern JSON_STRING = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""
    );
    private static final Pattern JSON_NUMBER = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)"
    );
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
            InetSocketAddress address = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    port
            );
            HttpServer server = HttpServer.create(address, 0);
            ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "pickleball-local-test-site");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", LocalTestSite::handleRequest);
            server.start();
            return new LocalTestSite(server, executor);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not start the local test site at http://127.0.0.1:" + port,
                    exception
            );
        }
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = Objects.requireNonNullElse(
                    exchange.getRequestURI().getPath(),
                    "/"
            );

            if (path.equals(ITEMS_PATH) || path.startsWith(ITEMS_PATH + "/")) {
                handleItems(exchange, path);
                return;
            }
            if (SOAP_PATH.equals(path)) {
                handleSoap(exchange);
                return;
            }
            handleStaticSite(exchange);
        }
    }

    private static void handleItems(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String itemId = path.length() > ITEMS_PATH.length()
                ? path.substring(ITEMS_PATH.length() + 1)
                : "";
        String traceId = firstHeader(exchange, "X-Test-Trace");
        exchange.getResponseHeaders().set("X-Test-Trace", traceId);
        exchange.getResponseHeaders().set(
                "Allow",
                "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS"
        );

        if ("OPTIONS".equals(method)) {
            sendBytes(exchange, 204, new byte[0], "application/json; charset=UTF-8", method);
            return;
        }

        switch (method) {
            case "GET", "HEAD" -> {
                if (itemId.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"An item id is required\"}", method);
                    return;
                }
                String include = queryParameters(exchange.getRequestURI()).getOrDefault(
                        "include",
                        "summary"
                );
                String response = "{"
                        + "\"id\":" + json(itemId) + ","
                        + "\"name\":" + json("Test item " + itemId) + ","
                        + "\"include\":" + json(include) + ","
                        + "\"method\":\"GET\","
                        + "\"traceId\":" + json(traceId)
                        + "}";
                sendJson(exchange, 200, response, method);
            }
            case "POST" -> {
                if (!itemId.isBlank()) {
                    sendJson(exchange, 404, "{\"error\":\"POST uses /api/items\"}", method);
                    return;
                }
                String body = readRequestBody(exchange);
                String name = jsonString(body, "name", "Unnamed item");
                String quantity = jsonNumber(body, "quantity", "0");
                String response = "{"
                        + "\"id\":\"created-100\","
                        + "\"name\":" + json(name) + ","
                        + "\"quantity\":" + quantity + ","
                        + "\"method\":\"POST\","
                        + "\"traceId\":" + json(traceId)
                        + "}";
                exchange.getResponseHeaders().set("Location", ITEMS_PATH + "/created-100");
                sendJson(exchange, 201, response, method);
            }
            case "PUT", "PATCH" -> {
                if (itemId.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"An item id is required\"}", method);
                    return;
                }
                String body = readRequestBody(exchange);
                String name = jsonString(body, "name", "Updated item");
                String response = "{"
                        + "\"id\":" + json(itemId) + ","
                        + "\"name\":" + json(name) + ","
                        + "\"method\":" + json(method) + ","
                        + "\"traceId\":" + json(traceId)
                        + "}";
                sendJson(exchange, 200, response, method);
            }
            case "DELETE" -> {
                if (itemId.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"An item id is required\"}", method);
                    return;
                }
                exchange.getResponseHeaders().set("X-Deleted-Item", itemId);
                sendBytes(exchange, 204, new byte[0], "application/json; charset=UTF-8", method);
            }
            default -> sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}", method);
        }
    }

    private static void handleSoap(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
        if ("OPTIONS".equals(method)) {
            sendBytes(exchange, 204, new byte[0], "text/xml; charset=UTF-8", method);
            return;
        }
        if (!"POST".equals(method)) {
            sendText(exchange, 405, "SOAP endpoint requires POST", method);
            return;
        }

        String request = readRequestBody(exchange);
        String operation = firstXmlOperation(request);
        int left = xmlInteger(request, "left");
        int right = xmlInteger(request, "right");
        int result = switch (operation) {
            case "Subtract" -> left - right;
            case "Multiply" -> left * right;
            default -> left + right;
        };

        exchange.getResponseHeaders().set("X-SOAP-Operation", operation);
        String response = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:calc="urn:pickleball:calculator">
                  <soapenv:Body>
                    <calc:%sResponse>
                      <calc:%sResult>%d</calc:%sResult>
                    </calc:%sResponse>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(operation, operation, result, operation, operation);
        sendBytes(
                exchange,
                200,
                response.getBytes(StandardCharsets.UTF_8),
                "text/xml; charset=UTF-8",
                method
        );
    }

    private static void handleStaticSite(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
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

        byte[] content = readResource(SITE_ROOT + requestPath);
        if (content == null) {
            sendText(exchange, 404, "Not found", method);
            return;
        }

        sendBytes(exchange, 200, content, contentType(requestPath), method);
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

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(
            HttpExchange exchange,
            int status,
            String json,
            String method
    ) throws IOException {
        sendBytes(
                exchange,
                status,
                json.getBytes(StandardCharsets.UTF_8),
                "application/json; charset=UTF-8",
                method
        );
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String text,
            String method
    ) throws IOException {
        sendBytes(
                exchange,
                status,
                text.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=UTF-8",
                method
        );
    }

    private static void sendBytes(
            HttpExchange exchange,
            int status,
            byte[] content,
            String contentType,
            String method
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        boolean noBody = "HEAD".equals(method) || status == 204 || status == 304;
        exchange.sendResponseHeaders(status, noBody ? -1 : content.length);
        if (!noBody) {
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        }
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        return Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst(name), "");
    }

    private static String jsonString(String body, String field, String defaultValue) {
        Matcher matcher = Pattern.compile(JSON_STRING.pattern().formatted(Pattern.quote(field)))
                .matcher(body);
        return matcher.find() ? unescapeJson(matcher.group(1)) : defaultValue;
    }

    private static String jsonNumber(String body, String field, String defaultValue) {
        Matcher matcher = Pattern.compile(JSON_NUMBER.pattern().formatted(Pattern.quote(field)))
                .matcher(body);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    private static String firstXmlOperation(String body) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?(Add|Subtract|Multiply)(?:\\s|>)"
        ).matcher(body);
        return matcher.find() ? matcher.group(1) : "Add";
    }

    private static int xmlInteger(String body, String localName) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?" + Pattern.quote(localName)
                        + "(?:\\s[^>]*)?>(-?\\d+)</(?:[A-Za-z_][\\w.-]*:)?"
                        + Pattern.quote(localName) + ">"
        ).matcher(body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String json(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");
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
