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
 * {@code --snap=diff} names the elements that changed.
 * Kills "changed always true" and "docVersion before -> before".
 */
class ElementSnapDiffTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void snapDiffNamesTheElementThatChanged() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/page", exchange -> write(exchange, PAGE.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/page",
                    "--browser=chrome-headless", "--run-id=element-diff");
            assertEquals(0, started.code, started.err + started.out);

            JsonNode added = act("Add", "click", "");
            assertTrue(added.path("applied").asBoolean(), added.toString());
            assertEquals(1, added.path("snap").path("added").size(), added.toString());
            String line = added.path("snap").path("added").get(0).asText();
            assertTrue(line.startsWith("- listitem \"Row 0\""), line);
            assertTrue(line.contains("[ref="), line);
            assertEquals(0, added.path("snap").path("removed").size(), added.toString());
            assertSidesDiffer(added.path("snap").path("docVersion").asText());
            assertEquals("Row 0", eval("document.querySelector('#rows li').textContent"));
            assertEquals("undefined", eval("typeof window.__pkbDocWatch"));
            assertEquals("undefined", eval("typeof window.__pkbDocCount"));
            assertEquals("object", evalWorld("typeof globalThis.__pkbDocWatch"));

            JsonNode quiet = act("Quiet", "click", "");
            assertFalse(quiet.path("applied").asBoolean(), quiet.toString());
            assertFalse(quiet.path("snap").path("changed").asBoolean(), quiet.toString());
            assertEquals(0, quiet.path("snap").path("added").size(), quiet.toString());
            assertEquals(0, quiet.path("snap").path("removed").size(), quiet.toString());
            assertEquals(0, quiet.path("snap").path("updated").size(), quiet.toString());
            assertEquals("Quiet", eval("document.getElementById('quiet').textContent"));

            JsonNode typed = act("Email", "type", "ada@b.c");
            assertTrue(typed.path("applied").asBoolean(), typed.toString());
            assertTrue(joined(typed.path("snap").path("updated")).contains("value=\"ada@b.c\""), typed.toString());
            assertEquals("ada@b.c", eval("document.getElementById('email').value"));

            JsonNode async = act("Async", "click", "");
            assertTrue(async.path("applied").asBoolean(), async.toString());
            assertTrue(async.path("snap").path("changed").asBoolean(), async.toString());
            String diffText = joined(async.path("snap").path("updated")) + joined(async.path("snap").path("added"));
            assertTrue(diffText.contains("as:1"), async.toString());
            assertEquals("as:1", eval("document.getElementById('as').textContent"));
            assertEquals("1", eval("String(window.__async)"));

            Capture failed = run(
                    "act", "--target=browser:auto", "--query=reset", "--kind=type",
                    "--value=nope", "--snap=diff", "--run-id=element-diff");
            assertEquals(1, failed.code, failed.err + failed.out);
            JsonNode reset = JSON.readTree(failed.out);
            assertEquals("not applied", reset.path("error").asText(), failed.out);
            assertTrue(joined(reset.path("snap").path("added")).contains("Row 9"), failed.out);
            assertEquals("", eval("document.getElementById('reset').value"));
            assertEquals("Row 9", eval("document.querySelectorAll('#rows li')[1].textContent"));
        } finally {
            server.stop(0);
        }
    }

    private static void assertSidesDiffer(String version) {
        int arrow = version.indexOf(" -> ");
        assertTrue(arrow > 0, version);
        assertFalse(version.substring(0, arrow).equals(version.substring(arrow + 4)), version);
    }

    private static String joined(JsonNode lines) {
        StringBuilder text = new StringBuilder();
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) text.append(line.asText()).append('\n');
        }
        return text.toString();
    }

    private JsonNode act(String query, String kind, String value) throws Exception {
        Capture acted = value.isBlank()
                ? run("act", "--target=browser:auto", "--query=" + query, "--kind=" + kind,
                "--snap=diff", "--run-id=element-diff")
                : run("act", "--target=browser:auto", "--query=" + query, "--kind=" + kind,
                "--value=" + value, "--snap=diff", "--run-id=element-diff");
        assertEquals(kind.equals("type") && "reset".equals(query) ? 1 : 0, acted.code, acted.err + acted.out);
        return JSON.readTree(acted.out);
    }

    private String eval(String expression) throws Exception {
        return evalWorld(expression, "main");
    }

    private String evalWorld(String expression) throws Exception {
        return evalWorld(expression, "pkb");
    }

    private String evalWorld(String expression, String world) throws Exception {
        Capture read = run(
                "eval", "--target=browser:auto", "--world=" + world, "--run-id=element-diff",
                "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private static final String PAGE = """
            <!DOCTYPE html><html><head><title>Ready</title></head><body>
            <button id="add">Add</button>
            <ul id="rows"></ul>
            <button id="quiet">Quiet</button>
            <input id="email" placeholder="Email">
            <button id="async">Async</button>
            <span id="as">as:0</span>
            <input id="reset">
            <script>
            document.getElementById('add').addEventListener('click', () => {
              document.getElementById('rows').insertAdjacentHTML('beforeend', '<li>Row 0</li>');
            });
            window.__async = 0;
            document.getElementById('async').addEventListener('click', () => {
              setTimeout(() => {
                window.__async++;
                document.getElementById('as').textContent = 'as:' + window.__async;
              }, 150);
            });
            document.getElementById('reset').addEventListener('input', () => {
              document.getElementById('rows').insertAdjacentHTML('beforeend', '<li>Row 9</li>');
              document.getElementById('reset').value = '';
            });
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
