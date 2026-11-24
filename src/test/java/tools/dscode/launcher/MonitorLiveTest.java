package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E10 live (fresh Chrome per test): audits, security, service workers, workers, storage rows, window.open. */
class MonitorLiveTest {
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
            String path = exchange.getRequestURI().getPath();
            String type = "text/html";
            String body;
            switch (path) {
                case "/csp" -> {
                    exchange.getResponseHeaders().add("Content-Security-Policy", "script-src 'self'");
                    body = "<!doctype html><title>csp</title><script>window.inline=1</script>";
                }
                case "/sw-page" -> body = "<!doctype html><title>sw</title><script>navigator.serviceWorker.register('/sw.js')"
                        + ".then(()=>navigator.serviceWorker.ready).then(()=>{window.swReady=1})</script>";
                case "/sw.js" -> {
                    type = "text/javascript";
                    body = "self.addEventListener('install',e=>e.waitUntil(new Promise(r=>setTimeout(r,1500)).then(()=>fetch('/from-sw?x=1'))));"
                            + "self.addEventListener('activate',e=>e.waitUntil(clients.claim()));";
                }
                case "/worker-page" -> body = "<!doctype html><title>worker</title><script>window.w=new Worker('/worker.js')</script>";
                case "/worker.js" -> {
                    type = "text/javascript";
                    body = "setTimeout(()=>console.log('from worker'),1000);setInterval(()=>{},1000)";
                }
                default -> body = "<!doctype html><title>" + path + "</title><p>page</p>";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
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
        ok(project, "browser-events", "--start");
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

    static void go(Path project, String url) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + url);
    }

    static JsonNode row(Path project, String kind, Predicate<JsonNode> want, String... extra) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        List<JsonNode> seen = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            List<String> args = new ArrayList<>(List.of("browser-events", "--since=0", "--kinds=" + kind, "--wait-ms=500", "--limit=500"));
            args.addAll(List.of(extra));
            JsonNode reply = ok(project, args.toArray(String[]::new));
            seen.clear();
            for (JsonNode r : reply.path("rows")) {
                seen.add(r);
                if (want.test(r)) return r;
            }
        }
        throw new AssertionError("no " + kind + " row in " + seen);
    }

    @Test
    void covers_command_audits() throws Exception {
        Path p = project("audits");
        go(p, base + "/csp");
        JsonNode issue = row(p, "audit", r -> r.path("method").asText().contains("ContentSecurityPolicy"));
        assertEquals("warning", issue.path("level").asText());
        JsonNode listed = ok(p, "audits", "--list");
        assertTrue(listed.path("count").asInt() >= 1, listed.toString());
        JsonNode forms = ok(p, "audits", "--check-forms");
        assertEquals("check-forms", forms.path("mode").asText());
        assertTrue(Files.isDirectory(p.resolve(".pickleball")));
    }

    @Test
    void certificateErrorIsASecurityRow() throws Exception {
        Path keystore = temp.resolve("test-cert.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process gen = new ProcessBuilder(keytool, "-genkeypair", "-alias", "pkb", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        assertEquals(0, gen.waitFor(), new String(gen.getInputStream().readAllBytes()));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ssl));
        https.createContext("/", exchange -> {
            byte[] body = "<!doctype html><title>tls</title>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        https.start();
        try {
            Path p = project("security");
            run(p, "pw", "page", "goto", "--url=https://127.0.0.1:" + https.getAddress().getPort() + "/");
            JsonNode security = row(p, "security", r -> "certificate-error".equals(r.path("subkind").asText()));
            assertTrue(security.path("text").asText().contains("ERR_CERT"), security.toString());
            assertEquals("error", security.path("level").asText());
        } finally {
            https.stop(0);
        }
    }

    @Test
    void covers_command_sw() throws Exception {
        Path p = project("sw");
        go(p, base + "/sw-page");
        JsonNode list = ok(p, "sw", "--list", "--wait-ms=10000");
        assertEquals(1, list.path("count").asInt(), "the page's worker; the browser's extension workers need --all: " + list);
        assertTrue(ok(p, "sw", "--list", "--all").path("count").asInt() >= 1);
        assertTrue(list.path("registrations").path(0).path("scope").asText().startsWith(base), list.toString());
        row(p, "sw", r -> "version".equals(r.path("subkind").asText()));
        JsonNode fetched = row(p, "net", r -> r.path("url").asText().contains("/from-sw") && "service_worker".equals(r.path("target").asText()));
        assertFalse(fetched.path("context").asText().isBlank(), fetched.toString());
        assertTrue(ok(p, "sw", "--bypass").path("bypass").asBoolean());
        assertFalse(ok(p, "sw", "--bypass=false").path("bypass").asBoolean());
        ok(p, "sw", "--unregister=" + base + "/");
        row(p, "sw", r -> "unregistered".equals(r.path("subkind").asText()));
        assertTrue(Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("sw unregister scope=" + base + "/"));
    }

    @Test
    void workerConsoleJoinsTheStream() throws Exception {
        Path p = project("worker");
        go(p, base + "/worker-page");
        JsonNode console = row(p, "console", r -> r.path("text").asText().contains("from worker"));
        assertEquals("worker", console.path("target").asText(), console.toString());
    }

    @Test
    void storageRowsKeepKeysOnly() throws Exception {
        Path p = project("storage-rows");
        go(p, base + "/page");
        ok(p, "pw", "page", "eval", "--expr=localStorage.setItem('token','s3cret-value-77'),1");
        JsonNode added = row(p, "storage", r -> "token".equals(r.path("key").asText()));
        assertEquals("[redacted]", added.path("value").asText(), added.toString());
        assertEquals(15, added.path("valueLength").asInt());
        JsonNode revealed = row(p, "storage", r -> "token".equals(r.path("key").asText()), "--reveal");
        assertEquals("s3cret-value-77", revealed.path("value").asText());
        try (var files = Files.walk(p.resolve(".pickleball"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.ISO_8859_1).contains("s3cret-value-77"), "never spilled or logged: " + file);
            }
        }
    }

    @Test
    void windowOpenIsATargetRow() throws Exception {
        Path p = project("window-open");
        go(p, base + "/page");
        ok(p, "pw", "page", "eval", "--expr=window.open('/opened','w2'),1");
        JsonNode opened = row(p, "target", r -> "window-open".equals(r.path("subkind").asText()));
        assertTrue(opened.path("url").asText().contains("/opened"), opened.toString());
        row(p, "target", r -> "page-attached".equals(r.path("subkind").asText()) && r.path("url").asText().contains("/opened"));
    }
}
