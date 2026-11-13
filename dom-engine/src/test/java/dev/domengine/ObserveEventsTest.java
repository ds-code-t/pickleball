package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code observe} without a selector reads the session's CDP event buffer
 * (the Rust observe-buffers contract); with a selector it still waits on a query.
 */
class ObserveEventsTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    private static ObjectNode request(String id, String url, String method) {
        ObjectNode event = Jsons.object();
        event.put("method", "Network.requestWillBeSent");
        ObjectNode params = event.putObject("params");
        params.put("requestId", id);
        params.putObject("request").put("url", url).put("method", method);
        params.put("wallTime", 1_700_000_000.25);
        return event;
    }

    private static FakeCdp fakeWithTraffic() throws Exception {
        FakeCdp cdp = FakeCdp.start();
        cdp.eventsAfterNetworkEnable.add(request("r1", "https://api.example/orders", "POST"));
        ObjectNode console = Jsons.object();
        console.put("method", "Runtime.consoleAPICalled");
        ObjectNode params = console.putObject("params");
        params.put("type", "log");
        params.putArray("args").addObject().put("type", "string").put("value", "ready");
        cdp.eventsAfterNetworkEnable.add(console);
        return cdp;
    }

    @Test
    void sessionObserveReturnsBufferedCdpEventsWithFilters() throws Exception {
        Path dir = Files.createTempDirectory("domengine-observe-events");
        try (FakeCdp cdp = fakeWithTraffic()) {
            CliResult attached = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "obs", "--cdp", cdp.http(), "--i-accept-local-attach",
                    "--session-dir", dir.toString()));
            assertEquals(0, attached.exit(), attached.stderr());
            assertTrue(cdp.methods.contains("Network.enable"), cdp.methods.toString());
            assertTrue(cdp.methods.contains("Page.enable"), cdp.methods.toString());
            assertTrue(cdp.methods.contains("Runtime.enable"), cdp.methods.toString());
            assertTrue(cdp.methods.contains("Log.enable"), cdp.methods.toString());

            CliResult all = DomEngineMain.dispatch(List.of("observe", "--session", "obs", "--wait-ms", "3000", "--type", "console"));
            assertEquals(0, all.exit(), all.stderr());
            JsonNode consoleBody = Jsons.MAPPER.readTree(all.stdout());
            assertEquals("cdp-session", consoleBody.path("source").asText());
            assertEquals(1, consoleBody.path("count").asInt(), all.stdout());
            assertEquals("ready", consoleBody.path("events").get(0).path("text").asText());

            CliResult network = DomEngineMain.dispatch(List.of(
                    "observe", "--session", "obs", "--type", "network", "--url", "orders", "--wait-ms", "3000"));
            assertEquals(0, network.exit(), network.stderr());
            JsonNode row = Jsons.MAPPER.readTree(network.stdout()).path("events").get(0);
            assertEquals("Network.requestWillBeSent", row.path("method").asText());
            assertEquals("https://api.example/orders", row.path("url").asText());
            assertEquals("POST", row.path("httpMethod").asText());
            assertEquals(1_700_000_000_250L, row.path("at").asLong());

            CliResult none = DomEngineMain.dispatch(List.of(
                    "observe", "--session", "obs", "--url", "nothing-like-this", "--wait-ms", "50"));
            assertEquals(0, none.exit(), none.stderr());
            assertEquals(0, Jsons.MAPPER.readTree(none.stdout()).path("count").asInt());

            CliResult window = DomEngineMain.dispatch(List.of(
                    "observe", "--session", "obs", "--type", "network", "--since", "1700000000300"));
            assertEquals(0, Jsons.MAPPER.readTree(window.stdout()).path("count").asInt(), window.stdout());

            CliResult byCdp = DomEngineMain.dispatch(List.of("observe", "--cdp", cdp.http(), "--type", "network"));
            assertEquals(0, byCdp.exit(), byCdp.stderr());
            assertEquals(1, Jsons.MAPPER.readTree(byCdp.stdout()).path("count").asInt(), byCdp.stdout());
        }
    }

    @Test
    void selectorObserveStillWaitsOnTheQuery() throws Exception {
        Path dir = Files.createTempDirectory("domengine-observe-query");
        try (FakeCdp cdp = fakeWithTraffic()) {
            assertEquals(0, DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "q", "--cdp", cdp.http(), "--i-accept-local-attach",
                    "--session-dir", dir.toString())).exit());
            CliResult query = DomEngineMain.dispatch(List.of("observe", "--session", "q", "--css", "#go", "--timeout-ms", "500"));
            assertEquals(0, query.exit(), query.stderr());
            JsonNode body = Jsons.MAPPER.readTree(query.stdout());
            assertTrue(body.path("waited").asBoolean(), query.stdout());
            assertTrue(body.path("nodes").size() > 0, query.stdout());
        }
    }

    @Test
    void observeWithoutSessionOrNewCdpIsAFailureNotEmptyTraffic() throws Exception {
        CliResult bare = DomEngineMain.dispatch(List.of("observe", "--type", "network"));
        assertEquals(2, bare.exit(), bare.stdout());
        CliResult unknown = DomEngineMain.dispatch(List.of("observe", "--cdp", "http://127.0.0.1:9", "--wait-ms", "0"));
        assertEquals(1, unknown.exit(), unknown.stdout());
        assertTrue(unknown.stderr().contains("no history"), unknown.stderr());
        JsonNode caps = Jsons.MAPPER.readTree(DomEngineMain.dispatch(List.of("capabilities")).stdout());
        assertTrue(caps.path("observe").path("events").asBoolean(), caps.toString());
        List<String> commands = new ArrayList<>();
        caps.path("commands").forEach(node -> commands.add(node.asText()));
        assertTrue(commands.contains("observe"));
    }

    @Test
    void workerServeAttachThenObserveOnTheSameProcess() throws Exception {
        Path dir = Files.createTempDirectory("domengine-observe-serve");
        try (FakeCdp cdp = fakeWithTraffic()) {
            String requests = "{\"id\":\"a\",\"argv\":[\"session\",\"create\",\"--id\",\"w\",\"--cdp\",\"" + cdp.http()
                    + "\",\"--i-accept-local-attach\",\"--session-dir\",\"" + dir + "\"]}\n"
                    + "{\"id\":\"b\",\"argv\":[\"observe\",\"--session\",\"w\",\"--type\",\"network\",\"--url\",\"orders\",\"--wait-ms\",\"3000\"]}\n";
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            DomEngineMain.serve(new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)), sink);
            String[] lines = sink.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("{")).toArray(String[]::new);
            assertEquals(2, lines.length, sink.toString(StandardCharsets.UTF_8));
            JsonNode observed = Jsons.MAPPER.readTree(lines[1]);
            assertEquals(0, observed.path("exit").asInt(), lines[1]);
            JsonNode body = Jsons.MAPPER.readTree(observed.path("stdout").asText());
            assertEquals(1, body.path("count").asInt(), body.toString());
            assertEquals("https://api.example/orders", body.path("events").get(0).path("url").asText());
        }
    }
}
