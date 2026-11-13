package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session event buffer that {@code observe} reads, matching the Rust Session deque projection. */
class SessionEventsTest {
    private static ObjectNode row(String kind, String method, String url, long at) {
        ObjectNode row = Jsons.object();
        row.put("kind", kind);
        row.put("method", method);
        row.put("url", url);
        row.put("text", "");
        row.put("level", "");
        row.put("at", at);
        return row;
    }

    private static ObjectNode cdp(String method, String params) throws Exception {
        ObjectNode event = Jsons.object();
        event.put("method", method);
        event.set("params", Jsons.MAPPER.readTree(params));
        return event;
    }

    @Test
    void filterKeepsTypeUrlWindowAndNewestLimit() {
        List<ObjectNode> events = List.of(
                row("network", "Network.requestWillBeSent", "https://a.example/old", 1),
                row("network", "Network.requestWillBeSent", "https://b.example/api", 50),
                row("console", "Runtime.consoleAPICalled", "https://b.example/app.js", 60),
                row("log", "Log.entryAdded", "https://b.example/app.js", 70),
                row("network", "Network.responseReceived", "https://b.example/api", 80)
        );
        List<ObjectNode> kept = SessionEvents.filter(events,
                new SessionEvents.Filter("network", "b.example", 40L, 90L, 1));
        assertEquals(1, kept.size());
        assertEquals("Network.responseReceived", kept.get(0).path("method").asText());

        assertEquals(5, SessionEvents.filter(events, new SessionEvents.Filter(null, null, null, null, 0)).size());
        assertEquals(2, SessionEvents.filter(events, new SessionEvents.Filter("NETWORK.request", null, null, null, 0)).size());
        assertEquals(1, SessionEvents.filter(events, new SessionEvents.Filter("console", null, null, null, 0)).size());
        assertEquals(1, SessionEvents.filter(events, new SessionEvents.Filter(null, "A.EXAMPLE", null, null, 0)).size());
        assertEquals(2, SessionEvents.filter(events, new SessionEvents.Filter(null, null, null, 50L, 0)).size());
        assertEquals(3, SessionEvents.filter(events, new SessionEvents.Filter(null, null, null, null, 3)).size());
    }

    @Test
    void projectsNetworkConsoleLogAndNavigationRows() throws Exception {
        SessionEvents buffer = new SessionEvents();
        buffer.accept(cdp("Network.requestWillBeSent",
                "{\"requestId\":\"r1\",\"request\":{\"url\":\"https://api.example/health?ready=1\",\"method\":\"POST\"},\"wallTime\":1700000000.5}"));
        buffer.accept(cdp("Network.responseReceived",
                "{\"requestId\":\"r1\",\"response\":{\"url\":\"https://api.example/health?ready=1\",\"status\":201,\"mimeType\":\"application/json\"}}"));
        buffer.accept(cdp("Network.requestWillBeSent",
                "{\"requestId\":\"r2\",\"request\":{\"url\":\"https://api.example/broken\",\"method\":\"GET\"}}"));
        buffer.accept(cdp("Network.loadingFailed", "{\"requestId\":\"r2\",\"errorText\":\"net::ERR_FAILED\"}"));
        buffer.accept(cdp("Runtime.consoleAPICalled",
                "{\"type\":\"warning\",\"args\":[{\"type\":\"string\",\"value\":\"hello\"},{\"type\":\"number\",\"value\":3}],\"timestamp\":1700000000123.0}"));
        buffer.accept(cdp("Log.entryAdded",
                "{\"entry\":{\"source\":\"network\",\"level\":\"error\",\"text\":\"404\",\"url\":\"https://api.example/x\",\"timestamp\":1700000000456.0}}"));
        buffer.accept(cdp("Page.frameNavigated",
                "{\"frame\":{\"id\":\"F\",\"url\":\"https://app.example/home\",\"mimeType\":\"text/html\"}}"));
        buffer.accept(cdp("Network.requestWillBeSent",
                "{\"requestId\":\"r3\",\"request\":{\"url\":\"data:image/png;base64,AA\",\"method\":\"GET\"}}"));
        buffer.accept(cdp("Runtime.executionContextCreated", "{}"));

        List<ObjectNode> rows = buffer.snapshot();
        assertEquals(7, rows.size(), rows.toString());
        JsonNode request = rows.get(0);
        assertEquals("network", request.path("kind").asText());
        assertEquals("POST", request.path("httpMethod").asText());
        assertEquals(1_700_000_000_500L, request.path("at").asLong());
        assertFalse(request.path("failed").asBoolean());
        JsonNode response = rows.get(1);
        assertEquals(201, response.path("status").asInt());
        assertEquals("application/json", response.path("mime").asText());
        JsonNode failed = rows.get(3);
        assertEquals("Network.loadingFailed", failed.path("method").asText());
        assertTrue(failed.path("failed").asBoolean());
        assertEquals("https://api.example/broken", failed.path("url").asText());
        assertEquals("net::ERR_FAILED", failed.path("text").asText());
        JsonNode console = rows.get(4);
        assertEquals("console", console.path("kind").asText());
        assertEquals("hello 3", console.path("text").asText());
        assertEquals("warning", console.path("level").asText());
        assertEquals(1_700_000_000_123L, console.path("at").asLong());
        JsonNode log = rows.get(5);
        assertEquals("log", log.path("kind").asText());
        assertEquals("error", log.path("level").asText());
        assertEquals(1_700_000_000_456L, log.path("at").asLong());
        JsonNode nav = rows.get(6);
        assertEquals("navigation", nav.path("kind").asText());
        assertEquals("Page.frameNavigated", nav.path("method").asText());
        assertEquals("https://app.example/home", nav.path("url").asText());
        assertTrue(nav.path("top").asBoolean());
        for (JsonNode each : rows) {
            for (String field : List.of("method", "kind", "url", "text", "level", "at", "status", "mime", "failed")) {
                assertTrue(each.has(field), field + " missing in " + each);
            }
        }
        assertNull(SessionEvents.project(Jsons.object().put("method", "DOM.documentUpdated")));
    }

