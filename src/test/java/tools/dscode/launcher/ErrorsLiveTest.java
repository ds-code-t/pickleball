package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E3 live (CDP): page errors and rejections with stacks and a source-mapped location; logpoints as console rows. */
class ErrorsLiveTest {
    static final Map<String, String> FILES = Map.of(
            "/", "<!doctype html><title>errors</title><script src=\"/app.min.js\"></script><script src=\"/lp.js\"></script>",
            "/app.min.js", "window.boom=function(){throw new Error(\"kaboom\")};window.rej=function(){Promise.reject(new Error(\"rejected-x\"))};\n//# sourceMappingURL=app.min.js.map\n",
            // One segment: generated line 1 column 0 -> sources[0] line 10 column 1, names[0].
            "/app.min.js.map", "{\"version\":3,\"file\":\"app.min.js\",\"sources\":[\"src/app.ts\"],\"names\":[\"boom\"],\"mappings\":\"AASAA\"}",
            "/lp.js", "window.add=function(a,b){\n  var s=a+b;\n  return s;\n};\n");
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;

    @BeforeAll
    static void start() throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile"), started);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String text = FILES.getOrDefault(path, "");
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", path.endsWith(".js") ? "application/javascript"
                    : path.endsWith(".map") ? "application/json" : "text/html");
            exchange.sendResponseHeaders(text.isEmpty() ? 404 : 200, text.isEmpty() ? -1 : body.length);
            if (!text.isEmpty()) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        PwCommandLiveTest.agentIn(temp.resolve("errors"), "browser-events", "--stop");
        PwCommandLiveTest.agentIn(temp.resolve("logpoint"), "browser-events", "--stop");
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        if (server != null) server.stop(0);
    }

    private static void eval(Path project, String expr) throws Exception {
        BrowserEventsLiveTest.ok(project, "pw", "page", "eval", "--expr=" + expr, "--target=browser:cdp=" + endpoint);
    }

    @Test
    void covers_command_errors() throws Exception {
        Path project = temp.resolve("errors");
        long cursor = BrowserEventsLiveTest.ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint).path("cursor").asLong();
        BrowserEventsLiveTest.ok(project, "pw", "page", "goto", "--url=" + base + "/", "--target=browser:cdp=" + endpoint);
        eval(project, "setTimeout(()=>window.boom(),0)");
        eval(project, "setTimeout(()=>window.rej(),0)");
        List<JsonNode> rows = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        String since = Long.toString(cursor);
        while (System.currentTimeMillis() < deadline && rows.size() < 2) {
            JsonNode reply = BrowserEventsLiveTest.ok(project, "errors", "--since=" + since, "--wait-ms=1000");
            reply.path("rows").forEach(rows::add);
            since = reply.path("cursor").asText();
        }
        assertTrue(rows.stream().allMatch(r -> "error".equals(r.path("kind").asText())), rows.toString());
        JsonNode thrown = rows.stream().filter(r -> r.path("text").asText().contains("kaboom")).findFirst().orElseThrow(() -> new AssertionError(rows.toString()));
        assertEquals("exception", thrown.path("subkind").asText(), thrown.toString());
        assertTrue(thrown.path("url").asText().endsWith("/app.min.js"), thrown.toString());
        assertEquals(1, thrown.path("line").asInt(), thrown.toString());
        assertTrue(thrown.path("stack").size() >= 1 && thrown.path("stack").get(0).asText().contains("app.min.js"), thrown.toString());
        assertTrue(thrown.path("original").path("source").asText().endsWith("/src/app.ts"), thrown.toString());
        assertEquals(10, thrown.path("original").path("line").asInt(), thrown.toString());
        assertEquals("boom", thrown.path("original").path("name").asText(), thrown.toString());
        JsonNode rejected = rows.stream().filter(r -> r.path("text").asText().contains("rejected-x")).findFirst().orElseThrow(() -> new AssertionError(rows.toString()));
        assertFalse(rejected.path("stack").isEmpty(), rejected.toString());
        Path spill = project.resolve(".pickleball/runs/live/browser-events-" + java.time.LocalDate.now() + ".ndjson");
        assertTrue(java.nio.file.Files.readString(spill).contains("\"original\""), "the spill keeps the mapped location");
    }

    @Test
    void covers_command_logpoint() throws Exception {
        Path project = temp.resolve("logpoint");
        long cursor = BrowserEventsLiveTest.ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint).path("cursor").asLong();
        BrowserEventsLiveTest.ok(project, "pw", "page", "goto", "--url=" + base + "/", "--target=browser:cdp=" + endpoint);
        JsonNode set = BrowserEventsLiveTest.ok(project, "logpoint", "--at=lp.js:2", "--expr=a*10");
        assertEquals("cdp", set.path("via").asText(), set.toString());
        assertTrue(set.path("resolved").asInt() >= 1, set.toString());
        eval(project, "window.add(4,5)");
        List<JsonNode> rows = BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("console"), "--match=logpoint");
        assertTrue(rows.stream().anyMatch(r -> r.path("text").asText().equals("[logpoint lp1] 40")), rows.toString());
        Path spill = project.resolve(".pickleball/runs/live/browser-events-" + java.time.LocalDate.now() + ".ndjson");
        assertTrue(java.nio.file.Files.readString(spill).contains("[logpoint lp1] 40"), "logpoint output is spilled like any console row");
        JsonNode list = BrowserEventsLiveTest.ok(project, "logpoint", "--list");
        assertEquals(1, list.path("logpoints").size(), list.toString());
        JsonNode cleared = BrowserEventsLiveTest.ok(project, "logpoint", "--clear");
        assertTrue(cleared.path("cleared").asInt() >= 1, cleared.toString());
        PwCommandLiveTest.Capture bad = PwCommandLiveTest.agentIn(project, "logpoint", "--at=nowhere");
        assertEquals(1, bad.code());
        assertTrue(bad.err().startsWith("error: logpoint needs --at="), bad.err());
    }
}
