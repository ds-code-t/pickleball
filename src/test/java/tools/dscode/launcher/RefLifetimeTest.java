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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A browser ref is the node, not the page mutation count.
 * Kills "any mutation stales" and "stale() never true".
 */
class RefLifetimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIX = "run snap (or find) again for a fresh ref";

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void refSurvivesUnrelatedChangesAndStalesWithItsNode() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String origin = "http://127.0.0.1:" + port;
        server.createContext("/page", exchange -> write(exchange, PAGE.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/other", exchange -> write(exchange, OTHER.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=" + origin + "/page",
                    "--browser=chrome-headless", "--run-id=ref-life");
            assertEquals(0, started.code, started.err + started.out);

            String ref = saveRef(snap());
            eval("document.getElementById('rows').insertAdjacentHTML('beforeend','<li>row</li>');"
                    + "document.getElementById('clock').textContent='9';");
            Capture painted = run(
                    "highlight", "--target=browser:auto", "--handle=save",
                    "--preset=yellow", "--run-id=ref-life");
            assertEquals(0, painted.code, painted.err + painted.out);

            Capture acted = run("act", "--target=" + ref, "--kind=click", "--run-id=ref-life");
            assertEquals(0, acted.code, acted.err + acted.out);
            JsonNode body = JSON.readTree(acted.out);
            assertTrue(body.path("applied").asBoolean(), acted.out);
            assertFalse(body.has("error"), acted.out);
            assertEquals("Saved1", eval("document.title"), acted.out);
            assertEquals("1", eval("String(window.__n)"), acted.out);

            eval("history.pushState({}, '', location.pathname + '?moved=1')");
            assertStale(run("act", "--target=" + ref, "--kind=click", "--run-id=ref-life"));
            assertEquals("Saved1", eval("document.title"));
            assertEquals("1", eval("String(window.__n)"));

            eval("history.pushState({}, '', location.pathname)");
            eval("document.getElementById('save').textContent='Save later'");
            assertStale(run("act", "--target=" + ref, "--kind=click", "--run-id=ref-life"));
            assertEquals("Saved1", eval("document.title"));

            String replaced = saveRef(snap());
            eval("document.getElementById('save').outerHTML='<button id=\"save\">Save</button>';"
                    + "document.getElementById('save').addEventListener('click', function(){"
                    + "window.__extra=(window.__extra||0)+1; document.title='Extra'+window.__extra;});");
            assertStale(run("act", "--target=" + replaced, "--kind=click", "--run-id=ref-life"));
            assertEquals("0", eval("String(window.__extra||0)"));
            assertEquals("Saved1", eval("document.title"));

            String beforeNav = saveRef(snap());
            run("eval", "--target=browser:auto", "--world=main", "--run-id=ref-life",
                    "--expr=location.assign('" + origin + "/other')");
            String title = "";
            for (int attempt = 0; attempt < 40 && !"Other".equals(title); attempt++) {
                Thread.sleep(100);
                Capture read = run(
                        "eval", "--target=browser:auto", "--world=main", "--run-id=ref-life",
                        "--expr=document.title");
                if (read.code == 0) title = JSON.readTree(read.out).path("value").asText("");
            }
            assertEquals("Other", title);
            assertStale(run("act", "--target=" + beforeNav, "--kind=click", "--run-id=ref-life"));
            assertEquals("Other", eval("document.title"));
        } finally {
            server.stop(0);
        }
    }

    private void assertStale(Capture acted) throws Exception {
        assertEquals(1, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertEquals("stale_ref", body.path("error").asText(), acted.out);
        assertEquals(FIX, body.path("fix").asText(), acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertTrue(acted.err.contains("fix: " + FIX), acted.err);
        assertFalse(acted.err.contains("Usage: act"), acted.err);
    }

    private JsonNode snap() throws Exception {
        Capture shot = run("snap", "--target=browser:auto", "--run-id=ref-life");
        assertEquals(0, shot.code, shot.err + shot.out);
        return JSON.readTree(shot.out);
    }

    private static String saveRef(JsonNode body) {
        String ref = "";
        for (JsonNode line : body.path("lines")) {
            String text = line.asText();
            if (text.contains("\"Save\"") || text.contains("\"Save later\"")) {
                int start = text.indexOf("[ref=");
                int end = text.indexOf(']', start);
                if (start > 0 && end > start) ref = text.substring(start + 5, end);
            }
        }
        assertTrue(ref.startsWith("ref:"), body.toString());
        return ref;
    }

    private String eval(String expression) throws Exception {
        Capture read = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=ref-life", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private static final String PAGE = """
            <!DOCTYPE html><html><head><title>Home</title></head><body>
            <button id="save">Save</button>
            <span id="clock">0</span>
            <ul id="rows"></ul>
            <script>
            let n = 0;
            document.getElementById('save').addEventListener('click', () => {
              n++;
              window.__n = n;
              document.title = 'Saved' + n;
            });
            </script>
            </body></html>
            """;

    private static final String OTHER = """
            <!DOCTYPE html><html><head><title>Other</title></head><body>
            <button id="save">Save</button>
            <script>
            document.getElementById('save').addEventListener('click', () => { document.title = 'Clicked'; });
            </script>
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
