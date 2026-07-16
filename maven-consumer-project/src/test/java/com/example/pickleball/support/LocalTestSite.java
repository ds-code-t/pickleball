package com.example.pickleball.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny loopback-only web server used by the feature tests.
 */
public final class LocalTestSite implements AutoCloseable {
    private static final String PAGE_RESOURCE = "/site/index.html";

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
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
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
                    "Could not start the local test page at http://127.0.0.1:" + port,
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

            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(404, notFound.length);
                if (!"HEAD".equals(method)) {
                    try (OutputStream responseBody = exchange.getResponseBody()) {
                        responseBody.write(notFound);
                    }
                }
                return;
            }

            byte[] page = readPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, page.length);
            if (!"HEAD".equals(method)) {
                try (OutputStream responseBody = exchange.getResponseBody()) {
                    responseBody.write(page);
                }
            }
        }
    }

    private static byte[] readPage() throws IOException {
        try (InputStream input = LocalTestSite.class.getResourceAsStream(PAGE_RESOURCE)) {
            return Objects.requireNonNull(input, "Missing classpath resource: " + PAGE_RESOURCE).readAllBytes();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
