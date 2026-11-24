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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D14 live: one focused check per Playwright area through the pw and cdp verbs, attached over CDP to a test-started
 * Chrome. Nothing is downloaded. Skips only when this machine has no Chrome.
 */
class PwCommandLiveTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String INDEX = "<!doctype html><title>pw-index</title>"
            + "<input id=name><p id=keys></p>"
            + "<button id=ask onclick=\"document.getElementById('said').textContent=prompt('Name?')\">Ask</button><p id=said></p>"
            + "<a id=get href=/file.txt download>Get</a>"
            + "<iframe name=inner src=/inner></iframe>"
            + "<div id=drag draggable=true style='width:40px;height:40px;background:#c00'>D</div>"
            + "<div id=drop style='width:80px;height:80px;background:#0c0' ondragover='event.preventDefault()' ondrop=\"this.textContent='dropped'\"></div>"
            + "<p id=api></p><script>document.addEventListener('keydown',e=>{if(e.ctrlKey&&e.key==='k')document.getElementById('keys').textContent='chord'});"
            + "fetch('/api/data').then(r=>r.text()).then(t=>document.getElementById('api').textContent=t).catch(()=>{});</script>";

    @TempDir
    static Path temp;

    static final List<Process> started = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String target;

    @BeforeAll
    static void start() throws Exception {
        String endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile"), started);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/inner" -> "<!doctype html><p id=inside>inside frame</p>";
                case "/file.txt" -> "downloaded bytes";
                case "/api/data" -> "real api";
                default -> INDEX;
            };
            if ("/file.txt".equals(path)) exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=file.txt");
            exchange.getResponseHeaders().add("Content-Type", path.endsWith(".txt") || path.startsWith("/api") ? "text/plain" : "text/html");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        target = "--target=browser:cdp=" + endpoint;
    }

    @AfterAll
    static void stop() {
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        if (server != null) server.stop(0);
    }

    private JsonNode ok(String... args) throws Exception {
        Capture capture = agent(args);
        assertEquals(0, capture.code, capture.err + capture.out);
        return JSON.readTree(capture.out);
    }

    private void home() throws Exception {
        ok("pw", "page", "goto", "--url=" + base + "/", target);
    }

    @Test
    void covers_command_pw() throws Exception {
        home();
        JsonNode pages = ok("pw", "page", "list", target);
        assertEquals("pw", pages.path("op").asText());
        assertTrue(pages.path("pages").size() >= 1, pages.toString());
        Capture bad = agent("pw", "nope", target);
        assertEquals(1, bad.code);
        assertTrue(bad.err.startsWith("error: pw needs an area"), bad.err);
        assertTrue(Files.readString(temp.resolve("project/.pickleball/logs/playwright.log")).contains("page list"));
    }

    @Test
    void covers_command_cdp() throws Exception {
        home();
        JsonNode result = ok("cdp", "--method=Runtime.evaluate", "--params={\"expression\":\"document.title\",\"returnByValue\":true}", target);
        assertEquals("cdp", result.path("op").asText());
        assertEquals("pw-index", result.path("result").path("result").path("value").asText(), result.toString());
        Capture missing = agent("cdp", target);
        assertEquals(1, missing.code, missing.out);
        assertTrue(missing.err.contains("missing --method"), missing.err);
    }

    @Test
    void routeFulfillChangesPageContent() throws Exception {
        JsonNode result = ok("pw", "route", "fulfill", "--pattern=**/api/data", "--body=mocked api", "--content-type=text/plain", "--url=" + base + "/", target);
        assertEquals(200, result.path("status").asInt());
        JsonNode steps = ok("pw", "run", "--steps=[{\"area\":\"route\",\"action\":\"fulfill\",\"pattern\":\"**/api/data\",\"body\":\"mocked api\",\"content-type\":\"text/plain\",\"url\":\"" + base + "/\"},"
                + "{\"area\":\"wait\",\"action\":\"\",\"for\":\"selector\",\"match\":\"#api:has-text('mocked api')\"},"
                + "{\"area\":\"locator\",\"action\":\"text\",\"selector\":\"#api\"}]", target);
        assertEquals("mocked api", steps.path("steps").get(2).path("text").asText(), steps.toString());
    }

    @Test
    void traceZipIsWritten() throws Exception {
        Path zip = temp.resolve("trace.zip");
        ok("pw", "run", "--steps=[{\"area\":\"trace\",\"action\":\"start\"},{\"area\":\"page\",\"action\":\"goto\",\"url\":\"" + base + "/\"},"
                + "{\"area\":\"trace\",\"action\":\"stop\",\"out\":\"" + zip.toString().replace("\\", "\\\\") + "\"}]", target);
        byte[] bytes = Files.readAllBytes(zip);
        assertTrue(bytes.length > 100 && bytes[0] == 'P' && bytes[1] == 'K', "zip");
    }

    @Test
    void screenshotPageAndElementAreWritten() throws Exception {
        home();
        Path page = temp.resolve("page.png");
        Path element = temp.resolve("drop.png");
        ok("pw", "screenshot", "--out=" + page, "--full-page=true", target);
        ok("pw", "screenshot", "--out=" + element, "--selector=#drop", target);
        assertEquals((byte) 0x89, Files.readAllBytes(page)[0]);
        assertTrue(Files.size(element) > 50 && Files.size(element) < Files.size(page));
    }

    @Test
    void downloadIsCapturedToADir() throws Exception {
        home();
        Path dir = Files.createDirectories(temp.resolve("downloads"));
        JsonNode result = ok("pw", "download", "--click=#get", "--dir=" + dir, target);
        assertEquals("file.txt", result.path("suggested").asText(), result.toString());
        assertEquals("downloaded bytes", Files.readString(dir.resolve("file.txt")));
    }

    @Test
    void dialogPromptIsAutoAccepted() throws Exception {
        home();
        JsonNode result = ok("pw", "run", "--steps=[{\"area\":\"dialog\",\"action\":\"accept\",\"prompt-text\":\"Dan\",\"click\":\"#ask\"},"
                + "{\"area\":\"locator\",\"action\":\"text\",\"selector\":\"#said\"}]", target);
        assertEquals("Dan", result.path("steps").get(1).path("text").asText(), result.toString());
        assertEquals("prompt:Name?", result.path("dialogs").get(0).asText(), result.toString());
    }

    @Test
    void emulationViewportAndTimezoneAreObservable() throws Exception {
        JsonNode result = ok("pw", "emulate", "--viewport=500x400", "--timezone=Asia/Tokyo", "--locale=fr-FR", "--color-scheme=dark", "--url=" + base + "/", target);
        JsonNode seen = result.path("observed");
        assertEquals(500, seen.path("width").asInt(), result.toString());
        assertEquals(400, seen.path("height").asInt());
        assertEquals("Asia/Tokyo", seen.path("timezone").asText());
        assertEquals("fr-FR", seen.path("locale").asText());
        assertTrue(seen.path("dark").asBoolean());
    }

    @Test
    void newTabIsListedAndSwitched() throws Exception {
        home();
        int before = ok("pw", "tabs", "list", target).path("pages").size();
        JsonNode made = ok("pw", "page", "new", "--url=" + base + "/inner", target);
        JsonNode listed = ok("pw", "tabs", "list", target);
        assertEquals(before + 1, listed.path("pages").size(), listed.toString());
        int index = made.path("index").asInt();
        JsonNode switched = ok("pw", "tabs", "switch", "--index=" + index, target);
        assertTrue(switched.path("url").asText().endsWith("/inner"), switched.toString());
        ok("pw", "page", "close", "--page-url=/inner", target);
        assertEquals(before, ok("pw", "tabs", "list", target).path("pages").size());
    }

    @Test
    void frameSwitchReadsInsideTheFrame() throws Exception {
        home();
        JsonNode frames = ok("pw", "frames", "list", target);
        assertTrue(frames.path("frames").size() >= 2, frames.toString());
        JsonNode inside = ok("pw", "frames", "switch", "--frame-name=inner", "--selector=#inside", target);
        assertEquals("inside frame", inside.path("text").asText(), inside.toString());
    }

    @Test
    void keyboardChordReachesThePage() throws Exception {
        home();
        JsonNode result = ok("pw", "run", "--steps=[{\"area\":\"keyboard\",\"action\":\"press\",\"keys\":\"Control+k\",\"selector\":\"#name\"},"
                + "{\"area\":\"locator\",\"action\":\"text\",\"selector\":\"#keys\"}]", target);
        assertEquals("chord", result.path("steps").get(1).path("text").asText(), result.toString());
    }

    @Test
    void mouseDragDropsOnTheTarget() throws Exception {
        home();
        JsonNode result = ok("pw", "run", "--steps=[{\"area\":\"mouse\",\"action\":\"drag\",\"from\":\"#drag\",\"to\":\"#drop\"},"
                + "{\"area\":\"locator\",\"action\":\"text\",\"selector\":\"#drop\"}]", target);
        assertEquals("dropped", result.path("steps").get(1).path("text").asText(), result.toString());
    }

    @Test
    void harRecordThenReplayAndVideoOrItsHonestError() throws Exception {
        Path har = temp.resolve("page.har");
        JsonNode recorded = ok("pw", "har", "record", "--path=" + har, "--url=" + base + "/", target);
        assertTrue(recorded.path("bytes").asLong() > 100, recorded.toString());
        assertTrue(Files.readString(har).contains("/api/data") || Files.readString(har).contains("pw-index"));
        JsonNode replayed = ok("pw", "run", "--steps=[{\"area\":\"context\",\"action\":\"new\"},{\"area\":\"har\",\"action\":\"replay\",\"path\":\""
                + har.toString().replace("\\", "\\\\") + "\",\"url\":\"" + base + "/\"},{\"area\":\"page\",\"action\":\"title\"}]", target);
        assertEquals("pw-index", replayed.path("steps").get(2).path("title").asText(), replayed.toString());
        Path videos = Files.createDirectories(temp.resolve("videos"));
        Capture video = agent("pw", "video", "--dir=" + videos, "--url=" + base + "/", "--wait-ms=800", target);
        if (video.code == 0) {
            assertTrue(JSON.readTree(video.out).path("bytes").asLong() > 0, video.out);
        } else {
            assertTrue(video.err.startsWith("error: ") && video.err.contains("video") && video.err.contains("fix: "), video.err);
        }
    }

    @Test
    void noEndpointIsAnErrorWithFix() {
        Capture none = agentIn(temp.resolve("empty"), "pw", "page", "list");
        assertEquals(1, none.code, none.out);
        assertTrue(none.err.startsWith("error: no CDP endpoint for Playwright"), none.err);
        assertTrue(none.err.contains("fix: set pkb_cdp_endpoint"), none.err);
    }

    Capture agent(String... args) {
        return agentIn(temp.resolve("project"), args);
    }

    static Capture agentIn(Path project, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = project.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
