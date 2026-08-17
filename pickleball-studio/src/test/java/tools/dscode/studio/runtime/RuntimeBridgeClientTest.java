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

    @Test
    void mappingSnapshotAndRestoreRoundTripStructuredState() throws Exception {
        AtomicReference<Map<?, ?>> snapshotRequest = new AtomicReference<>();
        AtomicReference<Map<?, ?>> restoreRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/mappings/snapshot", exchange -> {
            try (exchange) {
                snapshotRequest.set(json.readValue(exchange.getRequestBody(), Map.class));
                byte[] response = """
                        {
                          "status": "SUCCESS",
                          "snapshot": {
                            "version": 1,
                            "mapReference": "OVERRIDE",
                            "mapType": "OVERRIDE_MAP",
                            "mapClass": "tools.dscode.common.mappings.NodeMap",
                            "dataSources": ["SCENARIO"],
                            "restorable": true,
                            "values": {"status": "before"}
                          },
                          "error": null,
                          "runtime": null
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.createContext("/v1/mappings/restore", exchange -> {
            try (exchange) {
                restoreRequest.set(json.readValue(exchange.getRequestBody(), Map.class));
                byte[] response = "{\"status\":\"SUCCESS\",\"valueType\":\"java.lang.String\",\"valueText\":\"RESTORED\",\"error\":null,\"runtime\":null}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();

        RuntimeMappingStateResult captured;
        RuntimeControlResult restored;
        try {
            RuntimeBridgeClient client = client(
                    server,
                    List.of("mapping_snapshot", "mapping_restore")
            );
            captured = client.mappingSnapshot("scenario", "OVERRIDE", 5);
            restored = client.mappingRestore("scenario", captured.snapshot(), 5);
        } finally {
            server.stop(0);
        }

        assertEquals("SUCCESS", captured.status());
        assertEquals("before", captured.snapshot().values().get("status").asText());
        assertEquals("RESTORED", restored.valueText());
        assertEquals("scenario", snapshotRequest.get().get("scenarioId"));
        assertEquals("OVERRIDE", snapshotRequest.get().get("mapReference"));

        Map<?, ?> restore = restoreRequest.get();
        assertEquals("scenario", restore.get("scenarioId"));
        Map<?, ?> snapshot = assertInstanceOf(Map.class, restore.get("snapshot"));
        assertEquals("OVERRIDE", snapshot.get("mapReference"));
        Map<?, ?> values = assertInstanceOf(Map.class, snapshot.get("values"));
        assertEquals("before", values.get("status"));
    }

    @Test
    void browserEvidenceUsesScenarioTargetAndStructuredPayloads() throws Exception {
        AtomicReference<Map<?, ?>> pageRequest = new AtomicReference<>();
        AtomicReference<Map<?, ?>> screenshotRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/browser/page", exchange -> {
            try (exchange) {
                pageRequest.set(json.readValue(exchange.getRequestBody(), Map.class));
                byte[] response = """
                        {
                          "status":"SUCCESS",
                          "page":{
                            "url":"https://example.test/",
                            "title":"Example",
                            "windowHandle":"one",
                            "windowHandles":["one"],
                            "windowWidth":1280,
                            "windowHeight":720,
                            "pageSource":"<html></html>",
                            "pageSourceTruncated":false
                          },
                          "error":null,
                          "runtime":null
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.createContext("/v1/browser/screenshot", exchange -> {
            try (exchange) {
                screenshotRequest.set(json.readValue(exchange.getRequestBody(), Map.class));
                byte[] response = """
                        {
                          "status":"SUCCESS",
                          "screenshot":{
                            "mimeType":"image/png",
                            "byteSize":3,
                            "base64":"AQID"
                          },
                          "error":null,
                          "runtime":null
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();

        try {
            RuntimeBridgeClient client = client(server, List.of("browser_page", "browser_screenshot"));
            RuntimeBrowserPageResult page = client.browserPage("scenario", 5);
            RuntimeBrowserScreenshotBridgeResult screenshot = client.browserScreenshot("scenario", 5);

            assertEquals("https://example.test/", page.page().url());
            assertEquals("<html></html>", page.page().pageSource());
            assertEquals(3, screenshot.screenshot().byteSize());
            assertEquals("AQID", screenshot.screenshot().base64());
            assertEquals("scenario", pageRequest.get().get("scenarioId"));
            assertEquals("scenario", screenshotRequest.get().get("scenarioId"));
        } finally {
            server.stop(0);
        }
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
