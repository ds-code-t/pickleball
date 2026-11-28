package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E6 live: emulation, clock and permissions observed on fixture pages over CDP, Playwright and BiDi. */
class EmulateLiveTest {
    static final String PAGE = "<!doctype html><title>emulate</title><script>"
            + "window.tz=()=>Intl.DateTimeFormat().resolvedOptions().timeZone;"
            + "window.lang=()=>navigator.language+'|'+new Intl.NumberFormat().format(1234.5);"
            + "window.mq=(q)=>matchMedia(q).matches;"
            + "window.geo=()=>new Promise(res=>navigator.geolocation.getCurrentPosition(p=>res(p.coords.latitude+','+p.coords.longitude),e=>res('ERR '+e.code),{timeout:4000}));"
            + "window.perm=(n)=>navigator.permissions.query({name:n}).then(r=>r.state);"
            + "window.ua=()=>fetch('/ua').then(r=>r.text());"
            + "window.arm=()=>{window.fired=0;setTimeout(()=>window.fired=1,60000);return Date.now()};"
            + "</script>";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;

    /** A fresh Chrome per test: virtual time or a fake clock in one test must not freeze the next one's page. */
    @org.junit.jupiter.api.BeforeEach
    void chrome(org.junit.jupiter.api.TestInfo info) throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile-" + info.getTestMethod().orElseThrow().getName()), started);
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean ua = exchange.getRequestURI().getPath().equals("/ua");
            byte[] body = (ua ? String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")) : PAGE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", ua ? "text/plain" : "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopHosts() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        projects.clear();
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        started.clear();
    }

    @AfterAll
    static void stop() {
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        if (server != null) server.stop(0);
    }

    private static Path project(String name) {
        Path project = temp.resolve(name);
        projects.add(project);
        return project;
    }

