package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.common.driver.DriverConstruction;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E5 live: the same network-control checks forced to --via=cdp and --via=playwright (one Chrome over CDP), and to
 * --via=bidi (a Selenium Chrome session with webSocketUrl). Fixture server: JSON API, header echo, basic-auth page,
 * CSP page with an inline script.
 */
class RouteLiveTest {
    static final String PAGE = "<!doctype html><title>route</title><script>"
            + "window.api=()=>fetch('/api/data').then(async r=>r.status+':'+r.headers.get('x-mock')+':'+(await r.text())).catch(e=>'ERR '+e.message);"
            + "window.echo=()=>fetch('/api/echo').then(r=>r.text()).catch(e=>'ERR '+e.message);"
            + "window.sock=(u)=>new Promise(res=>{const w=new WebSocket(u);w.onopen=()=>w.send('ping');w.onmessage=e=>res(e.data);w.onerror=()=>res('ws error');setTimeout(()=>res('timeout'),4000);});"
            + "</script>";
    static final String AUTH_LOGIN = "ann:open-sesame-42";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;
    static ChromeDriver bidiDriver;
    static String bidiWs;
    static String bidiCdp;

    @BeforeAll
    static void start() throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile"), started);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String type = "text/html";
            int status = 200;
            String body;
            switch (path) {
                case "/api/data" -> {
                    type = "application/json";
                    body = "{\"real\":true}";
                }
                case "/api/echo" -> {
                    type = "text/plain";
                    body = "x-test=" + exchange.getRequestHeaders().getFirst("X-Test") + ";x-extra=" + exchange.getRequestHeaders().getFirst("X-Extra");
                }
                case "/auth" -> {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    String want = "Basic " + Base64.getEncoder().encodeToString(AUTH_LOGIN.getBytes(StandardCharsets.UTF_8));
                    if (want.equals(auth)) body = "<!doctype html><title>authed</title><p>welcome</p>";
                    else {
                        status = 401;
                        exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fixture\"");
                        body = "<!doctype html><title>denied</title>";
                    }
                }
                case "/csp" -> {
                    exchange.getResponseHeaders().add("Content-Security-Policy", "script-src 'none'");
                    body = "<!doctype html><title>csp</title><script>document.title='inline ran'</script>";
                }
                default -> body = PAGE;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
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
        if (bidiDriver != null) bidiDriver.quit();
        if (server != null) server.stop(0);
    }

    /** Each test stops its hosts, so no route or Fetch interception from one test reaches the next. */
    @org.junit.jupiter.api.AfterEach
    void stopHosts() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        projects.clear();
        PlaywrightConnection.closeAll();
    }

    /** One project and its endpoint flags. */
    record Session(Path project, String via, String[] target) {
        JsonNode ok(String... args) throws Exception {
            List<String> all = new ArrayList<>(List.of(args));
            all.addAll(List.of(target));
            if (!via.isEmpty() && !args[0].equals("pw") && !args[0].equals("browser-events")) all.add("--via=" + via);
            return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new));
        }

        PwCommandLiveTest.Capture run(String... args) {
            List<String> all = new ArrayList<>(List.of(args));
            all.addAll(List.of(target));
            if (!via.isEmpty()) all.add("--via=" + via);
            return PwCommandLiveTest.agentIn(project, all.toArray(String[]::new));
        }

        String eval(String expr) throws Exception {
            List<String> all = new ArrayList<>(List.of("pw", "page", "eval", "--expr=" + expr));
            all.add(pwTarget());
            return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new)).path("value").asText();
        }

        String pwTarget() {
            return "bidi".equals(via) ? "--cdp-endpoint=" + bidiCdp : "--target=browser:cdp=" + endpoint;
        }

        void go(String path) throws Exception {
            BrowserEventsLiveTest.ok(project, "pw", "page", "goto", "--url=" + base + path, pwTarget());
        }

        JsonNode row(long since, Predicate<JsonNode> want) throws Exception {
            long deadline = System.currentTimeMillis() + 10_000;
            List<JsonNode> seen = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                JsonNode reply = BrowserEventsLiveTest.ok(project, "browser-events", "--since=" + since, "--kinds=net", "--wait-ms=800", "--limit=500");
                JsonNode rows = reply.has("rows") ? reply.get("rows") : reply.path("items");
                for (JsonNode r : rows) {
                    seen.add(r);
                    if (want.test(r)) return r;
                }
            }
            throw new AssertionError("no matching row in " + seen);
        }

        long cursor() throws Exception {
            return BrowserEventsLiveTest.ok(project, "browser-events", "--status").path("cursor").asLong();
        }
    }

    private static Session session(String name, String via) {
        Path project = temp.resolve(name);
        projects.add(project);
        if ("bidi".equals(via)) return new Session(project, via, new String[]{"--cdp-endpoint=" + bidiCdp, "--bidi-endpoint=" + bidiWs});
        return new Session(project, via, new String[]{"--target=browser:cdp=" + endpoint});
    }

    private static void bidiBrowser() {
        if (bidiDriver != null) return;
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions options = DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        bidiDriver = new ChromeDriver(options);
        bidiWs = String.valueOf(bidiDriver.getCapabilities().getCapability("webSocketUrl"));
        @SuppressWarnings("unchecked")
        Map<String, Object> goog = (Map<String, Object>) bidiDriver.getCapabilities().getCapability("goog:chromeOptions");
        bidiCdp = "http://" + goog.get("debuggerAddress");
    }

    /** fulfill, --times=1 expiry, abort, modify, unroute: the checks every backend runs. */
    private static void routes(Session s) throws Exception {
        s.ok("browser-events", "--start");
        s.go("/");
        long cursor = s.cursor();
        JsonNode fulfill = s.ok("net-route", "--match=*/api/data", "--action=fulfill", "--status=203", "--body={\"mock\":1}",
                "--headers={\"X-Mock\":\"yes\",\"Content-Type\":\"application/json\"}", "--times=1", "--id=mock");
        assertEquals(s.via(), fulfill.path("via").asText(), fulfill.toString());
        assertEquals("203:yes:{\"mock\":1}", s.eval("window.api()"), "fulfill answered the request");
        assertEquals("200:null:{\"real\":true}", s.eval("window.api()"), "--times=1 expired after one hit");
        JsonNode routed = s.row(cursor, r -> "routed".equals(r.path("subkind").asText()) && "mock".equals(r.path("text").asText()));
        assertEquals("fulfill", routed.path("method").asText(), routed.toString());

        s.ok("net-route", "--match=re:/api/data$", "--action=abort", "--id=down");
        assertTrue(s.eval("window.api()").startsWith("ERR"), "abort failed the fetch");
        s.ok("net-unroute", "--id=down");
        assertTrue(s.eval("window.api()").startsWith("200:"), "unroute restored it");

        s.ok("net-route", "--match=/api/echo", "--action=modify", "--headers={\"X-Test\":\"modified\"}", "--id=mod");
        assertTrue(s.eval("window.echo()").contains("x-test=modified"), "the fixture server saw the modified header");
        JsonNode list = BrowserEventsLiveTest.ok(s.project(), "browser-events", "--status");
        assertEquals(1, list.path("routes").asInt(), list.toString());
        JsonNode cleared = s.ok("net-unroute", "--all");
        assertEquals("mod", cleared.path("removed").path(0).asText(), cleared.toString());
        assertTrue(s.eval("window.echo()").contains("x-test=null"));
    }

    private static void headersAndOffline(Session s) throws Exception {
        s.ok("net-headers", "--set={\"X-Extra\":\"added\"}");
        s.go("/");
        assertTrue(s.eval("window.echo()").contains("x-extra=added"), "extra header arrived");
        s.ok("net-headers", "--clear");
        s.go("/");
        assertTrue(s.eval("window.echo()").contains("x-extra=null"), "cleared");
        JsonNode offline = s.ok("emulate", "--network=offline");
        assertEquals("offline", offline.path("network").asText(), offline.toString());
        assertTrue(s.eval("window.api()").startsWith("ERR"), "offline makes fetch fail");
        s.ok("emulate", "--network=online");
        assertTrue(s.eval("window.api()").startsWith("200:"), "online again");
    }

    private static void authAndCsp(Session s) throws Exception {
        PwCommandLiveTest.Capture missing = s.run("net-auth", "--credential=prop:pkb.test.missing.login");
        assertEquals(1, missing.code(), missing.out());
        assertTrue(missing.err().contains("not set"), missing.err());
        System.setProperty("pkb.test.route.login", AUTH_LOGIN);
        try {
            PwCommandLiveTest.Capture auth = s.run("net-auth", "--credential=prop:pkb.test.route.login");
            assertEquals(0, auth.code(), auth.err());
            assertFalse(auth.out().contains("open-sesame-42"), "the secret is never printed");
            assertTrue(auth.out().contains("[redacted]"), auth.out());
            s.go("/auth");
            assertEquals("authed", s.eval("document.title"), "basic auth passed with the secret ref");
            assertFalse(Files.readString(s.project().resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("open-sesame-42"));
        } finally {
            System.clearProperty("pkb.test.route.login");
            s.ok("net-auth", "--clear");
        }
        s.go("/csp");
        assertEquals("csp", s.eval("document.title"), "CSP blocked the inline script");
        Session csp = s;
        if ("bidi".equals(s.via())) {
            // Chrome's BiDi has no browsingContext.setBypassCSP: forced bidi is unsupported, auto falls back to CDP.
            PwCommandLiveTest.Capture forced = s.run("csp", "--bypass");
            if (forced.code() != 0) {
                assertTrue(forced.err().contains("unsupported: csp on chrome/bidi"), forced.err());
                csp = new Session(s.project(), "", s.target());
            }
        }
        JsonNode bypass = csp.ok("csp", "--bypass");
        assertTrue(bypass.path("bypass").asBoolean(), bypass.toString());
        s.go("/csp");
        assertEquals("inline ran", s.eval("document.title"), "bypass let the inline script run");
        csp.ok("csp", "--bypass=false");
    }

    @Test
    void covers_command_net_route() throws Exception {
        Session s = session("cdp", "cdp");
        routes(s);
        JsonNode failed = s.row(0, r -> "failed".equals(r.path("subkind").asText()) && r.path("url").asText().endsWith("/api/data"));
        assertTrue(failed.path("text").asText().contains("Blocked") || failed.has("level"), failed.toString());
        assertTrue(Files.isRegularFile(s.project().resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")));
    }

    @Test
    void covers_command_net_unroute() throws Exception {
        Session s = session("unroute", "cdp");
        s.ok("net-route", "--match=*/nothing/*", "--action=abort", "--id=a1");
        s.ok("net-route", "--match=*/nothing2/*", "--action=abort", "--id=a2");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsumerReadyCommands.hostRoutes(s.project(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String doctor = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(doctor.contains("routes=2") && doctor.contains("route.a1=abort */nothing/* via=cdp"), doctor);
        assertEquals(1, s.ok("net-unroute", "--id=a1").path("left").asInt());
        PwCommandLiveTest.Capture unknown = s.run("net-unroute", "--id=zz");
        assertEquals(1, unknown.code());
        assertTrue(unknown.err().contains("no route zz"), unknown.err());
        assertEquals(0, s.ok("net-unroute", "--all").path("left").asInt());
    }

    @Test
    void covers_command_net_block() throws Exception {
        Session s = session("block", "cdp");
        s.ok("browser-events", "--start");
        s.go("/");
        JsonNode block = s.ok("net-block", "--match=*/api/data");
        assertEquals("abort", block.path("route").path("action").asText(), block.toString());
        assertTrue(s.eval("window.api()").startsWith("ERR"));
        s.ok("net-unroute", "--all");
        assertTrue(s.eval("window.api()").startsWith("200:"));
        assertTrue(Files.isDirectory(s.project().resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_net_headers() throws Exception {
        headersAndOffline(session("headers", "cdp"));
        assertTrue(Files.isDirectory(temp.resolve("headers").resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_emulate() throws Exception {
        Session s = session("throttle", "cdp");
        s.go("/");
        JsonNode slow = s.ok("emulate", "--network=50,50,600");
        assertEquals("50,50,600", slow.path("network").asText(), slow.toString());
        long t0 = System.currentTimeMillis();
        assertTrue(s.eval("window.api()").startsWith("200:"));
        assertTrue(System.currentTimeMillis() - t0 >= 550, "latency applied");
        s.ok("emulate", "--network=online");
        PwCommandLiveTest.Capture bad = s.run("emulate", "--network=warp");
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("--network is offline"), bad.err());
        assertTrue(Files.isDirectory(s.project().resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_net_auth() throws Exception {
        authAndCsp(session("auth", "cdp"));
        assertTrue(Files.isDirectory(temp.resolve("auth").resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_csp() throws Exception {
        Session s = session("csp", "cdp");
        JsonNode reply = s.ok("csp", "--bypass=false");
        assertFalse(reply.path("bypass").asBoolean(), reply.toString());
        assertTrue(Files.isDirectory(s.project().resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_net_cache() throws Exception {
        Session s = session("cache", "cdp");
        s.go("/");
        assertEquals("disabled", s.ok("net-cache", "--disable").path("cache").asText());
        assertTrue(s.eval("window.api()").startsWith("200:"));
        assertEquals("enabled", s.ok("net-cache", "--enable").path("cache").asText());
        assertTrue(Files.isDirectory(s.project().resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_security() throws Exception {
        Session s = session("security", "cdp");
        JsonNode on = s.ok("security", "--ignore-cert-errors");
        assertTrue(on.path("ignoreCertErrors").asBoolean(), on.toString());
        JsonNode off = s.ok("security", "--ignore-cert-errors=false");
        assertFalse(off.path("ignoreCertErrors").asBoolean(), off.toString());
        assertTrue(Files.readString(s.project().resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("security ignore-cert-errors=true"));
    }

    @Test
    void covers_command_ws_route() throws Exception {
        Session s = session("ws", "playwright");
        s.go("/");
        JsonNode reply = s.ok("ws-route", "--match=*/mock-socket", "--rules=[{\"on\":\"ping\",\"reply\":\"pong from mock\"}]");
        assertEquals("playwright", reply.path("via").asText(), reply.toString());
        s.go("/");
        assertEquals("pong from mock", s.eval("window.sock('ws://127.0.0.1:9/mock-socket')"), "ws-route replied without a server");
        Session cdp = session("ws-cdp", "cdp");
        PwCommandLiveTest.Capture unsupported = cdp.run("ws-route", "--match=*/x", "--rules=[]");
        assertEquals(1, unsupported.code());
        assertTrue(unsupported.err().contains("--via=playwright"), unsupported.err());
    }

    @Test
    void playwrightBackend() throws Exception {
        Session s = session("pw", "playwright");
        routes(s);
        headersAndOffline(s);
        PwCommandLiveTest.Capture auth = s.run("net-auth", "--credential=prop:pkb.test.none");
        assertEquals(1, auth.code(), "missing ref fails before any backend");
    }

    @Test
    void bidiBackend() throws Exception {
        bidiBrowser();
        Session s = session("bidi", "bidi");
        routes(s);
        s.ok("emulate", "--network=offline");
        assertTrue(s.eval("window.api()").startsWith("ERR"), "BiDi offline");
        s.ok("emulate", "--network=online");
        authAndCsp(s);
    }
}
