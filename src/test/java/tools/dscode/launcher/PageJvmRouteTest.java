package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code inject}, {@code input block}, {@code lease}, and agent {@code input}
 * run in a separate {@link PickleballWorkbenchLauncher} process and land in the
 * JVM that holds the live page.
 */
class PageJvmRouteTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path project;

    private HttpServer site;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(project);
        if (site != null) site.stop(0);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void injectInputAndLeaseReachThePageJvm() throws Exception {
        String browser = BrowserSession.findChrome() != null ? "chrome-headless"
                : BrowserSession.findEdge() != null ? "edge-headless" : null;
        assertNotNull(browser, "locally installed Chrome or Edge is required");

        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/fixture", exchange -> {
            byte[] body = FIXTURE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        String url = "http://127.0.0.1:" + site.getAddress().getPort() + "/fixture";

        String session = session();
        Capture started = inProcess("start", "--url=" + url, "--browser=" + browser, "--session=" + session);
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture first = cli("inject", "on", "--session=" + session, "--agent=agent-1", "--mutate=sibling");
        assertEquals(0, first.code, first.out);
        assertFalse(first.out.contains("\"frame\":\"top\""), first.out);
        await("document.querySelectorAll('[data-pkb-agent=\"agent-1\"][data-pkb-layer]').length", "1");

        Capture second = cli("inject", "on", "--session=" + session, "--agent=agent-2");
        assertEquals(0, second.code, second.out);
        await("document.querySelectorAll('[data-pkb-layer]').length", "2");

        reload();
        await("document.querySelectorAll('[data-pkb-agent=\"agent-1\"][data-pkb-layer]').length", "1");
        await("document.querySelectorAll('[data-pkb-agent=\"agent-2\"][data-pkb-layer]').length", "1");

        Capture blocked = cli("input", "block", "--on", "--session=" + session);
        assertEquals(0, blocked.code, blocked.out);
        await("document.querySelectorAll('[data-pkb-input-block]').length", "1");
        int clicks = count("window.clicks");
        int keys = count("window.keys");
        humanClick();
        humanKey("a");
        assertEquals(clicks, count("window.clicks"), "human click reached the page while the shield was on");
        assertEquals(keys, count("window.keys"), "human key reached the page while the shield was on");

        Capture agent = cli("input", "--text=Z", "--session=" + session);
        assertEquals(0, agent.code, agent.out);
        await("window.typed", "Z");

        Capture open = cli("input", "block", "--off", "--session=" + session);
        assertEquals(0, open.code, open.out);
        await("document.querySelectorAll('[data-pkb-input-block]').length", "0");
        humanClick();
        humanKey("b");
        assertTrue(count("window.clicks") > clicks, "human click did not reach the page after input block --off");
        assertTrue(count("window.keys") > keys, "human key did not reach the page after input block --off");

        Capture lease = cli("lease", "take", "--target=ui", "--session=" + session, "--agent=holder-a");
        assertEquals(0, lease.code, lease.out);
        String held = Files.readString(project.resolve(".pickleball").resolve("workbench-ui")
                .resolve("leases").resolve(session).resolve("ui.json"));
        assertTrue(held.contains("\"holder\":\"holder-a\""), held);
        Capture refused = cli("lease", "take", "--target=ui", "--session=" + session, "--agent=holder-b");
        assertEquals(2, refused.code, refused.out);
        assertTrue(refused.out.contains("error:"), refused.out);
        assertTrue(refused.out.contains("fix:"), refused.out);
    }

    private String session() throws Exception {
        Capture created = inProcess("spine", "session", "new");
        assertEquals(0, created.code, created.err + created.out);
        return JSON.readTree(created.out).path("state").path("session").asText();
    }

    private Capture inProcess(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = project.toString();
        if (args.length > 1) System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private Capture cli(String... args) throws Exception {
        Path argFile = project.resolve(".pickleball").resolve("cli-" + System.nanoTime() + ".args");
        Files.createDirectories(argFile.getParent());
        String classpath = System.getProperty("java.class.path", "");
        StringBuilder body = new StringBuilder();
        body.append("-cp\n").append(quote(classpath)).append('\n');
        body.append("tools.dscode.launcher.PickleballWorkbenchLauncher\n");
        for (String arg : args) body.append(quote(arg)).append('\n');
        body.append(quote(project.toAbsolutePath().normalize().toString())).append('\n');
        Files.writeString(argFile, body.toString(), StandardCharsets.UTF_8);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaBin.toString(), "@" + argFile.toAbsolutePath())
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return new Capture(1, output + "\ncli timed out", "");
        }
        return new Capture(process.exitValue(), output, "");
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void reload() throws Exception {
        try (CdpConnection cdp = connect()) {
            cdp.call("Page.reload", Jsons.object(), Duration.ofSeconds(10));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if ("complete".equals(eval("document.readyState"))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("page did not finish reloading");
    }

    private void humanClick() throws Exception {
        String point = eval("(function(){var r=document.getElementById('go').getBoundingClientRect(); return (r.x+r.width/2)+','+(r.y+r.height/2);})()");
        String[] parts = point.split(",");
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        try (CdpConnection cdp = connect()) {
            cdp.call("Input.dispatchMouseEvent", mouse("mousePressed", x, y), Duration.ofSeconds(5));
            cdp.call("Input.dispatchMouseEvent", mouse("mouseReleased", x, y), Duration.ofSeconds(5));
        }
        Thread.sleep(200);
    }

    private static ObjectNode mouse(String type, double x, double y) {
        ObjectNode node = Jsons.object();
        node.put("type", type);
        node.put("x", x);
        node.put("y", y);
        node.put("button", "left");
        node.put("clickCount", 1);
        return node;
    }

    private void humanKey(String key) throws Exception {
        eval("document.body.setAttribute('tabindex','0'); document.body.focus(); 'ok'");
        try (CdpConnection cdp = connect()) {
            ObjectNode down = Jsons.object();
            down.put("type", "keyDown");
            down.put("key", key);
            down.put("text", key);
            cdp.call("Input.dispatchKeyEvent", down, Duration.ofSeconds(5));
            ObjectNode up = Jsons.object();
            up.put("type", "keyUp");
            up.put("key", key);
            cdp.call("Input.dispatchKeyEvent", up, Duration.ofSeconds(5));
        }
        Thread.sleep(200);
    }

    private void await(String expression, String expected) throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            last = eval(expression);
            if (expected.equals(last)) return;
            Thread.sleep(250);
        }
        throw new AssertionError(expression + " stayed " + last + " wanted " + expected);
    }

    private int count(String expression) throws Exception {
        return Integer.parseInt(eval(expression));
    }

    private String eval(String expression) throws Exception {
        try (CdpConnection cdp = connect()) {
            ObjectNode params = Jsons.object();
            params.put("expression", expression);
            params.put("returnByValue", true);
            JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
            return value.path("result").path("value").asText("");
        }
    }

    private CdpConnection connect() throws Exception {
        LiveHostFile.Host host = LiveHostFile.read(project).orElseThrow();
        HttpRequest health = HttpRequest.newBuilder(URI.create(trim(host.url()) + "/health"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + host.token())
                .GET()
                .build();
        HttpResponse<String> body = HTTP.send(health, HttpResponse.BodyHandlers.ofString());
        String socket = JSON.readTree(body.body()).path("cdp").asText("");
        assertFalse(socket.isBlank(), body.body());
        return CdpConnection.connect(URI.create(socket), Duration.ofSeconds(10));
    }

    private static String trim(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
    }

    private static final String FIXTURE = """
            <!DOCTYPE html><html><body>
            <button id="go">Go</button>
            <script>
            window.clicks = 0;
            window.keys = 0;
            window.typed = '';
            document.getElementById('go').addEventListener('click', function () { window.clicks++; });
            document.addEventListener('keydown', function (event) { window.keys++; window.typed += event.key || ''; });
            </script>
            </body></html>
            """;

    private record Capture(int code, String out, String err) {
    }
}
