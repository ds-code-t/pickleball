package com.example.pickleball.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback-only server that serves the test site and exposes local REST and
 * SOAP endpoints used by the consumer-project feature tests.
 */
public final class LocalTestSite implements AutoCloseable {

    private static final String SITE_ROOT = "/site";
    private static final Object LOCAL_COORDINATION_LOCK = new Object();
    private static final Path COORDINATION_DIRECTORY = Path.of(
            System.getProperty("java.io.tmpdir"),
            "pickleball-local-test-site"
    );
    private static final long LEASE_POLL_MILLIS = 50L;
    private static final int PROBE_TIMEOUT_MILLIS = 500;

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
    private final int port;
    private final String leaseId;
    private final boolean owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean serverStopped = new AtomicBoolean();

    private LocalTestSite(
            HttpServer server,
            ExecutorService executor,
            int port,
            String leaseId,
            boolean owner
    ) {
        this.server = server;
        this.executor = executor;
        this.port = port;
        this.leaseId = leaseId;
        this.owner = owner;
    }

    /**
     * Starts the site or borrows the already-running site on the same port.
     * Coordination is file-based so this also works across separate JVMs.
     */
    public static LocalTestSite start(int port) {
        String leaseId = ProcessHandle.current().pid() + "|" + UUID.randomUUID();

        try {
            return withPortLock(port, () -> {
                LinkedHashSet<String> leases = readLiveLeases(port);

                if (isExpectedSiteRunning(port)) {
                    leases.add(leaseId);
                    writeLeases(port, leases);
                    return new LocalTestSite(null, null, port, leaseId, false);
                }

                LocalTestSite site = startOwnedSite(port, leaseId);

                try {
                    writeLeases(port, Set.of(leaseId));
                } catch (IOException exception) {
                    site.stopOwnedServer();
                    throw exception;
                }

                return site;
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not start the local test site at http://127.0.0.1:" + port,
                    exception
            );
        }
    }

    private static LocalTestSite startOwnedSite(
            int port,
            String leaseId
    ) throws IOException {
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

        // HttpServer uses the longest matching context.
        server.createContext("/api/", LocalTestSite::handleApiRequest);
        server.createContext("/soap/calculator", LocalTestSite::handleSoapRequest);
        server.createContext("/", LocalTestSite::handleStaticRequest);

        server.start();
        return new LocalTestSite(server, executor, port, leaseId, true);
    }

    private static boolean isExpectedSiteRunning(int port) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + port + "/api/health"
            ).toURL().openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setReadTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "close");

            if (connection.getResponseCode() != 200) {
                return false;
            }

            try (InputStream input = connection.getInputStream()) {
                String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return body.contains("\"service\":\"pickleball-local\"");
            }
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static <T> T withPortLock(
            int port,
            IoCallable<T> callable
    ) throws IOException {
        synchronized (LOCAL_COORDINATION_LOCK) {
            Files.createDirectories(COORDINATION_DIRECTORY);

            try (FileChannel channel = FileChannel.open(
                    lockFile(port),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                return callable.call();
            }
        }
    }

    private static LinkedHashSet<String> readLiveLeases(int port) throws IOException {
        Path file = leaseFile(port);
        LinkedHashSet<String> leases = new LinkedHashSet<>();

        if (!Files.exists(file)) {
            return leases;
        }

        long currentPid = ProcessHandle.current().pid();

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String lease = line.trim();
            int separator = lease.indexOf('|');

            if (separator <= 0) {
                continue;
            }

            try {
                long pid = Long.parseLong(lease.substring(0, separator));
                boolean alive = pid == currentPid
                        || ProcessHandle.of(pid)
                        .map(ProcessHandle::isAlive)
                        .orElse(false);

                if (alive) {
                    leases.add(lease);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed or stale lease records.
            }
        }

        return leases;
    }

    private static void writeLeases(
            int port,
            Set<String> leases
    ) throws IOException {
        Path file = leaseFile(port);

        if (leases.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }

        Files.write(
                file,
                leases,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    private static Path lockFile(int port) {
        return COORDINATION_DIRECTORY.resolve("site-" + port + ".lock");
    }

    private static Path leaseFile(int port) {
        return COORDINATION_DIRECTORY.resolve("site-" + port + ".leases");
    }

    @FunctionalInterface
    private interface IoCallable<T> {
        T call() throws IOException;
    }

    private static void handleApiRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = Objects.requireNonNullElse(exchange.getRequestURI().getPath(), "");

            if ("/api/service-calls/inspect".equals(path)) {
                handleInspect(exchange, method);
                return;
            }

            if ("/api/service-calls/token".equals(path)) {
                handleToken(exchange, method);
                return;
            }

            if ("/api/service-calls/protected".equals(path)) {
                handleProtected(exchange, method);
                return;
            }

            if (path.startsWith("/api/service-calls/no-content/")) {
                handleNoContent(exchange, method, path);
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
                handleUser(exchange, method, path);
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
                handleStatus(exchange, method, path);
                return;
            }

            sendJson(exchange, 404, "{\"error\":\"Endpoint not found\"}", method);
        }
    }

    private static void handleInspect(HttpExchange exchange, String method) throws IOException {
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

        exchange.getResponseHeaders().set("X-Service-Call-Test", "inspect");
        sendJson(exchange, responseStatus, response, method);
    }

    /**
     * Issues a deterministic token for inline-call tests.
     */
    private static void handleToken(HttpExchange exchange, String method) throws IOException {
        if (!allowMethods(exchange, method, "POST")) {
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String client = Objects.requireNonNullElse(
                exchange.getRequestHeaders().getFirst("X-Test-Client"),
                ""
        );
        String scope = query.getOrDefault("scope", "default");
        String token = issuedToken(client, scope);
        String requestBody = readRequestBody(exchange);

        String response = """
                {
                  "accessToken": %s,
                  "tokenType": "Bearer",
                  "scope": %s,
                  "client": %s,
                  "request": %s
                }
                """.formatted(
                jsonString(token),
                jsonString(scope),
                jsonString(client),
                jsonBodyOrString(requestBody)
        );

        exchange.getResponseHeaders().set("X-Service-Call-Test", "inline-token");
        exchange.getResponseHeaders().set("X-Issued-Token", token);
        sendJson(exchange, 200, response, method);
    }

    /**
     * Validates the token generated by {@link #handleToken(HttpExchange, String)}.
     */
    private static void handleProtected(HttpExchange exchange, String method) throws IOException {
        if (!allowMethods(exchange, method, "GET")) {
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String client = Objects.requireNonNullElse(
                exchange.getRequestHeaders().getFirst("X-Test-Client"),
                ""
        );
        String scope = query.getOrDefault("scope", "default");
        String authorization = Objects.requireNonNullElse(
                exchange.getRequestHeaders().getFirst("Authorization"),
                ""
        );
        String token = issuedToken(client, scope);
        String expectedAuthorization = "Bearer " + token;

        if (!expectedAuthorization.equals(authorization)) {
            String response = """
                    {
                      "authorized": false,
                      "client": %s,
                      "scope": %s,
                      "authorization": %s
                    }
                    """.formatted(
                    jsonString(client),
                    jsonString(scope),
                    jsonString(authorization)
            );
            sendJson(exchange, 401, response, method);
            return;
        }

        String response = """
                {
                  "authorized": true,
                  "client": %s,
                  "scope": %s,
                  "authorization": %s,
                  "token": %s
                }
                """.formatted(
                jsonString(client),
                jsonString(scope),
                jsonString(authorization),
                jsonString(token)
        );

        exchange.getResponseHeaders().set("X-Service-Call-Test", "inline-protected");
        sendJson(exchange, 200, response, method);
    }

    private static void handleNoContent(
            HttpExchange exchange,
            String method,
            String path
    ) throws IOException {
        if (!allowMethods(exchange, method, "DELETE")) {
            return;
        }

        String itemId = path.substring("/api/service-calls/no-content/".length());
        if (itemId.isBlank() || itemId.contains("/")) {
            sendJson(exchange, 404, "{\"error\":\"Item not found\"}", method);
            return;
        }

        exchange.getResponseHeaders().set("X-Service-Call-Test", "no-content");
        exchange.getResponseHeaders().set("X-Deleted-Item", itemId);
        sendNoContent(exchange);
    }

    private static void handleUser(
            HttpExchange exchange,
            String method,
            String path
    ) throws IOException {
        if (!allowMethods(exchange, method, "GET")) {
            return;
        }

        String id = path.substring("/api/users/".length());
        if (id.isBlank() || id.contains("/")) {
            sendJson(exchange, 404, "{\"error\":\"User not found\"}", method);
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String include = query.getOrDefault("include", "");
        String client = exchange.getRequestHeaders().getFirst("X-Test-Client");
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
    }

    private static void handleStatus(
            HttpExchange exchange,
            String method,
            String path
    ) throws IOException {
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
                    "{\"status\":%d}".formatted(status),
                    method
            );
        } catch (NumberFormatException exception) {
            sendJson(exchange, 400, "{\"error\":\"Invalid status code\"}", method);
        }
    }

    private static void handleSoapRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

            if ("OPTIONS".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!allowMethods(exchange, method, "POST", "OPTIONS")) {
                return;
            }

            String requestBody = readRequestBody(exchange);
            String operation = firstXmlOperation(requestBody);
            int left = xmlInteger(requestBody, "left");
            int right = xmlInteger(requestBody, "right");
            int result = switch (operation) {
                case "Subtract" -> left - right;
                case "Multiply" -> left * right;
                default -> left + right;
            };

            String response = """
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                      xmlns:calc="urn:pickleball:calculator">
                      <soapenv:Body>
                        <calc:%1$sResponse>
                          <calc:result>%2$d</calc:result>
                        </calc:%1$sResponse>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    """.formatted(operation, result);

            exchange.getResponseHeaders().set("X-SOAP-Operation", operation);
            sendXml(exchange, 200, response, method);
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
        boolean allowed = Arrays.stream(allowedMethods).anyMatch(actualMethod::equals);
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

    private static String issuedToken(String client, String scope) {
        return "inline-" + tokenPart(client) + "-" + tokenPart(scope);
    }

    private static String tokenPart(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "none" : normalized;
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

    private static String firstXmlOperation(String body) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?(Add|Subtract|Multiply)(?:\\s|>)"
        ).matcher(body);
        return matcher.find() ? matcher.group(1) : "Add";
    }

    private static int xmlInteger(String body, String localName) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z_][\\w.-]*:)?"
                        + Pattern.quote(localName)
                        + "(?:\\s[^>]*)?>(-?\\d+)"
        ).matcher(body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String jsonString(String value) {
        String escaped = Objects.requireNonNullElse(value, "")
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
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);

        if (!"HEAD".equals(method)) {
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(content);
            }
        }
    }

    private static void sendXml(
            HttpExchange exchange,
            int status,
            String xml,
            String method
    ) throws IOException {
        byte[] content = xml.strip().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=UTF-8");
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
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            removeLease();

            if (owner) {
                waitForBorrowersAndStop();
            }
        } catch (IOException exception) {
            stopOwnedServer();
            throw new IllegalStateException(
                    "Could not stop the local test site at http://127.0.0.1:" + port,
                    exception
            );
        }
    }

    private void removeLease() throws IOException {
        withPortLock(port, () -> {
            LinkedHashSet<String> leases = readLiveLeases(port);
            leases.remove(leaseId);
            writeLeases(port, leases);
            return null;
        });
    }

    private void waitForBorrowersAndStop() throws IOException {
        boolean interrupted = false;

        try {
            while (true) {
                boolean stopped = withPortLock(port, () -> {
                    LinkedHashSet<String> leases = readLiveLeases(port);
                    leases.remove(leaseId);

                    if (!leases.isEmpty()) {
                        writeLeases(port, leases);
                        return false;
                    }

                    stopOwnedServer();
                    Files.deleteIfExists(leaseFile(port));
                    return true;
                });

                if (stopped) {
                    return;
                }

                try {
                    Thread.sleep(LEASE_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopOwnedServer() {
        if (!owner || !serverStopped.compareAndSet(false, true)) {
            return;
        }

        server.stop(0);
        executor.shutdownNow();
    }
}
