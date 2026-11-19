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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 5: a click in a same-origin frame, a cross-site frame, and a nested cross-site frame
 * changes the page once. A control that cannot be clicked stays applied false.
 */
class FrameActTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void actInSameOriginOopifAndNestedFrameReadsTheEffect() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String childUrl = "http://localhost:" + port + "/child";
        String grandUrl = "http://127.0.0.1:" + port + "/grand";
        server.createContext("/parent", exchange -> write(exchange, """
                <!DOCTYPE html><html><head><title>Ready</title>
                <script>
                window.__hook = 'missing';
                addEventListener('message', (e) => {
                  if (!e.data) return;
                  if (e.data.hook) window.__hook = String(e.data.hook);
                  if (e.data.title) document.title = String(e.data.title);
                });
                </script>
                </head><body><p>Parent</p></body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/child", exchange -> write(exchange, child(grandUrl)));
        server.createContext("/grand", exchange -> write(exchange, """
                <!DOCTYPE html><html><body><button id="grand">Grand</button>
                <script>
                let n = 0;
                document.getElementById('grand').onclick = () => {
                  n++;
                  document.getElementById('grand').textContent = 'Grand:' + n;
                  top.postMessage({title: 'Grand:' + n}, '*');
                };
                </script>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/parent",
                    "--browser=chrome-headless", "--run-id=frame-act");
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("navigated=true"), started.out);

            Capture built = run(
                    "eval", "--target=browser:auto", "--world=main", "--run-id=frame-act",
                    "--expr=" + blankAndChild(childUrl));
            assertEquals(0, built.code, built.err + built.out);
            assertEquals("ok", JSON.readTree(built.out).path("value").asText(), built.out);

            JsonNode listed = awaitFrames();
            String main = frameWithParent(listed, "");
            String child = frameUrl(listed, "/child");
            String grand = frameUrl(listed, "/grand");
            assertFalse(main.isBlank(), listed.toString());
            assertFalse(child.isBlank(), listed.toString());
            assertFalse(grand.isBlank(), listed.toString());
            assertNotEquals(main, child, listed.toString());
            assertNotEquals(child, grand, listed.toString());
            assertEquals(main, parentOf(listed, child), listed.toString());
            assertEquals(child, parentOf(listed, grand), listed.toString());
            assertNoBoot(listed);

            String hook = awaitHook();
            assertEquals("undefined", hook, "page world saw __pkbOopif");

            Capture blank = run("act", "--target=browser:auto", "--query=BlankBtn", "--kind=click", "--run-id=frame-act");
            assertEquals(0, blank.code, blank.err + blank.out);
            assertTrue(JSON.readTree(blank.out).path("applied").asBoolean(), blank.out);
            assertEquals("Blank:1", title(), blank.out);

            Capture nope = run("act", "--target=browser:auto", "--query=Nope", "--kind=click", "--run-id=frame-act");
            assertEquals(1, nope.code, nope.err + nope.out);
            JsonNode refused = JSON.readTree(nope.out);
            assertFalse(refused.path("applied").asBoolean(), nope.out);
            assertEquals(child, refused.path("frame").asText(""), nope.out);
            assertEquals("Blank:1", title(), nope.out);

            Capture found = run("find", "--target=browser:auto", "--query=Inner", "--run-id=frame-act");
            assertEquals(0, found.code, found.err + found.out);
            JsonNode hit = JSON.readTree(found.out);
            assertEquals("Inner", hit.path("hits").get(0).path("name").asText(), found.out);
            assertEquals(child, hit.path("hits").get(0).path("frame").asText(""), found.out);
            assertEquals("coverage", hit.path("hits").get(0).path("via").asText(""), found.out);
            assertEquals("diff", hit.path("contexts").asText(""), found.out);
            assertTrue(hit.path("contextDiff").asText("").contains("oopif " + child), found.out);

            Capture inspect = run("inspect-page", "--target=browser:auto", "--run-id=frame-act");
            assertEquals(0, inspect.code, inspect.err + inspect.out);
            JsonNode shot = JSON.readTree(inspect.out);
            assertEquals("diff", shot.path("contexts").asText(""), inspect.out);
            assertTrue(shot.path("contextDiff").asText("").contains("oopif " + child), inspect.out);

            Capture inner = run("act", "--target=browser:auto", "--query=Inner", "--kind=click", "--run-id=frame-act");
            assertEquals(0, inner.code, inner.err + inner.out);
            assertTrue(JSON.readTree(inner.out).path("applied").asBoolean(), inner.out);
            assertEquals(child, JSON.readTree(inner.out).path("frame").asText(""), inner.out);
            assertEquals("Inner:1", awaitTitle("Inner:1"), inner.out);

            Capture entered = run("frame", "--target=browser:auto", "--enter=cross", "--run-id=frame-act");
            assertEquals(0, entered.code, entered.err + entered.out);
            assertEquals("cross", JSON.readTree(entered.out).path("entered").asText(""), entered.out);

            Capture scoped = run("act", "--target=browser:auto", "--query=Entered", "--kind=click", "--run-id=frame-act");
            assertEquals(0, scoped.code, scoped.err + scoped.out);
            JsonNode scopedBody = JSON.readTree(scoped.out);
            assertTrue(scopedBody.path("applied").asBoolean(), scoped.out);
            assertEquals(child, scopedBody.path("frame").asText(""), scoped.out);
            assertNotEquals("cross", scopedBody.path("frame").asText(""), scoped.out);
            assertEquals("Entered:1", awaitTitle("Entered:1"), scoped.out);

            Capture reset = run("frame", "--target=browser:auto", "--reset", "--run-id=frame-act");
            assertEquals(0, reset.code, reset.err + reset.out);

            Capture nested = run("find", "--target=browser:auto", "--query=Grand", "--run-id=frame-act");
            assertEquals(0, nested.code, nested.err + nested.out);
            JsonNode grandHit = JSON.readTree(nested.out);
            assertEquals("Grand", grandHit.path("hits").get(0).path("name").asText(), nested.out);
            assertEquals(grand, grandHit.path("hits").get(0).path("frame").asText(""), nested.out);
            assertEquals("coverage", grandHit.path("hits").get(0).path("via").asText(""), nested.out);

            Capture grandAct = run("act", "--target=browser:auto", "--query=Grand", "--kind=click", "--run-id=frame-act");
            assertEquals(0, grandAct.code, grandAct.err + grandAct.out);
            assertTrue(JSON.readTree(grandAct.out).path("applied").asBoolean(), grandAct.out);
            assertEquals(grand, JSON.readTree(grandAct.out).path("frame").asText(""), grandAct.out);
            assertEquals("Grand:1", awaitTitle("Grand:1"), grandAct.out);
        } finally {
            server.stop(0);
        }
    }

    private JsonNode awaitFrames() throws Exception {
        JsonNode last = JSON.readTree("{}");
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture listed = run("frame", "--target=browser:auto", "--list", "--run-id=frame-act");
            assertEquals(0, listed.code, listed.err + listed.out);
            last = JSON.readTree(listed.out);
            if (!frameUrl(last, "/child").isBlank() && !frameUrl(last, "/grand").isBlank()) return last;
            Thread.sleep(250);
        }
        return last;
    }

    private String awaitHook() throws Exception {
        String hook = "missing";
        for (int attempt = 0; attempt < 20; attempt++) {
            hook = eval("window.__hook");
            if (!"missing".equals(hook)) return hook;
            Thread.sleep(100);
        }
        return hook;
    }

    private String awaitTitle(String expected) throws Exception {
        String seen = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            seen = title();
            if (expected.equals(seen)) return seen;
            Thread.sleep(100);
        }
        return seen;
    }

    private String title() throws Exception {
        return eval("document.title");
    }

    private String eval(String expression) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=frame-act", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private static String frameWithParent(JsonNode listed, String parentId) {
        for (JsonNode frame : listed.path("frames")) {
            if (parentId.equals(frame.path("parentId").asText("x"))) return frame.path("id").asText("");
        }
        return "";
    }

    private static String frameUrl(JsonNode listed, String path) {
        for (JsonNode frame : listed.path("frames")) {
            String url = frame.path("url").asText("");
            if (!url.contains(path) || !frame.path("session").asBoolean(false)) continue;
            return frame.path("id").asText("");
        }
        return "";
    }

    private static String parentOf(JsonNode listed, String id) {
        for (JsonNode frame : listed.path("frames")) {
            if (id.equals(frame.path("id").asText(""))) return frame.path("parentId").asText("");
        }
        return "";
    }

    private static void assertNoBoot(JsonNode listed) {
        for (JsonNode frame : listed.path("frames")) {
            assertFalse(frame.has("boot"), listed.toString());
        }
    }

    private static byte[] child(String grandUrl) {
        return ("""
                <!DOCTYPE html><html><head>
                <script>
                document.documentElement.dataset.ran = 'page';
                parent.postMessage({hook: typeof window.__pkbOopif}, '*');
                </script>
                </head><body>
                <button id="inner">Inner</button>
                <button id="entered">Entered</button>
                <button id="nope" disabled>Nope</button>
                <iframe id="nest" src="%s"></iframe>
                <script>
                let innerN = 0;
                let enteredN = 0;
                document.getElementById('inner').onclick = () => {
                  innerN++;
                  document.getElementById('inner').textContent = 'Inner:' + innerN;
                  parent.postMessage({title: 'Inner:' + innerN}, '*');
                };
                document.getElementById('entered').onclick = () => {
                  enteredN++;
                  document.getElementById('entered').textContent = 'Entered:' + enteredN;
                  parent.postMessage({title: 'Entered:' + enteredN}, '*');
                };
                document.getElementById('nope').onclick = () => {
                  parent.postMessage({title: 'Nope:1'}, '*');
                };
                </script>
                </body></html>
                """.formatted(grandUrl)).getBytes(StandardCharsets.UTF_8);
    }

    private static String blankAndChild(String childUrl) {
        return "(() => { const blank = document.createElement('iframe'); blank.id = 'blank';"
                + " document.body.appendChild(blank); const doc = blank.contentDocument;"
                + " if (!doc || !doc.body) return 'nobody';"
                + " const button = doc.createElement('button'); button.id = 'blankbtn'; button.textContent = 'BlankBtn';"
                + " let n = 0; button.onclick = () => { n++; document.title = 'Blank:' + n; button.textContent = 'Blank:' + n; };"
                + " doc.body.appendChild(button);"
                + " const frame = document.createElement('iframe'); frame.id = 'cross'; frame.src = '" + childUrl + "';"
                + " document.body.appendChild(frame); return 'ok'; })()";
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