    private static JsonNode ok(Path project, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new));
    }

    private static String eval(Path project, String expr) throws Exception {
        return ok(project, "pw", "page", "eval", "--expr=" + expr).path("value").asText();
    }

    private static void go(Path project) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + base + "/");
    }

    @Test
    void covers_command_emulate_options() throws Exception {
        Path p = project("emulate");
        go(p);
        String tz0 = eval(p, "window.tz()");
        JsonNode reply = ok(p, "emulate", "--locale=de-DE", "--timezone=Asia/Tokyo", "--media=prefers-color-scheme:dark", "--forced-colors=active",
                "--ua=PickleBot/1.0", "--cpu=4x", "--vision=deuteranopia", "--viewport=500x400@2", "--via=cdp");
        for (String option : List.of("locale", "timezone", "media", "forced-colors", "ua", "cpu", "vision", "viewport")) {
            assertEquals("cdp", reply.path("applied").path(option).asText(), option + " " + reply);
        }
        go(p);
        assertEquals("Asia/Tokyo", eval(p, "window.tz()"));
        assertEquals("de-DE|1.234,5", eval(p, "window.lang()"));
        assertEquals("true", eval(p, "window.mq('(prefers-color-scheme: dark)')"));
        assertEquals("true", eval(p, "window.mq('(forced-colors: active)')"));
        assertEquals("PickleBot/1.0", eval(p, "window.ua()"), "the server saw the UA");
        assertEquals("500x400x2", eval(p, "innerWidth+'x'+innerHeight+'x'+devicePixelRatio"));
        JsonNode show = ok(p, "emulate", "--show");
        assertEquals("4x", show.path("active").path("cpu").path("value").asText(), show.toString());
        assertEquals("cdp", show.path("active").path("cpu").path("via").asText(), show.toString());
        JsonNode reset = ok(p, "emulate", "--reset");
        assertTrue(reset.path("cleared").size() >= 8, reset.toString());
        go(p);
        assertEquals(tz0, eval(p, "window.tz()"), "--reset restored the timezone");
        assertEquals("false", eval(p, "window.mq('(prefers-color-scheme: dark)')"));
        assertNotEquals("PickleBot/1.0", eval(p, "window.ua()"));
        assertEquals(0, ok(p, "emulate", "--show").path("active").size());
        PwCommandLiveTest.Capture bad = PwCommandLiveTest.agentIn(p, "emulate", "--vision=sepia", "--target=browser:cdp=" + endpoint);
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("--vision is one of"), bad.err());
    }

    /** PKB-HCR bug 12: emulate --reset restored the network but did not list it in cleared. */
    @Test
    void resetListsTheNetworkItRestored() throws Exception {
        Path p = project("net-reset");
        go(p);
        ok(p, "emulate", "--network=offline");
        assertEquals("offline", ok(p, "emulate", "--show").path("active").path("network").path("value").asText());
        JsonNode reset = ok(p, "emulate", "--reset");
        assertTrue(reset.path("cleared").toString().contains("network"), reset.toString());
        assertEquals(0, ok(p, "emulate", "--show").path("active").size());
    }

    @Test
    void playwrightReportsWhatItCannotApply() throws Exception {
        Path p = project("pw-emulate");
        go(p);
        JsonNode reply = ok(p, "emulate", "--media=prefers-color-scheme:dark", "--cpu=2x", "--via=playwright");
        assertEquals("playwright", reply.path("applied").path("media").asText(), reply.toString());
        assertTrue(reply.path("unsupported").path("cpu").asText().contains("playwright"), "cpu is reported, not dropped: " + reply);
        assertEquals("true", eval(p, "window.mq('(prefers-color-scheme: dark)')"));
        ok(p, "emulate", "--reset");
    }

    @Test
    void covers_command_permissions() throws Exception {
        Path p = project("perm");
        go(p);
        JsonNode grant = ok(p, "permissions", "--grant=geolocation", "--origin=" + base);
        assertEquals("cdp", grant.path("via").asText(), grant.toString());
        ok(p, "emulate", "--geo=52.52,13.4");
        assertEquals("granted", eval(p, "window.perm('geolocation')"));
        assertEquals("52.52,13.4", eval(p, "window.geo()"), "geolocation fixture read the coordinates");
        ok(p, "permissions", "--deny=geolocation", "--origin=" + base);
        assertEquals("denied", eval(p, "window.perm('geolocation')"));
        assertTrue(eval(p, "window.geo()").startsWith("ERR 1"), "denied");
        ok(p, "permissions", "--reset");
        assertEquals("prompt", eval(p, "window.perm('geolocation')"));
        assertTrue(Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("permissions deny [geolocation]"));
    }

    @Test
    void covers_command_clock() throws Exception {
        Path p = project("clock");
        go(p);
        JsonNode install = ok(p, "clock", "--install=2030-01-01T09:00:00Z", "--via=playwright");
        assertEquals("playwright", install.path("via").asText(), install.toString());
        long now = Long.parseLong(eval(p, "String(window.arm())"));
        assertTrue(Math.abs(now - java.time.Instant.parse("2030-01-01T09:00:00Z").toEpochMilli()) < 60_000, "installed time " + now);
        assertEquals("0", eval(p, "String(window.fired)"));
        ok(p, "clock", "--fast-forward=01:01", "--via=playwright");
        assertEquals("1", eval(p, "String(window.fired)"), "fast-forward fired the 60 s timer");
        assertTrue(ok(p, "emulate", "--show").path("active").path("clock").path("value").asText().startsWith("fast-forward=01:01"));
        assertTrue(Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("clock fast-forward via=playwright"));
    }

    @Test
    void cdpVirtualTimeFallback() throws Exception {
        Path p = project("vclock");
        go(p);
        JsonNode pause = ok(p, "clock", "--pause-at=2031-05-05T00:00:00Z", "--via=cdp");
        assertEquals("cdp", pause.path("via").asText(), pause.toString());
        long now = Long.parseLong(eval(p, "String(Date.now())"));
        assertTrue(Math.abs(now - java.time.Instant.parse("2031-05-05T00:00:00Z").toEpochMilli()) < 5_000, "virtual time " + now);
        PwCommandLiveTest.Capture install = PwCommandLiveTest.agentIn(p, "clock", "--install", "--via=cdp", "--target=browser:cdp=" + endpoint);
        assertEquals(1, install.code());
        assertTrue(install.err().contains("unsupported"), install.err());
        ok(p, "clock", "--resume", "--via=cdp");
        assertFalse(eval(p, "String(Date.now())").isBlank());
    }

    @Test
    void bidiEmulation() throws Exception {
        String binary = dev.domengine.session.BrowserSession.findChrome();
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        com.fasterxml.jackson.databind.node.ObjectNode empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        org.openqa.selenium.chrome.ChromeOptions options = tools.dscode.common.driver.DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        org.openqa.selenium.chrome.ChromeDriver driver = new org.openqa.selenium.chrome.ChromeDriver(options);
        try {
            String bidi = String.valueOf(driver.getCapabilities().getCapability("webSocketUrl"));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> goog = (java.util.Map<String, Object>) driver.getCapabilities().getCapability("goog:chromeOptions");
            String cdp = "http://" + goog.get("debuggerAddress");
            Path p = project("bidi");
            BrowserEventsLiveTest.ok(p, "pw", "page", "goto", "--url=" + base + "/", "--cdp-endpoint=" + cdp);
            JsonNode reply = BrowserEventsLiveTest.ok(p, "emulate", "--locale=fr-FR", "--timezone=America/Phoenix", "--ua=BidiBot/2", "--geo=10.5,20.25",
                    "--cpu=2x", "--via=bidi", "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + bidi);
            assertTrue(reply.path("unsupported").path("cpu").asText().contains("bidi"), "cpu has no BiDi command and is reported: " + reply);
            BrowserEventsLiveTest.ok(p, "pw", "page", "goto", "--url=" + base + "/", "--cdp-endpoint=" + cdp);
            java.util.function.Function<String, String> eval = expr -> {
                try {
                    return BrowserEventsLiveTest.ok(p, "pw", "page", "eval", "--expr=" + expr, "--cdp-endpoint=" + cdp).path("value").asText();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            };
            for (String option : List.of("locale", "timezone", "ua", "geo")) assertEquals("bidi", reply.path("applied").path(option).asText(), option + " " + reply);
            assertEquals("America/Phoenix", eval.apply("window.tz()"));
            assertTrue(eval.apply("window.lang()").startsWith("fr"), eval.apply("window.lang()"));
            assertEquals("BidiBot/2", eval.apply("navigator.userAgent"));
            BrowserEventsLiveTest.ok(p, "emulate", "--reset", "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + bidi);
            PwCommandLiveTest.agentIn(p, "browser-events", "--stop");
        } finally {
            driver.quit();
        }
    }
}
