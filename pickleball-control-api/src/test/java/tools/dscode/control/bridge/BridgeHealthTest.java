package tools.dscode.control.bridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.SelectorWatchdog;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeHealthTest {
    @Test
    void healthFailsFastWhenTheSelectorIsSpinning() throws Exception {
        SelectorWatchdog watchdog = new SelectorWatchdog(Duration.ofMillis(20), 80);
        watchdog.observe(0L, 0L);
        assertFalse(watchdog.unhealthy());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/health", exchange -> BridgeHealth.write(exchange, "secret", watchdog));
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/health");
            HttpRequest healthy = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(1))
                    .header("Authorization", "Bearer secret")
                    .GET()
                    .build();
            HttpResponse<String> ok = client.send(healthy, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("\"healthy\":true"));

            watchdog.observe(30_000_000L, 29_000_000L);
            assertTrue(watchdog.unhealthy());
            long started = System.nanoTime();
            HttpResponse<String> failed = client.send(healthy, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(503, failed.statusCode());
            assertTrue(failed.body().contains("\"healthy\":false"));
            assertTrue(failed.body().contains("selector-spin"));
            assertTrue(elapsedMs < 500, "health took " + elapsedMs + "ms");
        } finally {
            server.stop(0);
            watchdog.stop();
        }
    }
}
