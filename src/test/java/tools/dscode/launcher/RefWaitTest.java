package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code wait} on a ref judges that node.
 * Kills "wait ignores ref": the page already contains the words visible, hidden, enabled, detached, and Ready.
 */
class RefWaitTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void refWaitSeesEachConditionAndTimesOutWhenItNeverHappens() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/page", exchange -> write(exchange, PAGE.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/page",
                    "--browser=chrome-headless", "--run-id=ref-wait");
            assertEquals(0, started.code, started.err + started.out);
            JsonNode shot = snap();
            String go = refNamed(shot, "Go");
            String show = refNamed(shot, "Show");
            assertEquals("true|true|Go", eval(
                    "var el=document.getElementById('go');el.hidden+'|'+el.disabled+'|'+el.textContent"));

            assertTimeout(go, "visible");
            assertEquals("true", eval("String(document.getElementById('go').hidden)"));
            assertTimeout(go, "enabled");
            assertEquals("true", eval("String(document.getElementById('go').disabled)"));
            assertTimeout(go, "text=Ready");
            assertEquals("Go", eval("document.getElementById('go').textContent"));
            assertTimeout(go, "detached");
            assertEquals("false", eval("String(document.getElementById('go')==null)"));
            assertTimeout(show, "hidden");
            assertEquals("false", eval("String(document.getElementById('show').hidden)"));

            eval("setTimeout(function(){document.getElementById('go').hidden=false;},1000)");
            Capture visible = wait(go, "visible", 4000);
            assertEquals(0, visible.code, visible.err + visible.out);
            assertTrue(JSON.readTree(visible.out).path("elapsedMs").asLong() >= 500, visible.out);
            assertEquals("false", eval("String(document.getElementById('go').hidden)"));

            eval("setTimeout(function(){document.getElementById('go').disabled=false;},1000)");
            Capture enabled = wait(go, "enabled", 4000);
            assertEquals(0, enabled.code, enabled.err + enabled.out);
            assertTrue(JSON.readTree(enabled.out).path("elapsedMs").asLong() >= 500, enabled.out);
            assertEquals("false", eval("String(document.getElementById('go').disabled)"));

            eval("setTimeout(function(){document.getElementById('go').textContent='Ready';},1000)");
            Capture text = wait(go, "text=Ready", 4000);
            assertEquals(0, text.code, text.err + text.out);
            assertTrue(JSON.readTree(text.out).path("elapsedMs").asLong() >= 500, text.out);
            assertEquals("Ready", eval("document.getElementById('go').textContent"));

            eval("setTimeout(function(){document.getElementById('show').hidden=true;},1000)");
            Capture hidden = wait(show, "hidden", 4000);
            assertEquals(0, hidden.code, hidden.err + hidden.out);
            assertTrue(JSON.readTree(hidden.out).path("elapsedMs").asLong() >= 500, hidden.out);
            assertEquals("true", eval("String(document.getElementById('show').hidden)"));

            eval("setTimeout(function(){document.getElementById('go').remove();},1000)");
            Capture detached = wait(go, "detached", 4000);
            assertEquals(0, detached.code, detached.err + detached.out);
            assertTrue(JSON.readTree(detached.out).path("elapsedMs").asLong() >= 500, detached.out);
            assertEquals("true", eval("String(document.getElementById('go')==null)"));
        } finally {
            server.stop(0);
        }
    }

    private void assertTimeout(String ref, String condition) throws Exception {
        Capture waited = wait(ref, condition, 400);
        assertEquals(1, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertEquals("timeout", body.path("error").asText(), waited.out);
        assertTrue(body.path("elapsedMs").asLong() >= 300, waited.out);
    }

    private Capture wait(String ref, String condition, long timeout) {
        return run(
                "wait", "--target=" + ref, "--condition=" + condition,
                "--timeout=" + timeout, "--run-id=ref-wait");
    }

    private JsonNode snap() throws Exception {
        Capture shot = run("snap", "--target=browser:auto", "--run-id=ref-wait");
        assertEquals(0, shot.code, shot.err + shot.out);
        return JSON.readTree(shot.out);
    }

    private static String refNamed(JsonNode body, String name) {
        for (JsonNode line : body.path("items")) {
            String text = line.asText();
            if (text.contains("\"" + name + "\"")) {
                int start = text.indexOf("[ref=");
                int end = text.indexOf(']', start);
                if (start > 0 && end > start) return text.substring(start + 5, end);
            }
        }
        throw new AssertionError("no ref named " + name + " in " + body);
    }

    private String eval(String expression) throws Exception {
        Capture read = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=ref-wait", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private static final String PAGE = """
            <!DOCTYPE html><html><body>
            <p id="decoy">visible hidden enabled detached Ready</p>
            <button id="show">Show</button>
            <button id="go" hidden disabled>Go</button>
            </body></html>
            """;

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