    @Test
    void boundedRingDropsTheOldestRows() throws Exception {
        SessionEvents buffer = new SessionEvents(3);
        for (int i = 0; i < 5; i++) {
            buffer.accept(cdp("Log.entryAdded", "{\"entry\":{\"text\":\"n" + i + "\",\"level\":\"info\"}}"));
        }
        List<ObjectNode> rows = buffer.snapshot();
        assertEquals(3, rows.size());
        assertEquals("n2", rows.get(0).path("text").asText());
        assertEquals("n4", rows.get(2).path("text").asText());
        assertEquals(5, buffer.total());
        assertEquals(2, buffer.dropped());
        assertEquals(2000, SessionEvents.CAPACITY);
    }

    @Test
    void waitReturnsOnTheFirstMatchAndTimesOutToEmpty() throws Exception {
        SessionEvents buffer = new SessionEvents();
        SessionEvents.Filter filter = new SessionEvents.Filter("network", "late", null, null, 100);
        long started = System.nanoTime();
        assertTrue(buffer.read(filter, 0).isEmpty());
        assertTrue(buffer.read(filter, 150).isEmpty());
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 140, "wait-ms did not wait");

        ObjectNode late = cdp("Network.requestWillBeSent",
                "{\"requestId\":\"x\",\"request\":{\"url\":\"https://late.example/\",\"method\":\"GET\"}}");
        Thread feeder = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
            buffer.accept(cdpUnchecked("Log.entryAdded", "{\"entry\":{\"text\":\"noise\"}}"));
            buffer.accept(late);
        });
        long waitStarted = System.nanoTime();
        feeder.start();
        List<ObjectNode> found = buffer.read(filter, 10_000);
        long waited = (System.nanoTime() - waitStarted) / 1_000_000;
        feeder.join();
        assertEquals(1, found.size(), found.toString());
        assertTrue(waited < 5_000, "wait did not return on the first match: " + waited + "ms");
    }

    @Test
    void filterFlagsParseLikeTheRustCli() {
        SessionEvents.Filter defaults = SessionEvents.Filter.of(java.util.Map.of());
        assertEquals(100, defaults.limit());
        assertNull(defaults.kind());
        SessionEvents.Filter set = SessionEvents.Filter.of(java.util.Map.of(
                "type", "network", "url", "x", "since", "5", "until", "9", "limit", "0"));
        assertEquals(new SessionEvents.Filter("network", "x", 5L, 9L, 0), set);
        List<ObjectNode> none = new ArrayList<>();
        assertTrue(SessionEvents.filter(none, set).isEmpty());
    }

    private static ObjectNode cdpUnchecked(String method, String params) {
        try {
            return cdp(method, params);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
