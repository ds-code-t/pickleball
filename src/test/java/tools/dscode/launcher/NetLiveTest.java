package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E4 live (CDP and Playwright): bodies on demand and capped, header redaction, WebSocket rows, HAR, capture from HAR. */
class NetLiveTest {
    static final String PAGE = "<!doctype html><title>net</title><script>"
            + "window.api=()=>fetch('/api/data?q=1',{headers:{'Authorization':'Bearer s3cr3t-token-value'}}).then(r=>r.text());"
            + "window.big=()=>fetch('/api/big').then(r=>r.text());"
            + "window.sock=(u)=>new Promise(res=>{const w=new WebSocket(u);w.onopen=()=>w.send('hello ws');w.onmessage=e=>{res(e.data);w.close();};});"
            + "</script>";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static HttpServer server;
    static ServerSocket wsServer;
    static String base;
    static String endpoint;
    static String wsUrl;
    static final AtomicReference<String> lastAuth = new AtomicReference<>("");

    @BeforeAll
    static void start() throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile"), started);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) lastAuth.set(auth);
            String body;
            String type = "application/json";
            if (path.equals("/api/data")) body = "{\"user\":\"ann\",\"password\":\"hunter2\",\"n\":1}";
            else if (path.equals("/api/big")) body = "{\"blob\":\"" + "x".repeat(4000) + "\"}";
            else {
                body = PAGE;
                type = "text/html";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc123; Path=/");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        wsServer = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        wsUrl = "ws://127.0.0.1:" + wsServer.getLocalPort() + "/echo";
        Thread accept = new Thread(() -> {
            while (!wsServer.isClosed()) {
                try {
                    Socket socket = wsServer.accept();
                    Thread t = new Thread(() -> echo(socket));
                    t.setDaemon(true);
                    t.start();
                } catch (Exception closed) {
                    return;
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
    }

    /** A minimal RFC 6455 echo server: handshake, then text frames back with an echo: prefix. */
    static void echo(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int c = in.read();
                if (c < 0) return;
                head.append((char) c);
            }
            String key = "";
            for (String line : head.toString().split("\r\n")) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                    + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                int opcode = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = in.readNBytes(4);
                byte[] payload = in.readNBytes((int) len);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 8) {
                    out.write(new byte[]{(byte) 0x88, 0});
                    out.flush();
                    return;
                }
                byte[] reply = ("echo:" + new String(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                out.write(0x81);
                out.write(reply.length);
                out.write(reply);
                out.flush();
            }
        } catch (Exception ignored) {
            // The client went away.
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (String name : List.of("bodies", "ws", "har", "pwhar")) PwCommandLiveTest.agentIn(temp.resolve(name), "browser-events", "--stop");
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        if (server != null) server.stop(0);
        if (wsServer != null) wsServer.close();
    }

    private static JsonNode ok(Path project, String... args) throws Exception {
        return BrowserEventsLiveTest.ok(project, args);
    }

    private static void page(Path project, String expr) throws Exception {
        ok(project, "pw", "page", "eval", "--expr=" + expr, "--target=browser:cdp=" + endpoint);
    }

    private static JsonNode finished(List<JsonNode> rows, String suffix, String subkind) {
        return rows.stream().filter(r -> subkind.equals(r.path("subkind").asText()) && r.path("url").asText().contains(suffix))
                .findFirst().orElseThrow(() -> new AssertionError(suffix + " " + subkind + " in " + rows));
    }

    @Test
    void covers_command_net_body() throws Exception {
        Path project = temp.resolve("bodies");
        long cursor = ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint).path("cursor").asLong();
        JsonNode set = ok(project, "net", "--capture-bodies=api", "--max-kb=1");
        assertEquals("api", set.path("captureBodies").asText(), set.toString());
        ok(project, "pw", "page", "goto", "--url=" + base + "/", "--target=browser:cdp=" + endpoint);
        page(project, "window.api()");
        page(project, "window.big()");
        List<JsonNode> rows = BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("net"), "--kinds=net");
        long deadline = System.currentTimeMillis() + 10_000;
        while (rows.stream().filter(r -> "finished".equals(r.path("subkind").asText()) && r.path("url").asText().contains("/api/")).count() < 2
                && System.currentTimeMillis() < deadline) {
            rows = BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("net", "never"), "--kinds=net", "--limit=500");
        }
        JsonNode request = finished(rows, "/api/data", "request");
        assertEquals("[redacted]", request.path("requestHeaders").path("Authorization").asText(), request.toString());
        assertTrue(request.path("redacted").toString().contains("header:authorization"), request.toString());
        assertEquals("Bearer s3cr3t-token-value", lastAuth.get(), "the real header still reached the server");
        JsonNode response = finished(rows, "/api/data", "response");
        assertTrue(response.path("ttfbMs").isNumber() && response.has("remote") && response.has("cache"), response.toString());
        // Chrome strips Set-Cookie from responseReceived (it lives in ExtraInfo); the spill check below proves the cookie never lands.
        assertTrue(response.path("responseHeaders").has("Content-type"), response.toString());
        String id = request.path("requestId").asText();
        PwCommandLiveTest.Capture body = PwCommandLiveTest.agentIn(project, "net-body", "--request-id=" + id);
        assertEquals(0, body.code(), body.err());
        JsonNode read = BrowserEventsLiveTest.JSON.readTree(body.out());
        assertTrue(read.path("body").asText().contains("\"user\":\"ann\""), read.toString());
        assertTrue(read.path("body").asText().contains("[redacted]") && !read.path("body").asText().contains("hunter2"), read.toString());
        String bigId = finished(rows, "/api/big", "request").path("requestId").asText();
        JsonNode big = ok(project, "net-body", "--request-id=" + bigId, "--out=bodies/big.json");
        assertTrue(big.path("truncated").asBoolean(), big.toString());
        assertEquals(1024, Files.size(project.resolve("bodies/big.json")), "capped at --max-kb");
        assertFalse(Files.readString(temp.resolve("bodies").resolve(".pickleball/runs/live/browser-events-" + java.time.LocalDate.now() + ".ndjson"))
                .contains("s3cr3t-token-value"), "the spill never holds the secret header");
        assertFalse(Files.readString(temp.resolve("bodies").resolve(".pickleball/runs/live/browser-events-" + java.time.LocalDate.now() + ".ndjson"))
                .contains("hunter2"), "the spill never holds the secret body field");
    }

