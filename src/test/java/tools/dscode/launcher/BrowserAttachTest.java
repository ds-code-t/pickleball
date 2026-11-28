package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D13: attach routes, discovery, doctor --attach, token redaction, relaunch refusal, and live CDP joins. */
class BrowserAttachTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "pw-ext-token-0123456789abcdef";

    @TempDir
    Path temp;

    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void reset() {
        BrowserAttach.probes = BrowserAttach.LIVE;
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        tools.dscode.browser.PlaywrightConnection.closeAll();
    }

    /** Simulated machine: env, files, open ports, and /json/version answers. */
    static class Fake implements BrowserAttach.Probes {
        final Map<String, String> env = new HashMap<>();
        final Map<Path, String> files = new HashMap<>();
        final Set<Path> dirs = new java.util.HashSet<>();
        final Set<Integer> ports = new java.util.HashSet<>();
        final Map<String, String> versions = new HashMap<>();
        String os = "linux";
        Path home = Path.of("/home/u");

        public String os() { return os; }
        public Path home() { return home; }
        public String env(String name) { return env.get(name); }
        public boolean exists(Path path) { return files.containsKey(path) || dirs.contains(path); }
        public Optional<String> read(Path path) { return Optional.ofNullable(files.get(path)); }
        public boolean portOpen(String host, int port) { return "127.0.0.1".equals(host) && ports.contains(port); }
        public String jsonVersion(String base) { return versions.get(base.replaceAll("/$", "")); }
    }

    @Test
    void channelDirsFollowPlaywrightsLayoutPerOs() {
        Fake fake = new Fake();
        assertEquals(Path.of("/home/u/.config/google-chrome"), BrowserAttach.userDataDir("chrome", fake));
        assertEquals(Path.of("/home/u/.config/microsoft-edge-dev"), BrowserAttach.userDataDir("msedge-dev", fake));
        fake.env.put("XDG_CONFIG_HOME", "/cfg");
        assertEquals(Path.of("/cfg/google-chrome-unstable"), BrowserAttach.userDataDir("chrome-dev", fake));
        fake.os = "darwin";
        assertEquals(Path.of("/home/u/Library/Application Support/Google/Chrome Canary"), BrowserAttach.userDataDir("chrome-canary", fake));
        fake.os = "win32";
        fake.env.put("LOCALAPPDATA", "C:/Users/u/AppData/Local");
        assertEquals(Path.of("C:/Users/u/AppData/Local/Microsoft/Edge SxS/User Data"), BrowserAttach.userDataDir("msedge-canary", fake));
        assertEquals(null, BrowserAttach.userDataDir("firefox", fake));
    }

    @Test
    void routeOrderAndDoctorReportUseSimulatedProbesAndNeverPrintTheToken() {
        Fake fake = new Fake();
        Path chrome = Path.of("/home/u/.config/google-chrome");
        fake.files.put(chrome.resolve("DevToolsActivePort"), "9555\n/devtools/browser/abc\n");
        fake.ports.add(9555);
        fake.dirs.add(chrome);
        fake.dirs.add(chrome.resolve("Default").resolve("Extensions").resolve(BrowserAttach.EXTENSION_ID));
        fake.env.put(BrowserAttach.TOKEN_ENV, TOKEN);
        fake.ports.add(9222);
        fake.versions.put("http://127.0.0.1:9222", "ws://127.0.0.1:9222/devtools/browser/x");
        JsonNode routes = BrowserAttach.routes(fake, true);
        List<String> order = new ArrayList<>();
        routes.forEach(route -> order.add(route.path("route").asText()));
        assertEquals(List.of("channel", "extension", "endpoints", "selenium", "relaunch", "firefox", "webkit"), order);
        assertEquals("chrome", routes.get(0).path("available").get(0).asText(), routes.toString());
        assertEquals("attach --cdp-endpoint=chrome", routes.get(0).path("use").asText());
        assertEquals("present", routes.get(1).path("token").asText());
        assertEquals("chrome/Default", routes.get(1).path("installed").get(0).asText());
        JsonNode found = routes.get(2).path("discovered");
        assertEquals(2, found.size(), found.toString());
        assertEquals("channel:chrome", found.get(0).path("source").asText());
        assertEquals("ws://127.0.0.1:9555/devtools/browser/abc", found.get(0).path("endpoint").asText());
        assertEquals("port:9222", found.get(1).path("source").asText());
        assertFalse(routes.toString().contains(TOKEN), "the extension token is never reported");

        Fake none = new Fake();
        JsonNode missing = BrowserAttach.routes(none, true);
        assertTrue(missing.get(0).path("fix").asText().contains("chrome://inspect/#remote-debugging"), missing.toString());
        assertTrue(missing.get(0).path("fix").asText().contains("Allow remote debugging for this browser instance"));
        assertTrue(missing.get(1).path("fix").asText().contains("Chrome Web Store"), missing.toString());
        assertEquals("absent", missing.get(1).path("token").asText());
        assertTrue(missing.get(2).path("fix").asText().contains("--remote-debugging-port"), missing.toString());
        assertTrue(missing.get(5).path("note").asText().contains("WebDriver BiDi"));
    }

    /** PKB-HCR 2026-11-28 item 19: the live start browser has its own debug port and was not discovered. */
    @Test
    void discoverListsTheProjectsLiveStartBrowserFirst() {
        Fake fake = new Fake() {
            @Override
            public String liveStartCdp(Path project) {
                return "ws://127.0.0.1:9333/devtools/browser/live";
            }
        };
        fake.ports.add(9222);
        fake.versions.put("http://127.0.0.1:9222", "ws://127.0.0.1:9222/devtools/browser/x");
        List<com.fasterxml.jackson.databind.node.ObjectNode> found = BrowserAttach.discover(fake, Path.of("/p"));
        assertEquals("live-start", found.get(0).path("source").asText(), found.toString());
        assertEquals("ws://127.0.0.1:9333/devtools/browser/live", found.get(0).path("endpoint").asText());
        assertEquals("port:9222", found.get(1).path("source").asText(), found.toString());
        assertEquals(1, BrowserAttach.discover(fake).size(), "no project, no live start candidate");
    }

    @Test
    void aStaleActivePortFileOrNonLocalPortIsNotACandidate() {
        Fake fake = new Fake();
        Path chrome = Path.of("/home/u/.config/google-chrome");
        fake.files.put(chrome.resolve("DevToolsActivePort"), "9555\n/devtools/browser/abc\n");
        assertTrue(BrowserAttach.discover(fake).isEmpty(), "closed port is stale");
        fake.files.put(chrome.resolve("DevToolsActivePort"), "not-a-port\n");
        fake.ports.add(9555);
        assertEquals(null, BrowserAttach.activePort(chrome, fake));
    }

    @Test
    void channelWithoutRemoteDebuggingFailsWithTheInspectFix() throws Exception {
        BrowserAttach.probes = new Fake();
        Capture capture = agent("attach", "--cdp-endpoint=msedge");
        assertEquals(1, capture.code, capture.out);
        assertTrue(capture.err.startsWith("error: no msedge with remote debugging allowed"), capture.err);
        assertTrue(capture.err.contains("fix: open chrome://inspect/#remote-debugging"), capture.err);
        Capture none = agent("attach", "--discover");
        assertEquals(0, none.code, none.err);
        assertEquals(0, JSON.readTree(none.out).path("candidates").size());
        Capture bad = agent("attach", "--discover", "--candidate=3");
        assertEquals(1, bad.code);
        assertTrue(bad.err.contains("no candidate 3"), bad.err);
    }

    @Test
    void extensionTokenIsNeverPrintedOrLogged() throws Exception {
        Fake fake = new Fake();
        fake.env.put(BrowserAttach.TOKEN_ENV, TOKEN);
        BrowserAttach.probes = fake;
        Capture doctor = agent("doctor", "--attach");
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("attachRoutes=channel:no,extension:no,endpoints:no,selenium:yes"), doctor.out);
        assertTrue(doctor.out.contains("attach=["), doctor.out);
        assertFalse(doctor.out.contains(TOKEN) || doctor.err.contains(TOKEN));
        Capture plain = agent("doctor");
        assertTrue(plain.out.contains("attachRoutes=") && plain.out.contains("(doctor --attach for discovery and fixes)"), plain.out);
        assertFalse(plain.out.contains("attach=["), "plain doctor does not probe ports");
        BrowserAttach.log(temp, "extension:chrome", "ws://127.0.0.1:1/x?token=" + TOKEN);
        String log = Files.readString(temp.resolve(".pickleball/logs/attach.log"));
        assertFalse(log.contains(TOKEN), log);
        assertTrue(log.contains("token=<redacted>"), log);
        assertEquals("chrome", BrowserAttach.extensionChannel(new String[]{"attach", "p", "--extension"}));
        assertEquals("msedge", BrowserAttach.extensionChannel(new String[]{"attach", "p", "--extension=msedge"}));
    }

    @Test
    void covers_command_relaunch_with_debug() throws Exception {
        Capture refused = agent("relaunch-with-debug", "--browser=chrome");
        assertEquals(2, refused.code, refused.out);
        assertTrue(refused.err.startsWith("error: relaunch-with-debug needs --confirm"), refused.err);
        assertTrue(refused.err.contains("unsaved tabs and forms is lost"), refused.err);
        JsonNode plan = JSON.readTree(refused.out);
        assertFalse(plan.path("relaunched").asBoolean(), refused.out);
        assertEquals(9222, plan.path("port").asInt());
        Capture firefox = agent("relaunch-with-debug", "--browser=firefox", "--confirm");
        assertEquals(2, firefox.code);
        assertTrue(firefox.err.contains("WebDriver BiDi"), firefox.err);
        assertTrue(Files.notExists(temp.resolve(".pickleball/logs/attach.log")), "nothing was relaunched or logged");
    }

    @Test
    void liveChannelAndExplicitEndpointJoinTheExistingCdpAttach() throws Exception {
        Path profile = temp.resolve("google-chrome");
        String http = PlaywrightCdpLiveTest.startChrome(profile, started);
        Fake fake = new Fake() {
            @Override
            public boolean portOpen(String host, int port) {
                return BrowserAttach.LIVE.portOpen(host, port);
            }

            @Override
            public Optional<String> read(Path path) {
                return BrowserAttach.LIVE.read(path);
            }

            @Override
            public boolean exists(Path path) {
                return BrowserAttach.LIVE.exists(path);
            }

            @Override
            public String jsonVersion(String base) {
                return BrowserAttach.LIVE.jsonVersion(base);
            }
        };
        fake.env.put("CHROME_USER_DATA_DIR", profile.toString());
        BrowserAttach.probes = fake;

        Capture channel = agent("attach", "--cdp-endpoint=chrome", "--run-id=d13-channel");
        assertEquals(0, channel.code, channel.err + channel.out);
        assertTrue(channel.err.contains("attach: route=channel:chrome endpoint=ws://127.0.0.1:"), channel.err);
        JsonNode body = JSON.readTree(channel.out);
        assertEquals("attach", body.path("op").asText(), channel.out);
        assertFalse(body.path("docVersion").asText("").isBlank(), channel.out);

        Capture explicit = agent("attach", "--cdp-endpoint=" + http, "--run-id=d13-explicit");
        assertEquals(0, explicit.code, explicit.err + explicit.out);
        assertTrue(explicit.err.contains("route=cdp-endpoint"), explicit.err);

        Capture discovered = agent("attach", "--discover");
        JsonNode candidates = JSON.readTree(discovered.out).path("candidates");
        assertTrue(candidates.size() >= 1, discovered.out);
        assertEquals("channel:chrome", candidates.get(0).path("source").asText(), discovered.out);
        Capture picked = agent("attach", "--discover", "--candidate=1", "--run-id=d13-pick");
        assertEquals(0, picked.code, picked.err + picked.out);

        String log = Files.readString(temp.resolve(".pickleball/logs/attach.log"));
        assertTrue(log.contains("channel:chrome") && log.contains("cdp-endpoint") && log.contains("discover:channel:chrome"), log);
    }

    Capture agent(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
