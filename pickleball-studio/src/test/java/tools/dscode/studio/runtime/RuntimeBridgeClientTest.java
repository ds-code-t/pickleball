package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
            RuntimeBridgeClient client = new RuntimeBridgeClient(
                    new RuntimeBridgeDescriptor(
                            1,
                            "session",
                            "runtime",
                            1L,
                            "127.0.0.1",
                            server.getAddress().getPort(),
                            "now",
                            List.of("mapping_put")
                    ),
                    "token"
            );

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
}