    @Test
    void covers_command_net() throws Exception {
        Path project = temp.resolve("ws");
        ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint);
        JsonNode all = ok(project, "net", "--capture-bodies=all", "--max-kb=8");
        assertEquals("all", all.path("captureBodies").asText(), all.toString());
        assertEquals(8, all.path("maxKb").asInt(), all.toString());
        JsonNode none = ok(project, "net", "--capture-bodies=none");
        assertEquals("none", none.path("captureBodies").asText(), none.toString());
        assertTrue(Files.isDirectory(project.resolve(".pickleball")), "the net verb ran against the live host");
    }

    @Test
    void webSocketFramesBecomeWsRows() throws Exception {
        Path project = temp.resolve("ws");
        long cursor = ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint).path("cursor").asLong();
        ok(project, "pw", "page", "goto", "--url=" + base + "/", "--target=browser:cdp=" + endpoint);
        page(project, "window.sock('" + wsUrl + "')");
        List<JsonNode> rows = BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("ws"), "--kinds=ws");
        long deadline = System.currentTimeMillis() + 10_000;
        while (rows.stream().noneMatch(r -> "frame-received".equals(r.path("subkind").asText())) && System.currentTimeMillis() < deadline) {
            rows = BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("ws", "never"), "--kinds=ws", "--limit=500");
        }
        assertTrue(rows.stream().anyMatch(r -> "created".equals(r.path("subkind").asText()) && r.path("url").asText().equals(wsUrl)), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "frame-sent".equals(r.path("subkind").asText()) && r.path("text").asText().equals("hello ws")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "frame-received".equals(r.path("subkind").asText()) && r.path("text").asText().equals("echo:hello ws")), rows.toString());
    }

    @Test
    void covers_command_har() throws Exception {
        Path project = temp.resolve("har");
        ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint);
        JsonNode recording = ok(project, "har", "--start", "--content=embed", "--url-filter=*127.0.0.1*");
        assertEquals("cdp", recording.path("via").asText(), recording.toString());
        long cursor = ok(project, "browser-events", "--status").path("cursor").asLong();
        ok(project, "pw", "page", "goto", "--url=" + base + "/", "--target=browser:cdp=" + endpoint);
        page(project, "window.api()");
        BrowserEventsLiveTest.collect(project, Long.toString(cursor), Set.of("net"), "--kinds=net", "--match=/api/data");
        Thread.sleep(500);
        JsonNode stopped = ok(project, "har", "--stop", "--out=out/session.har");
        assertTrue(stopped.path("entries").asInt() >= 2, stopped.toString());
        JsonNode har = BrowserEventsLiveTest.JSON.readTree(project.resolve("out/session.har").toFile());
        assertEquals("1.2", har.path("log").path("version").asText());
        JsonNode api = null;
        for (JsonNode entry : har.path("log").path("entries")) {
            assertTrue(entry.has("startedDateTime") && entry.has("time") && entry.has("timings") && entry.has("cache"), entry.toString());
            if (entry.path("request").path("url").asText().contains("/api/data")) api = entry;
        }
        assertTrue(api != null, har.toString());
        assertEquals(200, api.path("response").path("status").asInt());
        assertEquals("q", api.path("request").path("queryString").path(0).path("name").asText());
        assertTrue(api.path("request").path("headers").toString().contains("[redacted]"), api.toString());
        assertFalse(Files.readString(project.resolve("out/session.har")).contains("s3cr3t-token-value"));
        assertTrue(api.path("response").path("content").path("text").asText().contains("ann"), api.toString());

        // capture-scenario from the HAR equals capture from the same traffic's request events.
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode entry : har.path("log").path("entries")) rows.add(entry);
        StringBuilder events = new StringBuilder("[");
        for (JsonNode entry : rows) {
            if (events.length() > 1) events.append(',');
            events.append("{\"method\":\"Network.requestWillBeSent\",\"kind\":\"network\",\"url\":")
                    .append(BrowserEventsLiveTest.JSON.writeValueAsString(entry.path("request").path("url").asText()))
                    .append(",\"httpMethod\":\"").append(entry.path("request").path("method").asText()).append("\"}");
        }
        Files.writeString(project.resolve("events.json"), events.append(']').toString());
        PwCommandLiveTest.Capture fromHar = PwCommandLiveTest.agentIn(project, "capture-scenario", "--har=out/session.har", "--out=from-har.feature");
        PwCommandLiveTest.Capture fromEvents = PwCommandLiveTest.agentIn(project, "capture-scenario", "--events=events.json", "--out=from-events.feature");
        assertEquals(0, fromHar.code(), fromHar.err() + fromHar.out());
        assertEquals(0, fromEvents.code(), fromEvents.err() + fromEvents.out());
        String a = Files.readString(featureFile(project, "from-har.feature"));
        String b = Files.readString(featureFile(project, "from-events.feature"));
        assertTrue(a.contains("EXECUTE SERVICE CALL") && a.contains("/api/data"), a);
        assertEquals(b, a);
    }

    private static Path featureFile(Path project, String name) throws Exception {
        try (var walk = Files.walk(project)) {
            return walk.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
        }
    }

    @Test
    void harViaPlaywright() throws Exception {
        Path project = temp.resolve("pwhar");
        ok(project, "browser-events", "--start", "--via=playwright", "--target=browser:cdp=" + endpoint);
        JsonNode recording = ok(project, "har", "--start", "--via=playwright");
        assertEquals("playwright", recording.path("via").asText(), recording.toString());
        ok(project, "pw", "page", "goto", "--url=" + base + "/?pw=1", "--target=browser:cdp=" + endpoint);
        page(project, "window.api()");
        Thread.sleep(800);
        JsonNode stopped = ok(project, "har", "--stop", "--out=pw.har");
        JsonNode har = BrowserEventsLiveTest.JSON.readTree(project.resolve("pw.har").toFile());
        assertEquals("1.2", har.path("log").path("version").asText(), stopped.toString());
        assertTrue(har.path("log").path("entries").toString().contains("/api/data"), stopped.toString());
    }
}
