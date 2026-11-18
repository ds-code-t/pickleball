package tools.dscode.control.bridge;

import com.sun.net.httpserver.HttpExchange;
import tools.dscode.control.protocol.SelectorWatchdog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** {@code GET /v1/health}. Answers from the watchdog only, so a stuck selector does not delay it. */
final class BridgeHealth {
    private BridgeHealth() {
    }

    static void write(HttpExchange exchange, String token, SelectorWatchdog watchdog) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            if (!authorized(exchange, token)) {
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            boolean healthy = watchdog == null || !watchdog.unhealthy();
            String reason = healthy || watchdog == null ? "" : watchdog.reason();
            int status = healthy ? 200 : 503;
            send(exchange, status, "{\"healthy\":" + healthy + ",\"reason\":\"" + escape(reason) + "\"}");
        }
    }

    private static boolean authorized(HttpExchange exchange, String token) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || token == null) return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String escape(String reason) {
        if (reason == null || reason.isEmpty()) return "";
        return reason.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
