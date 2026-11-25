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
import tools.dscode.common.agentcontrol.HumanLock;

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

/** E8 live (fresh Chrome per test): action chains, find --role, aria snapshots and refs, WebAuthn, extensions, WebMCP. */
class InputLiveTest {
    static final String PAGE = "<!doctype html><title>input</title><style>body{margin:0}#t{position:absolute;left:20px;top:20px;width:200px;height:30px}"
            + "#tall{height:4000px}</style><input id=t><form onsubmit='return false'>"
            + "<button id=go style='position:absolute;left:20px;top:80px' aria-describedby=gd onclick='window.clicks=(window.clicks||0)+1'>Submit</button>"
            + "<span id=gd hidden>Sends the form</span><button style='position:absolute;left:120px;top:80px'>Cancel</button></form><div id=tall></div>";
    static final String WEBAUTHN = "<!doctype html><title>passkey</title><script>"
            + "window.ceremony=async()=>{const c=await navigator.credentials.create({publicKey:{rp:{name:'pkb',id:'localhost'},"
            + "user:{id:new Uint8Array([1,2,3,4]),name:'u',displayName:'U'},challenge:new Uint8Array(32),pubKeyCredParams:[{type:'public-key',alg:-7}],"
            + "authenticatorSelection:{residentKey:'required',userVerification:'preferred'}}});"
            + "const g=await navigator.credentials.get({publicKey:{challenge:new Uint8Array(32),rpId:'localhost',allowCredentials:[{type:'public-key',id:c.rawId}],userVerification:'preferred'}});"
            + "return c.id===g.id?'ok:'+c.id:'mismatch'};</script>";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static HttpServer childServer;
    static String base;
    static String childBase;
    static int port;
    static String endpoint;

    @BeforeAll
    static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String html = path.startsWith("/webauthn") ? WEBAUTHN
                    : path.startsWith("/framed")
                    ? "<!doctype html><title>framed</title><button id=go>Submit</button><button>Cancel</button><iframe id=child src=\""
                    + childBase + "/child\"></iframe>"
                    : PAGE;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;
        childServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        childServer.createContext("/child", exchange -> {
            byte[] body = "<!doctype html><title>child</title><button id=inner>Inner Submit</button>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        childServer.start();
        childBase = "http://127.0.0.1:" + childServer.getAddress().getPort();
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
        if (childServer != null) childServer.stop(0);
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

    static PwCommandLiveTest.Capture run(Path project, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return PwCommandLiveTest.agentIn(project, all.toArray(String[]::new));
    }

    static String eval(Path project, String expr) throws Exception {
        return ok(project, "pw", "page", "eval", "--expr=" + expr).path("value").asText();
    }

    static void go(Path project, String url) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + url);
    }

