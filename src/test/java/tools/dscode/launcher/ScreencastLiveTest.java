package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E11 live (fresh Chrome per test): screencast frames over CDP and webm over Playwright; share-browser bind. */
class ScreencastLiveTest {
    static final String PAGE = "<!doctype html><title>cast</title><div id=b style='width:200px;height:200px;background:red'></div>"
            + "<script>let i=0;setInterval(()=>{b.style.background=(i++%2)?'red':'blue';b.textContent=i},50)</script>";
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
            byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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
        ok(project, "pw", "page", "goto", "--url=" + base + "/cast");
        return project;
    }

    static JsonNode ok(Path project, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new));
    }

    static boolean playwrightFfmpeg() {
        String custom = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        Path cache = custom == null || custom.isBlank() ? Path.of(System.getProperty("user.home"), ".cache", "ms-playwright") : Path.of(custom);
        try (Stream<Path> dirs = Files.list(cache)) {
            return dirs.anyMatch(d -> d.getFileName().toString().startsWith("ffmpeg"));
        } catch (Exception none) {
            return false;
        }
    }

    static String log(Path project) throws Exception {
        return Files.readString(project.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log"));
    }

    @Test
    void covers_command_screencast() throws Exception {
        Path p = project("cast");
        JsonNode start = ok(p, "screencast", "--start", "--frames-dir=frames", "--via=cdp");
        assertEquals("cdp", start.path("via").asText(), start.toString());
        assertEquals("frames", start.path("form").asText());
        Thread.sleep(1500);
        JsonNode stop = ok(p, "screencast", "--stop");
        assertTrue(stop.path("frames").asInt() > 0, stop.toString());
        try (Stream<Path> frames = Files.list(p.resolve("frames"))) {
            Path first = frames.sorted().findFirst().orElseThrow();
            byte[] head = Files.readAllBytes(first);
            assertTrue((head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8, "JPEG frame " + first);
        }

        if (playwrightFfmpeg()) {
            JsonNode webm = ok(p, "screencast", "--start", "--out=cast.webm", "--show-actions");
            assertEquals("playwright", webm.path("via").asText(), webm.toString());
            assertEquals("webm", webm.path("form").asText());
            Thread.sleep(1500);
            JsonNode done = ok(p, "screencast", "--stop");
            assertTrue(done.path("bytes").asLong() > 0, done.toString());
            try (InputStream in = Files.newInputStream(p.resolve("cast.webm"))) {
                byte[] magic = in.readNBytes(4);
                assertTrue((magic[0] & 0xff) == 0x1A && (magic[1] & 0xff) == 0x45 && (magic[2] & 0xff) == 0xDF && (magic[3] & 0xff) == 0xA3, "EBML header");
            }
            assertTrue(log(p).contains("screencast stop via=playwright form=webm"));
        } else {
            // No Playwright ffmpeg on this machine (tests never download it): the reply says so, and Playwright frames still work.
            PwCommandLiveTest.Capture noFfmpeg = PwCommandLiveTest.agentIn(p, "screencast", "--start", "--out=cast.webm", "--target=browser:cdp=" + endpoint);
            String text = noFfmpeg.out() + noFfmpeg.err();
            assertTrue(noFfmpeg.code() != 0 && text.contains("needs Playwright's ffmpeg") && text.contains("--frames-dir"), text);
            JsonNode pwFrames = ok(p, "screencast", "--start", "--frames-dir=pw-frames", "--via=playwright", "--show-actions");
            assertEquals("playwright", pwFrames.path("via").asText(), pwFrames.toString());
            Thread.sleep(1500);
            assertTrue(ok(p, "screencast", "--stop").path("frames").asInt() > 0);
            assertTrue(log(p).contains("screencast stop via=playwright form=frames"));
        }
        PwCommandLiveTest.Capture refused = PwCommandLiveTest.agentIn(p, "screencast", "--start", "--out=x.webm", "--via=cdp", "--target=browser:cdp=" + endpoint);
        assertTrue(refused.code() != 0 && (refused.out() + refused.err()).contains("webm needs Playwright"), refused.out() + refused.err());
    }

    @Test
    void covers_command_share_browser() throws Exception {
        Path p = project("share");
        JsonNode bound = ok(p, "share-browser", "--name=review", "--port=0");
        String ws = bound.path("endpoint").asText();
        assertTrue(ws.startsWith("ws://127.0.0.1:"), bound.toString());
        assertEquals("playwright-cli attach review", bound.path("connect").asText());
        assertTrue(bound.path("mcpConfig").toString().contains("--endpoint"), bound.toString());
        assertTrue(bound.path("warning").asText().contains("can drive this browser"));
        try (Playwright second = Playwright.create()) {
            Browser browser = second.chromium().connect(ws);
            boolean sees = browser.contexts().stream().flatMap(c -> c.pages().stream()).map(Page::url).anyMatch(u -> u.startsWith(base));
            assertTrue(sees, "the second client sees the shared page");
            browser.close();
        }
        assertEquals("stop", ok(p, "share-browser", "--stop").path("mode").asText());
        String log = Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log"));
        assertTrue(log.contains("share-browser name=review endpoint=ws://127.0.0.1:<port>/<id>"), log);
        assertTrue(log.contains("share-browser stop"));
    }
}
