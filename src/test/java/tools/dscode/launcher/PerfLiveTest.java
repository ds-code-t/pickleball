package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.BrowserReport;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E9 live (fresh Chrome per test): metrics, vitals, traces, heap tools; and the report section from a recorded NDJSON fixture. */
class PerfLiveTest {
    static final String PAGE = "<!doctype html><title>perf</title><style>body{margin:0;font:16px sans-serif}</style>"
            + "<div id=top></div><h1 style='font-size:64px'>A large heading that is the largest paint</h1><p>body text</p>"
            + "<script>setTimeout(()=>{const d=document.createElement('div');d.style.height='300px';d.textContent='late banner';"
            + "document.getElementById('top').appendChild(d)},300)</script>";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;

    @BeforeAll
    static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopAll() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        projects.clear();
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        started.clear();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    private static Path project(String name) throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile-" + name), started);
        Path project = temp.resolve(name);
        projects.add(project);
        return project;
    }

    static JsonNode ok(Path project, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new));
    }

    static void go(Path project) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + base + "/");
    }

    @Test
    void covers_command_perf() throws Exception {
        Path p = project("perf");
        go(p);
        ok(p, "perf", "--vitals");
        go(p);
        Thread.sleep(1200);
        JsonNode vitals = ok(p, "perf", "--vitals");
        assertEquals("cdp", vitals.path("via").asText(), vitals.toString());
        JsonNode v = vitals.path("vitals");
        assertTrue(v.path("lcp").asDouble() > 0, "LCP measured: " + v);
        assertTrue(v.path("cls").asDouble() > 0, "the late banner shifted the layout: " + v);
        assertTrue(v.path("ttfb").isNumber(), "TTFB measured: " + v);
        assertTrue(v.path("fcp").asDouble() > 0, "FCP measured: " + v);
        assertFalse("true".equals(ok(p, "pw", "page", "eval", "--expr=String(!!window.__pkbVitals)").path("value").asText()),
                "the observer lives in pickleball's isolated world, not the page's");
        JsonNode rows = BrowserEventsLiveTest.ok(p, "browser-events", "--since=0", "--kinds=perf", "--limit=200");
        assertTrue(rows.toString().contains("\"metric\":\"lcp\""), rows.toString());
        JsonNode metrics = ok(p, "perf", "--metrics");
        assertTrue(metrics.path("metrics").path("JSHeapUsedSize").asDouble() > 0, metrics.toString());
        JsonNode pwMetrics = ok(p, "perf", "--metrics", "--via=playwright");
        assertTrue(pwMetrics.path("metrics").path("loadMs").asLong() > 0, pwMetrics.toString());
        ok(p, "perf", "--trace-start");
        go(p);
        JsonNode trace = ok(p, "perf", "--trace-stop", "--out=out/trace.json");
        JsonNode file = new ObjectMapper().readTree(p.resolve("out/trace.json").toFile());
        JsonNode events = file.isArray() ? file : file.path("traceEvents");
        assertTrue(events.isArray() && events.size() > 0, "Chrome trace-event JSON with traceEvents: " + trace);
        JsonNode report = BrowserEventsLiveTest.ok(p, "perf", "--report");
        assertTrue(report.path("vitals").size() > 0, report.toString());
        assertTrue(Files.readString(p.resolve(report.path("html").asText())).contains("<h2>Browser</h2>"));
        String log = Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log"));
        assertTrue(log.contains("perf trace-stop bytes="), log);
    }

    @Test
    void playwrightTraceAndVitals() throws Exception {
        Path p = project("perf-pw");
        go(p);
        JsonNode vitals = ok(p, "perf", "--vitals", "--via=playwright");
        assertEquals("playwright", vitals.path("via").asText());
        assertTrue(vitals.path("vitals").path("ttfb").isNumber(), vitals.toString());
        ok(p, "perf", "--trace-start", "--format=playwright", "--aria-snapshots");
        go(p);
        JsonNode stopped = ok(p, "perf", "--trace-stop", "--out=out/trace.zip");
        assertEquals("playwright", stopped.path("format").asText());
        assertTrue(Files.size(p.resolve("out/trace.zip")) > 0);
    }

    @Test
    void covers_command_memory() throws Exception {
        Path p = project("memory");
        go(p);
        assertEquals("gc", ok(p, "memory", "--gc").path("mode").asText());
        assertEquals("playwright", ok(p, "memory", "--gc", "--via=playwright").path("via").asText());
        JsonNode capped = ok(p, "memory", "--snapshot", "--out=out/capped.heapsnapshot", "--max-mb=1");
        assertTrue(capped.path("truncated").asBoolean(), capped.toString());
        assertEquals(1024 * 1024, Files.size(p.resolve("out/capped.heapsnapshot")));
        JsonNode full = ok(p, "memory", "--snapshot", "--out=out/full.heapsnapshot");
        assertFalse(full.path("truncated").asBoolean(), full.toString());
        assertTrue(Files.readString(p.resolve("out/full.heapsnapshot")).startsWith("{\"snapshot\":"), "a V8 heap snapshot");
    }

    @Test
    void reportSectionFromRecordedNdjson() throws Exception {
        Path p = temp.resolve("report");
        Path run = p.resolve(".pickleball/runs/r1");
        Files.createDirectories(run);
        Files.writeString(run.resolve("browser-events-2026-11-24.ndjson"), String.join("\n",
                "{\"kind\":\"perf\",\"subkind\":\"vital\",\"metric\":\"lcp\",\"value\":812,\"url\":\"https://shop.test/\"}",
                "{\"kind\":\"perf\",\"subkind\":\"vital\",\"metric\":\"cls\",\"value\":0.12,\"url\":\"https://shop.test/\"}",
                "{\"kind\":\"net\",\"subkind\":\"finished\",\"url\":\"https://shop.test/a.js\",\"durationMs\":40,\"responseBody\":\"SECRET-BODY\"}",
                "{\"kind\":\"net\",\"subkind\":\"finished\",\"url\":\"https://shop.test/slow.json\",\"durationMs\":900}",
                "{\"kind\":\"net\",\"subkind\":\"failed\",\"url\":\"https://shop.test/gone\",\"text\":\"net::ERR_FAILED\"}",
                "{\"kind\":\"error\",\"subkind\":\"exception\",\"text\":\"TypeError: x is undefined\\n    at f\",\"url\":\"https://shop.test/\"}",
                "not json") + "\n");
        Map<String, Object> summary = BrowserReport.summarize(run, 1);
        assertEquals("https://shop.test/slow.json", ((List<Map<String, Object>>) summary.get("slowestRequests")).get(0).get("url"));
        assertEquals(1, ((List<?>) summary.get("slowestRequests")).size());
        String html = BrowserReport.html(summary);
        assertTrue(html.contains("<h2>Browser</h2>") && html.contains("812") && html.contains("net::ERR_FAILED") && html.contains("TypeError: x is undefined"), html);
        assertFalse(html.contains("SECRET-BODY") || summary.toString().contains("SECRET-BODY"), "no payloads");
        PwCommandLiveTest.Capture cli = PwCommandLiveTest.agentIn(p, "perf", "--report", "--run-id=r1");
        assertEquals(0, cli.code(), cli.err());
        assertTrue(Files.isRegularFile(run.resolve("workbench/browser.html")));
        PwCommandLiveTest.Capture none = PwCommandLiveTest.agentIn(p, "perf", "--report", "--run-id=nothing");
        assertEquals(1, none.code());
        assertTrue(none.err().contains("no browser-events spill"), none.err());
    }
}
