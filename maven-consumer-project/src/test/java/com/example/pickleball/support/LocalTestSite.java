package com.example.pickleball.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback-only static web server for files under src/test/resources/site.
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
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            HttpServer server = HttpServer.create(address, 0);
            ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
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

    private static void sendText(HttpExchange exchange, int status, String text, String method) throws IOException {
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
        server.stop(0);
        executor.shutdownNow();
    }
}