    static String log(Path project) throws Exception {
        return Files.readString(project.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log"));
    }

    static final String TYPE_AND_SCROLL = "[{\"type\":\"pointer\",\"id\":\"m\",\"parameters\":{\"pointerType\":\"mouse\"},\"actions\":["
            + "{\"type\":\"pointerMove\",\"x\":60,\"y\":35},{\"type\":\"pointerDown\",\"button\":0},{\"type\":\"pointerUp\",\"button\":0}]},"
            + "{\"type\":\"key\",\"id\":\"k\",\"actions\":[{\"type\":\"pause\"},{\"type\":\"pause\"},{\"type\":\"pause\"},"
            + "{\"type\":\"keyDown\",\"value\":\"h\"},{\"type\":\"keyUp\",\"value\":\"h\"},{\"type\":\"keyDown\",\"value\":\"i\"},{\"type\":\"keyUp\",\"value\":\"i\"}]},"
            + "{\"type\":\"wheel\",\"id\":\"w\",\"actions\":[{\"type\":\"pause\"},{\"type\":\"pause\"},{\"type\":\"pause\"},{\"type\":\"pause\"},{\"type\":\"pause\"},"
            + "{\"type\":\"pause\"},{\"type\":\"pause\"},{\"type\":\"scroll\",\"x\":300,\"y\":300,\"deltaX\":0,\"deltaY\":600}]}]";

    @Test
    void covers_command_input() throws Exception {
        Path p = project("input");
        go(p, base + "/");
        JsonNode cdp = ok(p, "input", "--actions=" + TYPE_AND_SCROLL);
        assertEquals("cdp", cdp.path("via").asText(), cdp.toString());
        assertEquals(18, cdp.path("actions").asInt(), "every action entry, pauses included: " + cdp);
        assertEquals("hi", eval(p, "document.getElementById('t').value"));
        long deadline = System.currentTimeMillis() + 3000;
        while ("0".equals(eval(p, "String(Math.round(scrollY))")) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertNotEquals("0", eval(p, "String(Math.round(scrollY))"), "wheel scrolled the page");
        eval(p, "scrollTo(0,0),document.getElementById('t').value=''");
        Files.createDirectories(p);
        Files.writeString(p.resolve("chain.json"), "{\"actions\":" + TYPE_AND_SCROLL + "}");
        JsonNode pw = ok(p, "input", "--actions=@chain.json", "--via=playwright");
        assertEquals("playwright", pw.path("via").asText());
        assertEquals("hi", eval(p, "document.getElementById('t').value"));
        // A held Shift is let go by --release.
        ok(p, "input", "--actions=[{\"type\":\"key\",\"id\":\"k\",\"actions\":[{\"type\":\"keyDown\",\"value\":\"\\uE008\"}]}]", "--via=cdp");
        assertTrue(ok(p, "input", "--release", "--via=cdp").path("released").asBoolean());
        assertTrue(log(p).contains("input actions=18"), "logged without values");
        PwCommandLiveTest.Capture bad = run(p, "input", "--actions=[{\"type\":\"gamepad\",\"id\":\"g\",\"actions\":[]}]");
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("not key, pointer, wheel or none"), bad.err());
    }

    @Test
    void inputHumanTierIsCoordinateAndKeyOnly() {
        assertTrue(HumanLock.inputIsHuman(temp, TYPE_AND_SCROLL));
        assertFalse(HumanLock.inputIsHuman(temp, "[{\"type\":\"pointer\",\"id\":\"m\",\"actions\":[{\"type\":\"pointerMove\",\"x\":0,\"y\":0,"
                + "\"origin\":{\"type\":\"element\",\"element\":{\"sharedId\":\"x\"}}}]}]"), "an element origin is not human input");
        assertFalse(HumanLock.inputIsHuman(temp, "not json"));
    }

    @Test
    void findRoleCdpAndPlaywright() throws Exception {
        Path p = project("findrole");
        go(p, base + "/");
        JsonNode cdp = ok(p, "find", "--role=button", "--name=Submit");
        assertEquals("cdp", cdp.path("via").asText(), cdp.toString());
        assertEquals(1, cdp.path("count").asInt(), cdp.toString());
        assertEquals("go", cdp.path("items").path(0).path("id").asText());
        assertEquals("role", cdp.path("items").path(0).path("source").asText());
        JsonNode pw = ok(p, "find", "--role=button", "--name=Submit", "--description=Sends the form", "--via=playwright");
        assertEquals("playwright", pw.path("via").asText());
        assertEquals(1, pw.path("count").asInt(), pw.toString());
        assertEquals("Sends the form", pw.path("items").path(0).path("description").asText());
        assertEquals(2, ok(p, "find", "--role=button").path("count").asInt());
        assertEquals(0, ok(p, "find", "--role=button", "--name=Submit", "--description=nothing like it").path("count").asInt());
    }

    /** L2: find --role searches every frame unless --frame=top. */
    @Test
    void findRoleSearchesEveryFrame() throws Exception {
        Path p = project("framed-role");
        go(p, base + "/framed");
        JsonNode all = null;
        for (int i = 0; i < 25; i++) {
            all = ok(p, "find", "--role=button");
            if (all.path("count").asInt() >= 3) break;
            Thread.sleep(200);
        }
        assertTrue(all.path("count").asInt() >= 3, all.toString());
        JsonNode named = ok(p, "find", "--role=button", "--name=Inner Submit");
        assertEquals(1, named.path("count").asInt(), named.toString());
        JsonNode top = ok(p, "find", "--role=button", "--frame=top");
        assertEquals(2, top.path("count").asInt(), top.toString());
        JsonNode pw = ok(p, "find", "--role=button", "--name=Inner Submit", "--via=playwright");
        assertEquals(1, pw.path("count").asInt(), pw.toString());
    }

    @Test
    void findRoleBidi() throws Exception {
        String binary = dev.domengine.session.BrowserSession.findChrome();
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        com.fasterxml.jackson.databind.node.ObjectNode empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        org.openqa.selenium.chrome.ChromeOptions options = tools.dscode.common.driver.DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        org.openqa.selenium.chrome.ChromeDriver driver = new org.openqa.selenium.chrome.ChromeDriver(options);
        Path p = temp.resolve("findrole-bidi");
        try {
            String bidi = String.valueOf(driver.getCapabilities().getCapability("webSocketUrl"));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> goog = (java.util.Map<String, Object>) driver.getCapabilities().getCapability("goog:chromeOptions");
            String cdp = "http://" + goog.get("debuggerAddress");
            BrowserEventsLiveTest.ok(p, "pw", "page", "goto", "--url=" + base + "/", "--cdp-endpoint=" + cdp);
            JsonNode found = BrowserEventsLiveTest.ok(p, "find", "--role=button", "--name=Submit", "--via=bidi", "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + bidi);
            assertEquals("bidi", found.path("via").asText(), found.toString());
            assertEquals(1, found.path("count").asInt(), found.toString());
            assertEquals("go", found.path("items").path(0).path("id").asText());
            assertEquals("Sends the form", found.path("items").path(0).path("description").asText());
            JsonNode typed = BrowserEventsLiveTest.ok(p, "input", "--actions=" + TYPE_AND_SCROLL, "--via=bidi", "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + bidi);
            assertEquals("bidi", typed.path("via").asText());
            assertEquals("hi", BrowserEventsLiveTest.ok(p, "pw", "page", "eval", "--expr=document.getElementById('t').value", "--cdp-endpoint=" + cdp).path("value").asText());
        } finally {
            PwCommandLiveTest.agentIn(p, "browser-events", "--stop");
            PlaywrightConnection.closeAll();
            driver.quit();
        }
    }

    @Test
    void covers_command_aria_snap() throws Exception {
        Path p = project("aria");
        go(p, base + "/");
        JsonNode snap = ok(p, "aria-snap", "--boxes");
        assertEquals("playwright", snap.path("via").asText(), snap.toString());
        assertTrue(snap.path("snapshot").asText().contains("button \"Submit\""), snap.toString());
        JsonNode ai = ok(p, "snap", "--aria", "--mode=ai");
        String text = ai.path("snapshot").asText();
        java.util.regex.Matcher ref = java.util.regex.Pattern.compile("button \"Submit\"[^\\n]*\\[ref=(e\\d+)]").matcher(text);
        assertTrue(ref.find(), text);
        JsonNode act = ok(p, "act", "--aria-ref=" + ref.group(1), "--kind=click");
        assertTrue(act.path("applied").asBoolean(), act.toString());
        assertEquals("1", eval(p, "String(window.clicks)"));
        assertTrue(Files.isDirectory(p.resolve(".pickleball")));
    }

    @Test
    void covers_command_webauthn() throws Exception {
        Path p = project("webauthn");
        go(p, "http://localhost:" + port + "/webauthn");
        JsonNode auth = ok(p, "webauthn", "--add-authenticator", "--resident", "--uv");
        assertEquals("cdp", auth.path("via").asText(), auth.toString());
        assertTrue(eval(p, "window.ceremony()").startsWith("ok:"), "create and get completed with the virtual authenticator");
        JsonNode list = ok(p, "webauthn", "--list");
        assertEquals(1, list.path("count").asInt(), list.toString());
        assertEquals("localhost", list.path("credentials").path(0).path("rpId").asText());
        Files.writeString(p.resolve("cred.json"), "{\"rpId\":\"localhost\",\"id\":\"cHJlbG9hZGVk\",\"userHandle\":\"dXNlcg\"}");
        assertEquals("cHJlbG9hZGVk", ok(p, "webauthn", "--add-credential=@cred.json").path("id").asText());
        list = ok(p, "webauthn", "--list");
        assertEquals(2, list.path("count").asInt(), list.toString());
        assertFalse(list.toString().contains("privateKey"), "no key material in replies");
        assertEquals(1, ok(p, "webauthn", "--remove", "--id=cHJlbG9hZGVk").path("removed").asInt());
        assertEquals(1, ok(p, "webauthn", "--list").path("count").asInt());
        Files.writeString(p.resolve("bad.json"), "{\"rpId\":\"localhost\",\"privateKey\":\"AAAA\"}");
        PwCommandLiveTest.Capture refused = run(p, "webauthn", "--add-credential=@bad.json");
        assertEquals(1, refused.code());
        assertTrue(refused.err().contains("not in the credential JSON"), refused.err());
        assertFalse(log(p).contains("AAAA"));
    }

    @Test
    void webauthnPlaywright() throws Exception {
        Path p = project("webauthn-pw");
        go(p, "http://localhost:" + port + "/webauthn");
        JsonNode auth = ok(p, "webauthn", "--add-authenticator", "--via=playwright");
        assertEquals("playwright", auth.path("via").asText(), auth.toString());
        assertTrue(eval(p, "window.ceremony()").startsWith("ok:"), "Playwright credentials() serve the ceremony");
        JsonNode list = ok(p, "webauthn", "--list", "--via=playwright");
        assertTrue(list.path("count").asInt() >= 1, list.toString());
        assertFalse(list.toString().contains("privateKey"));
    }

    @Test
    void covers_command_extension() throws Exception {
        String binary = dev.domengine.session.BrowserSession.findChrome();
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null, "skipped: no Chrome binary on this machine");
        Path profile = temp.resolve("profile-ext");
        Process process = new ProcessBuilder(binary, "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--no-first-run",
                "--enable-unsafe-extension-debugging", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank")
                .redirectErrorStream(true).redirectOutput(temp.resolve("profile-ext.log").toFile()).start();
        started.add(process);
        Path portFile = profile.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !(Files.isRegularFile(portFile) && !Files.readAllLines(portFile).isEmpty())) Thread.sleep(50);
        endpoint = "http://127.0.0.1:" + Files.readAllLines(portFile).get(0).trim();
        Path p = temp.resolve("ext");
        projects.add(p);
        Files.createDirectories(p.resolve("fixture-ext"));
        Files.writeString(p.resolve("fixture-ext/manifest.json"), "{\"manifest_version\":3,\"name\":\"pkb fixture\",\"version\":\"1.0\",\"background\":{\"service_worker\":\"sw.js\"}}");
        Files.writeString(p.resolve("fixture-ext/sw.js"), "self.loaded=1");
        JsonNode installed = ok(p, "extension", "--install=fixture-ext");
        String id = installed.path("id").asText();
        assertEquals(32, id.length(), installed.toString());
        JsonNode list = ok(p, "extension", "--list");
        assertTrue(list.toString().contains(id) && list.toString().contains("pkb fixture"), list.toString());
        ok(p, "extension", "--uninstall=" + id);
        assertFalse(ok(p, "extension", "--list").toString().contains(id));
        PwCommandLiveTest.Capture missing = run(p, "extension", "--install=nowhere");
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("no manifest.json"), missing.err());
        assertTrue(log(p).contains("extension install"));
    }

    @Test
    void covers_command_webmcp() throws Exception {
        Path p = project("webmcp");
        go(p, base + "/");
        for (String via : List.of("--via=playwright", "--via=auto")) {
            PwCommandLiveTest.Capture listed = run(p, "webmcp", "--list", via);
            if (listed.code() == 0) {
                assertTrue(listed.out().contains("\"tools\""), listed.out());
            } else {
                assertEquals(1, listed.code(), listed.err());
                assertTrue(listed.err().contains("unsupported: webmcp"), listed.err());
                assertFalse(listed.err().contains("Exception"), "reported, not a crash: " + listed.err());
            }
        }
        assertTrue(Files.isDirectory(p.resolve(".pickleball")));
    }
}
