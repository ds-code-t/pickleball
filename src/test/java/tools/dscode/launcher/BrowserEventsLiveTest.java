package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2 live: the unified browser event stream through the protocol host on a test-started Chrome. Navigation, a
 * console.log and a fetch produce nav, console and net rows with increasing seq and ISO times; --since and --follow
 * read only newer rows; the NDJSON spill is written and capped; the kinds toggle is respected.
 */
class BrowserEventsLiveTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PAGE = "<!doctype html><title>events</title><p id=out></p><script>"
            + "console.log('hello from page', 42);"
            + "fetch('/api/data').then(r=>r.text()).then(t=>document.getElementById('out').textContent=t);</script>";

    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;

    @BeforeAll
    static void start() throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile"), started);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.startsWith("/api") ? "api says hi" : PAGE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", path.startsWith("/api") ? "text/plain" : "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        if (server != null) server.stop(0);
    }

    static Path project(String name) {
        Path project = temp.resolve(name);
        projects.add(project);
        return project;
    }

    static JsonNode ok(Path project, String... args) throws Exception {
        PwCommandLiveTest.Capture capture = PwCommandLiveTest.agentIn(project, args);
        assertEquals(0, capture.code(), capture.err() + capture.out());
        return JSON.readTree(capture.out());
    }

    static void visit(Path project, String path) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + base + path, "--target=browser:cdp=" + endpoint);
    }

    /** Reads until every wanted kind has a row (or 15 s). */
    static List<JsonNode> collect(Path project, String since, Set<String> wanted, String... extra) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = since;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !seen.containsAll(wanted)) {
            List<String> args = new ArrayList<>(List.of("browser-events", "--since=" + cursor, "--wait-ms=1000"));
            args.addAll(List.of(extra));
            JsonNode reply = ok(project, args.toArray(String[]::new));
            for (JsonNode row : reply.path("rows")) {
                rows.add(row);
                seen.add(row.path("kind").asText());
            }
            cursor = reply.path("cursor").asText();
        }
        return rows;
    }

    @Test
    void covers_command_browser_events() throws Exception {
        Path project = project("main");
        JsonNode startedReply = ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint);
        assertEquals("cdp", startedReply.path("via").asText(), startedReply.toString());
        long cursor = startedReply.path("cursor").asLong();
        visit(project, "/");
        List<JsonNode> rows = collect(project, Long.toString(cursor), Set.of("nav", "console", "net"));
        assertTrue(rows.stream().anyMatch(r -> "nav".equals(r.path("kind").asText()) && r.path("url").asText().startsWith(base)), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "console".equals(r.path("kind").asText()) && r.path("text").asText().equals("hello from page 42")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "net".equals(r.path("kind").asText()) && r.path("url").asText().endsWith("/api/data")), rows.toString());
        long previous = cursor;
        for (JsonNode row : rows) {
            assertTrue(row.path("seq").asLong() > previous, "seq increases: " + rows);
            previous = row.path("seq").asLong();
            OffsetDateTime.parse(row.path("at").asText());
            assertEquals("cdp", row.path("source").asText());
            assertFalse(row.path("session").asText().isBlank());
        }
        long last = previous;
        JsonNode newer = ok(project, "browser-events", "--since=" + last);
        for (JsonNode row : newer.path("rows")) assertTrue(row.path("seq").asLong() > last, "--since returns only newer rows: " + newer);
        long cursorAfter = newer.path("cursor").asLong();
        assertTrue(cursorAfter >= last);
        JsonNode none = ok(project, "browser-events", "--since=" + cursorAfter, "--kinds=console");
        assertEquals(0, none.path("rows").size(), "no console row after the cursor: " + none);
        Path spill = project.resolve(".pickleball/runs/live/browser-events-" + LocalDate.now() + ".ndjson");
        assertTrue(Files.isRegularFile(spill));
        assertTrue(Files.readString(spill).contains("hello from page 42"));
        assertTrue(Files.readString(spill).contains("\"seq\":" + previous));
        PwCommandLiveTest.Capture bad = PwCommandLiveTest.agentIn(project, "browser-events", "--kinds=bogus");
        assertEquals(1, bad.code());
        assertTrue(bad.err().startsWith("error: unknown event kind bogus"), bad.err());
    }

    @Test
    void followPrintsNewRowsAsNdjsonAndRedactsSecrets() throws Exception {
        Path project = project("follow");
        long cursor = ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint).path("cursor").asLong();
        Thread trigger = new Thread(() -> {
            try {
                Thread.sleep(700);
                ok(project, "pw", "page", "eval", "--expr=console.log('follow me token=abc123secret')", "--target=browser:cdp=" + endpoint);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        trigger.start();
        PwCommandLiveTest.Capture follow = PwCommandLiveTest.agentIn(project, "browser-events", "--follow", "--since=" + cursor,
                "--kinds=console", "--wait-ms=6000", "--limit=1");
        trigger.join();
        assertEquals(0, follow.code(), follow.err());
        String[] lines = follow.out().trim().split("\n");
        assertTrue(lines.length >= 2, follow.out());
        JsonNode row = JSON.readTree(lines[0]);
        assertEquals("console", row.path("kind").asText());
        assertTrue(row.path("text").asText().startsWith("follow me token=[redacted]"), row.toString());
        assertTrue(row.path("redacted").toString().contains("text"), row.toString());
        assertFalse(follow.out().contains("abc123secret"));
        JsonNode tail = JSON.readTree(lines[lines.length - 1]);
        assertEquals(row.path("seq").asLong(), tail.path("cursor").asLong());
    }

    @Test
    void spillIsCappedAndKindsToggleIsRespected() throws Exception {
        Path project = project("capped");
        System.setProperty("pkb_browser_events_max_kb", "1");
        System.setProperty("pkb_browser_events_kinds", "console");
        try {
            ok(project, "browser-events", "--start", "--target=browser:cdp=" + endpoint);
        } finally {
            System.clearProperty("pkb_browser_events_max_kb");
            System.clearProperty("pkb_browser_events_kinds");
        }
        for (int i = 0; i < 12; i++) {
            ok(project, "pw", "page", "eval", "--expr=console.log('" + "x".repeat(150) + " " + i + "')", "--target=browser:cdp=" + endpoint);
        }
        visit(project, "/");
        List<JsonNode> rows = collect(project, "0", Set.of("console"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (rows.size() < 12 && System.currentTimeMillis() < deadline) rows = collect(project, "0", Set.of("console", "never"), "--limit=500");
        assertTrue(rows.size() >= 12, rows.toString());
        assertTrue(rows.stream().allMatch(r -> "console".equals(r.path("kind").asText())), "only console rows: " + rows);
        Path spill = project.resolve(".pickleball/runs/live/browser-events-" + LocalDate.now() + ".ndjson");
        assertTrue(Files.isRegularFile(spill.resolveSibling(spill.getFileName() + ".1")), "rotated once");
        assertTrue(Files.size(spill) <= 1024 + 600, "capped: " + Files.size(spill));
    }

    /** E2b: --dom feeds pushed DOM batches as kind=dom rows on the backend asked for; pickleball surfaces stay out. */
    void domFeed(String name, String via) throws Exception {
        Path project = project(name);
        visit(project, "/");
        JsonNode watching = ok(project, "browser-events", "--dom", "--via=" + via, "--debounce=20", "--target=browser:cdp=" + endpoint);
        assertEquals(via, watching.path("via").asText(), watching.toString());
        assertEquals("push", watching.path("mode").asText(), watching.toString());
        long cursor = watching.path("cursor").asLong();
        ok(project, "pw", "page", "eval", "--expr=(()=>{const d=document.createElement('div');d.setAttribute('data-pkb-ui','');document.body.appendChild(d);return 1})()",
                "--target=browser:cdp=" + endpoint);
        ok(project, "pw", "page", "eval", "--expr=(()=>{const b=document.createElement('b');b.id='pushed';b.textContent='pushed';document.body.appendChild(b);return 1})()",
                "--target=browser:cdp=" + endpoint);
        List<JsonNode> rows = collect(project, Long.toString(cursor), Set.of("dom"), "--kinds=dom");
        assertFalse(rows.isEmpty(), "a dom row arrived");
        JsonNode row = rows.get(rows.size() - 1);
        assertEquals("mutation", row.path("subkind").asText(), rows.toString());
        assertEquals(via, row.path("source").asText(), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.path("dom").path("targets").toString().contains("body") && r.path("dom").path("added").asInt() >= 1), rows.toString());
        assertTrue(rows.stream().noneMatch(r -> r.path("text").asText().contains("data-pkb")), rows.toString());
        JsonNode status = ok(project, "browser-events", "--status");
        assertEquals(via, status.path("dom").asText(), status.toString());
    }

    @Test
    void domMutationsArePushedOverCdp() throws Exception {
        domFeed("dom-cdp", "cdp");
    }

    @Test
    void domMutationsArePushedOverPlaywright() throws Exception {
        domFeed("dom-pw", "playwright");
    }
}
