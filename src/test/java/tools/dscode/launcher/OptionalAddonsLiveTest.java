package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.optional.OptionalAddons;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E12 through the CLI: optional-list/-install/-uninstall plans, not-installed errors, audit --axe with a pre-seeded
 * stand-in axe.js (live Chrome; exercises pickleball's code path, not axe itself) and the bridge config. No network.
 */
class OptionalAddonsLiveTest {
    /** Same shape as axe.run: resolves {violations:[{id, impact, help, nodes}]}. */
    static final String FAKE_AXE = "window.axe={run:async(ctx,opts)=>({violations:[...document.querySelectorAll('img:not([alt])')].length?"
            + "[{id:'image-alt',impact:'critical',help:'Images must have alternate text',nodes:[...document.querySelectorAll('img:not([alt])')].map(n=>({html:n.outerHTML}))}]:[]})};";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;
    Path cache;

    @BeforeAll
    static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "<!doctype html><title>a11y</title><img src='x.png'><img src='y.png'>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void cacheDir(org.junit.jupiter.api.TestInfo info) {
        cache = temp.resolve("cache-" + info.getTestMethod().orElseThrow().getName());
        System.setProperty("pkb_optional_dir", cache.toString());
    }

    @AfterEach
    void stopAll() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        projects.clear();
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        started.clear();
        System.clearProperty("pkb_optional_dir");
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    /** Pre-seeds an installed add-on the way optional-install --confirm leaves it. */
    void seed(String name, String relative, String body) throws Exception {
        Path file = cache.resolve(name).resolve("package").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.writeString(cache.resolve("optional.lock.json"), "{\"" + name + "\":{\"package\":\"x\",\"version\":\"0.0.0-test\",\"integrity\":\"sha512-test\"}}");
    }

    static PwCommandLiveTest.Capture cli(Path project, String... args) {
        return PwCommandLiveTest.agentIn(project, args);
    }

    @Test
    void covers_command_optional_list() throws Exception {
        Path p = temp.resolve("list");
        Files.createDirectories(p);
        int before = OptionalAddons.FETCHES.get();
        JsonNode list = BrowserEventsLiveTest.ok(p, "optional-list");
        assertEquals(3, list.path("addons").size(), list.toString());
        assertEquals("MPL-2.0", list.path("addons").path(0).path("license").asText());
        assertFalse(list.path("addons").path(0).path("installed").asBoolean());
        assertEquals("external download on install", list.path("addons").path(2).path("external").asText());
        assertFalse(Files.exists(cache), "listing creates nothing");
        assertEquals(before, OptionalAddons.FETCHES.get());
    }

    @Test
    void covers_command_optional_install() throws Exception {
        Path p = temp.resolve("install");
        Files.createDirectories(p);
        int before = OptionalAddons.FETCHES.get();
        JsonNode plan = BrowserEventsLiveTest.ok(p, "optional-install", "devtools-mcp");
        assertEquals("plan", plan.path("mode").asText(), plan.toString());
        assertEquals("chrome-devtools-mcp", plan.path("package").asText());
        assertEquals("Apache-2.0", plan.path("license").asText());
        assertTrue(plan.path("external").asText().contains("never at build, test or default runtime"));
        assertEquals("optional-install devtools-mcp --confirm", plan.path("next").asText());
        assertFalse(Files.exists(cache), "a plan writes nothing");
        assertEquals(before, OptionalAddons.FETCHES.get(), "no download without --confirm");
        PwCommandLiveTest.Capture unknown = cli(p, "optional-install", "leftpad");
        assertTrue(unknown.code() != 0 && unknown.err().contains("fix: use one of axe, lighthouse, devtools-mcp"), unknown.err());
    }

    @Test
    void covers_command_optional_uninstall() throws Exception {
        Path p = temp.resolve("uninstall");
        Files.createDirectories(p);
        seed("axe", "axe.min.js", FAKE_AXE);
        assertTrue(BrowserEventsLiveTest.ok(p, "optional-list").path("addons").path(0).path("installed").asBoolean());
        JsonNode removed = BrowserEventsLiveTest.ok(p, "optional-uninstall", "axe");
        assertTrue(removed.path("removed").asBoolean(), removed.toString());
        assertFalse(Files.exists(cache.resolve("axe")));
        assertFalse(Files.readString(cache.resolve("optional.lock.json")).contains("\"axe\""));
    }

    @Test
    void covers_command_audit() throws Exception {
        Path p = temp.resolve("audit");
        Files.createDirectories(p);
        PwCommandLiveTest.Capture missing = cli(p, "audit", "--axe");
        assertTrue(missing.code() != 0, missing.out());
        assertTrue(missing.err().contains("error: axe is not installed") && missing.err().contains("fix: pickleball optional-install axe"), missing.err());
        PwCommandLiveTest.Capture noLighthouse = cli(p, "audit", "--lighthouse");
        assertTrue(noLighthouse.code() != 0 && noLighthouse.err().contains("fix: pickleball optional-install lighthouse"), noLighthouse.err());

        seed("axe", "axe.min.js", FAKE_AXE);
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile-audit"), started);
        projects.add(p);
        BrowserEventsLiveTest.ok(p, "pw", "page", "goto", "--url=" + base + "/a11y", "--target=browser:cdp=" + endpoint);
        BrowserEventsLiveTest.ok(p, "browser-events", "--start", "--target=browser:cdp=" + endpoint);
        JsonNode audit = BrowserEventsLiveTest.ok(p, "audit", "--axe", "--out=axe.json", "--target=browser:cdp=" + endpoint);
        assertEquals("cdp", audit.path("via").asText(), audit.toString());
        assertEquals(1, audit.path("count").asInt());
        assertEquals("image-alt", audit.path("violations").path(0).path("id").asText());
        assertEquals(2, audit.path("violations").path(0).path("nodes").asInt());
        assertTrue(Files.readString(p.resolve("axe.json")).contains("Images must have alternate text"));
        JsonNode rows = BrowserEventsLiveTest.ok(p, "browser-events", "--since=0", "--kinds=audit", "--target=browser:cdp=" + endpoint).path("rows");
        boolean row = false;
        for (JsonNode r : rows) row |= "a11y".equals(r.path("subkind").asText()) && "image-alt".equals(r.path("method").asText()) && "error".equals(r.path("level").asText());
        assertTrue(row, rows.toString());
        // The isolated world keeps the page's own globals clean.
        JsonNode pageAxe = BrowserEventsLiveTest.ok(p, "pw", "page", "eval", "--expr=typeof window.axe", "--target=browser:cdp=" + endpoint);
        assertEquals("undefined", pageAxe.path("value").asText(), pageAxe.toString());
    }

    @Test
    void covers_command_bridge() throws Exception {
        Path p = temp.resolve("bridge");
        Files.createDirectories(p);
        PwCommandLiveTest.Capture missing = cli(p, "bridge", "--devtools-mcp", "--endpoint=http://127.0.0.1:9222");
        assertTrue(missing.code() != 0 && missing.err().contains("fix: pickleball optional-install devtools-mcp"), missing.err());
        seed("devtools-mcp", "out/Default/gen/src/bin/chrome-devtools-mcp.js", "// stand-in");
        JsonNode bridge = BrowserEventsLiveTest.ok(p, "bridge", "--devtools-mcp", "--endpoint=http://127.0.0.1:9222");
        JsonNode server = bridge.path("mcpConfig").path("mcpServers").path("chrome-devtools");
        List<String> args = new ArrayList<>();
        server.path("args").forEach(a -> args.add(a.asText()));
        assertTrue(args.getFirst().endsWith("chrome-devtools-mcp.js"), args.toString());
        assertTrue(args.containsAll(List.of("--browserUrl=http://127.0.0.1:9222", "--no-performance-crux", "--no-usage-statistics")), args.toString());
        assertTrue(Files.exists(Path.of(server.path("command").asText())), "the Node from Playwright's driver bundle");
        assertEquals("1", server.path("env").path("CHROME_DEVTOOLS_MCP_NO_USAGE_STATISTICS").asText());
        PwCommandLiveTest.Capture remote = cli(p, "bridge", "--devtools-mcp", "--endpoint=http://10.1.2.3:9222");
        assertTrue(remote.code() != 0 && remote.err().contains("localhost only"), remote.err());
    }
}
