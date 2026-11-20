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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One act click runs the page handler once. A quiet click is not reported as a refused click.
 * Kills a second click when the title and DOM stay still, and a second click before an async DOM update.
 */
class ClickOnceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void oneActFiresEachHandlerOnce() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String childUrl = "http://localhost:" + port + "/child";
        String grandUrl = "http://127.0.0.1:" + port + "/grand";
        server.createContext("/main", exchange -> write(exchange, mainPage(childUrl)));
        server.createContext("/child", exchange -> write(exchange, childPage(grandUrl)));
        server.createContext("/grand", exchange -> write(exchange, grandPage()));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/main",
                    "--browser=chrome-headless", "--run-id=click-once");
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("navigated=true"), started.out);

            awaitFind("Inner");
            awaitFind("Grand");

            assertQuiet("Count", "String(window.cnt)");
            assertQuiet("OpenOne", "String(window.openClicks)");
            assertQuiet("PlainDiv", "String(window.plainClicks)");
            assertQuiet("Hidden", "String(window.hiddenClicks)");
            assertQuiet("BlankBtn", "String(document.getElementById('blank').contentWindow.bk)");
            assertQuietFrame("Inner", "/child", "String(window.k)");
            assertQuietFrame("Grand", "/grand", "String(window.g)");

            Capture async = run(
                    "act", "--target=browser:auto", "--query=AsyncSave", "--kind=click",
                    "--snap=diff", "--run-id=click-once");
            assertEquals(0, async.code, async.err + async.out);
            JsonNode asyncBody = JSON.readTree(async.out);
            assertTrue(asyncBody.path("ok").asBoolean(), async.out);
            assertTrue(asyncBody.path("applied").asBoolean(), async.out);
            assertTrue(asyncBody.path("dispatched").asBoolean(), async.out);
            assertTrue(asyncBody.path("visible").asBoolean(), async.out);
            assertFalse(asyncBody.has("error"), async.out);
            assertEquals("1", eval("String(window.as)"), async.out);
            assertEquals("saved", eval("document.getElementById('asyncOut').textContent"), async.out);
            String diffText = joined(asyncBody.path("snap").path("updated")) + joined(asyncBody.path("snap").path("added"));
            assertTrue(diffText.contains("\"saved\""), async.out);
            assertFalse(diffText.contains("\"idle\""), async.out);
            assertTrue(asyncBody.path("snap").path("changed").asBoolean(), async.out);

            assertRefused("Off", "disabled", "String(window.offClicks)");
            assertRefused("Still", "inert", "String(window.stillClicks)");
        } finally {
            server.stop(0);
        }
    }

    private void assertQuiet(String query, String counterExpr) throws Exception {
        Capture acted = run("act", "--target=browser:auto", "--query=" + query, "--kind=click", "--run-id=click-once");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("ok").asBoolean(), acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertTrue(body.path("dispatched").asBoolean(), acted.out);
        assertFalse(body.path("visible").asBoolean(), acted.out);
        assertEquals("no visible change", body.path("reason").asText(), acted.out);
        assertFalse(body.has("error"), acted.out);
        assertEquals("1", eval(counterExpr), acted.out);
    }

    private void assertQuietFrame(String query, String urlPart, String counterExpr) throws Exception {
        Capture found = run("find", "--target=browser:auto", "--query=" + query, "--run-id=click-once");
        assertEquals(0, found.code, found.err + found.out);
        String frame = JSON.readTree(found.out).path("hits").get(0).path("frame").asText("");
        assertFalse(frame.isBlank(), found.out);
        Capture acted = run("act", "--target=browser:auto", "--query=" + query, "--kind=click", "--run-id=click-once");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("ok").asBoolean(), acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertTrue(body.path("dispatched").asBoolean(), acted.out);
        assertFalse(body.path("visible").asBoolean(), acted.out);
        assertEquals("no visible change", body.path("reason").asText(), acted.out);
        assertFalse(body.has("error"), acted.out);
        assertEquals(frame, body.path("frame").asText(""), acted.out);
        assertEquals("1", frameEval(urlPart, counterExpr), acted.out);
    }

    private void assertRefused(String query, String reason, String counterExpr) throws Exception {
        Capture acted = run("act", "--target=browser:auto", "--query=" + query, "--kind=click", "--run-id=click-once");
        assertEquals(1, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertFalse(body.path("ok").asBoolean(), acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertFalse(body.path("dispatched").asBoolean(), acted.out);
        assertEquals("could not land", body.path("error").asText(), acted.out);
        assertEquals(reason, body.path("reason").asText(), acted.out);
        assertEquals("0", eval(counterExpr), acted.out);
    }

    private void awaitFind(String query) throws Exception {
        JsonNode last = JSON.readTree("{}");
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture found = run("find", "--target=browser:auto", "--query=" + query, "--run-id=click-once");
            if (found.code == 0 && !found.out.isBlank()) {
                last = JSON.readTree(found.out);
                if (last.path("hits").size() > 0) return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("find missed " + query + " " + last);
    }

    private String eval(String expression) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=click-once", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    /** Reads the expression on the iframe target whose URL contains {@code urlPart}. */
    private String frameEval(String urlPart, String expression) throws Exception {
        LiveHostFile.Host host = LiveHostFile.read(temp).orElseThrow();
        HttpRequest health = HttpRequest.newBuilder(URI.create(trim(host.url()) + "/health"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + host.token())
                .GET()
                .build();
        HttpResponse<String> healthBody = HTTP.send(health, HttpResponse.BodyHandlers.ofString());
        String pageSocket = JSON.readTree(healthBody.body()).path("cdp").asText("");
        assertFalse(pageSocket.isBlank(), healthBody.body());
        URI socket = URI.create(pageSocket);
        String base = "http://" + socket.getHost() + ":" + socket.getPort();
        HttpResponse<String> version = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/json/version")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String browserSocket = JSON.readTree(version.body()).path("webSocketDebuggerUrl").asText("");
        assertFalse(browserSocket.isBlank(), version.body());
        try (CdpConnection cdp = CdpConnection.connect(URI.create(browserSocket), Duration.ofSeconds(10))) {
            JsonNode targets = cdp.call("Target.getTargets", Jsons.object(), Duration.ofSeconds(5));
            String targetId = "";
            for (JsonNode info : targets.path("targetInfos")) {
                if (info.path("url").asText("").contains(urlPart)) {
                    targetId = info.path("targetId").asText("");
                    break;
                }
            }
            assertFalse(targetId.isBlank(), targets.toString());
            ObjectNode attach = Jsons.object();
            attach.put("targetId", targetId);
            attach.put("flatten", true);
            String sessionId = cdp.call("Target.attachToTarget", attach, Duration.ofSeconds(5)).path("sessionId").asText("");
            assertFalse(sessionId.isBlank(), "no session for " + urlPart);
            ObjectNode params = Jsons.object();
            params.put("expression", expression);
            params.put("returnByValue", true);
            JsonNode value = cdp.call("Runtime.evaluate", params, sessionId, Duration.ofSeconds(5));
            return value.path("result").path("value").asText("");
        }
    }

    private static String joined(JsonNode lines) {
        StringBuilder text = new StringBuilder();
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) text.append(line.asText("")).append('\n');
        }
        return text.toString();
    }

    private static String trim(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
    }

    private static byte[] mainPage(String childUrl) {
        return ("""
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="count">Count</button>
                <div id="plain" role="button">PlainDiv</div>
                <button id="async">AsyncSave</button>
                <span id="asyncOut">idle</span>
                <button id="off" disabled>Off</button>
                <button id="still" inert>Still</button>
                <div id="openhost"></div>
                <div id="closedhost"></div>
                <iframe id="blank"></iframe>
                <iframe id="cross" src="%s"></iframe>
                <script>
                window.cnt = 0;
                window.plainClicks = 0;
                window.openClicks = 0;
                window.hiddenClicks = 0;
                window.offClicks = 0;
                window.stillClicks = 0;
                window.as = 0;
                document.getElementById('count').onclick = function () { window.cnt++; };
                document.getElementById('plain').onclick = function () { window.plainClicks++; };
                document.getElementById('off').onclick = function () { window.offClicks++; };
                document.getElementById('still').onclick = function () { window.stillClicks++; };
                document.getElementById('async').onclick = function () {
                  window.as++;
                  setTimeout(function () { document.getElementById('asyncOut').textContent = 'saved'; }, 150);
                };
                var open = document.getElementById('openhost').attachShadow({mode:'open'});
                var openButton = document.createElement('button');
                openButton.textContent = 'OpenOne';
                openButton.onclick = function () { window.openClicks++; };
                open.appendChild(openButton);
                var closedRoot = document.getElementById('closedhost').attachShadow({mode:'closed'});
                var hidden = document.createElement('button');
                hidden.textContent = 'Hidden';
                hidden.onclick = function () { window.hiddenClicks++; };
                closedRoot.appendChild(hidden);
                var blank = document.getElementById('blank');
                var doc = blank.contentDocument;
                var blankButton = doc.createElement('button');
                blankButton.textContent = 'BlankBtn';
                doc.defaultView.bk = 0;
                blankButton.onclick = function () { doc.defaultView.bk++; };
                doc.body.appendChild(blankButton);
                </script>
                </body></html>
                """.formatted(childUrl)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] childPage(String grandUrl) {
        return ("""
                <!DOCTYPE html><html><body>
                <button id="inner">Inner</button>
                <iframe id="nest" src="%s"></iframe>
                <script>
                window.k = 0;
                document.getElementById('inner').onclick = function () { window.k++; };
                </script>
                </body></html>
                """.formatted(grandUrl)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] grandPage() {
        return """
                <!DOCTYPE html><html><body>
                <button id="grand">Grand</button>
                <script>
                window.g = 0;
                document.getElementById('grand').onclick = function () { window.g++; };
                </script>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
