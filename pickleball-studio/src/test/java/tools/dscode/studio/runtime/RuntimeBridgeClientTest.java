package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeBridgeClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mappingPutPreservesJsonLiteralTypes() throws Exception {
        List<Object> values = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/mappings/put", exchange -> {
            try (exchange) {
                Map<?, ?> request = json.readValue(exchange.getRequestBody(), Map.class);
                values.add(request.get("value"));
                byte[] response = "{\"status\":\"SUCCESS\",\"value\":null,\"error\":null,\"runtime\":null}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();

        try {
            RuntimeBridgeClient client = client(server, List.of("mapping_put"));

            client.mappingPut("scenario", "OVERRIDE", "value", "\"READY\"", 5);
            client.mappingPut("scenario", "OVERRIDE", "value", "3", 5);
            client.mappingPut("scenario", "OVERRIDE", "value", "true", 5);
            client.mappingPut("scenario", "OVERRIDE", "value", "{\"a\":1}", 5);
        } finally {
            server.stop(0);
        }

        assertEquals("READY", values.get(0));
        assertEquals(3, values.get(1));
        assertEquals(true, values.get(2));
        Map<?, ?> object = assertInstanceOf(Map.class, values.get(3));
        assertEquals(1, object.get("a"));
    }

    @Test
    void eventReadPreservesCursorAndScenarioFilter() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            try (exchange) {
                query.set(exchange.getRequestURI().getRawQuery());
                byte[] response = """
                        {
                          "events": [{
                            "sequence": 8,
                            "timestamp": "2026-08-16T00:00:00Z",
                            "threadId": 12,
                            "scenarioId": "scenario id",
                            "scenarioName": "Example",
                            "hook": "BEFORE_STEP",
                            "signature": "step",
                            "stepText": "Given example",
                            "phraseText": null
                          }],
                          "nextSequence": 8,
                          "earliestAvailableSequence": 1,
                          "latestSequence": 8,
                          "gap": false,
                          "hasMore": false
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();

        RuntimeEventPage page;
        try {
            page = client(server, List.of("events"))
                    .events("scenario id", 7L, 25);
        } finally {
            server.stop(0);
        }

        assertTrue(query.get().contains("scenarioId=scenario+id"), query.get());
        assertTrue(query.get().contains("afterSequence=7"), query.get());
        assertTrue(query.get().contains("limit=25"), query.get());
        assertEquals(8L, page.nextSequence());
        assertEquals(1, page.events().size());
        assertEquals("BEFORE_STEP", page.events().getFirst().hook());
        assertEquals("scenario id", page.events().getFirst().scenarioId());
    }

    private static RuntimeBridgeClient client(
            HttpServer server,
            List<String> capabilities
    ) {
        return new RuntimeBridgeClient(
                new RuntimeBridgeDescriptor(
                        1,
                        "session",
                        "runtime",
                        1L,
                        "127.0.0.1",
                        server.getAddress().getPort(),
                        "now",
                        capabilities
                ),
                "token"
        );
    }
}
