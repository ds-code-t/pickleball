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

/** Phase 5: a cross-site iframe is paused, marked, then resumed, and a later iframe is too. */
class OopifAutoAttachTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void crossOriginIframeIsMarkedBeforeItsPageScript() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parent", exchange -> write(exchange, """
                <!DOCTYPE html><html><head><title>Ready</title>
                <script>
                window.__hook = 'missing';
                addEventListener('message', (e) => { if (e.data && e.data.hook) window.__hook = String(e.data.hook); });
                </script>
                </head><body><p>Parent</p></body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/child", exchange -> write(exchange, child("Inner")));
        server.createContext("/late", exchange -> write(exchange, child("Late")));
        server.start();
        try {
            int port = server.getAddress().getPort();
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/parent",
                    "--browser=chrome-headless", "--run-id=oopif");
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("navigated=true"), started.out);

            String childUrl = "http://localhost:" + port + "/child";
            Capture added = run(
                    "eval", "--target=browser:auto", "--world=main", "--run-id=oopif",
                    "--expr=(() => { const frame = document.createElement('iframe'); frame.id = 'cross'; frame.src = '"
                            + childUrl + "'; document.body.appendChild(frame); return frame.src; })()");
            assertEquals(0, added.code, added.err + added.out);

            JsonNode first = awaitBoot("/child");
            String firstId = frameId(first, "/child");
            assertFalse(firstId.isBlank(), first.toString());
            String hook = "missing";
            for (int attempt = 0; attempt < 20 && "missing".equals(hook); attempt++) {
                Capture read = run(
                        "eval", "--target=browser:auto", "--world=main", "--run-id=oopif", "--expr=window.__hook");
                assertEquals(0, read.code, read.err + read.out);
                hook = JSON.readTree(read.out).path("value").asText("");
                if ("missing".equals(hook)) Thread.sleep(100);
            }
            assertEquals("undefined", hook, first.toString());

            Capture entered = run("frame", "--target=browser:auto", "--enter=cross", "--run-id=oopif");
            assertEquals(0, entered.code, entered.err + entered.out);
            assertEquals(firstId, JSON.readTree(entered.out).path("frame").asText(""), entered.out);
            Capture ui = run(
                    "eval", "--target=browser:auto", "--world=pkb", "--run-id=oopif",
                    "--expr=typeof globalThis.__pkbUi + '|' + location.href");
            assertEquals(0, ui.code, ui.err + ui.out);
            JsonNode uiBody = JSON.readTree(ui.out);
            String uiValue = uiBody.path("value").asText("");
            assertTrue(uiValue.startsWith("object|"), ui.out);
            assertTrue(uiValue.contains("/child"), ui.out);
            assertEquals(firstId, uiBody.path("frame").asText(""), ui.out);
            assertNotEquals("before-page", surfaceOf(first, "/child"), first.toString());

            Capture found = run("find", "--target=browser:auto", "--query=Inner", "--run-id=oopif");
            assertEquals(0, found.code, found.err + found.out);
            JsonNode hit = JSON.readTree(found.out);
            assertEquals("Inner", hit.path("hits").get(0).path("name").asText(), found.out);
            assertEquals(firstId, hit.path("hits").get(0).path("frame").asText(), found.out);
            assertEquals(firstId, hit.path("frame").asText(), found.out);
            assertTrue(hit.path("applied").asBoolean(), found.out);

            Capture reset = run("frame", "--target=browser:auto", "--reset", "--run-id=oopif");
            assertEquals(0, reset.code, reset.err + reset.out);

            String lateUrl = "http://localhost:" + port + "/late";
            Capture second = run(
                    "eval", "--target=browser:auto", "--world=main", "--run-id=oopif",
                    "--expr=(() => { const frame = document.createElement('iframe'); frame.id = 'late'; frame.src = '"
                            + lateUrl + "'; document.body.appendChild(frame); return frame.src; })()");
            assertEquals(0, second.code, second.err + second.out);
            JsonNode again = awaitBoot("/late");
            String lateId = frameId(again, "/late");
            assertFalse(lateId.isBlank(), again.toString());
            assertNotEquals(firstId, lateId, again.toString());
        } finally {
            server.stop(0);
        }
    }

    private JsonNode awaitBoot(String path) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture listed = run("frame", "--target=browser:auto", "--list", "--run-id=oopif");
            assertEquals(0, listed.code, listed.err + listed.out);
            last = JSON.readTree(listed.out);
            if (!frameId(last, path).isBlank()) return last;
            Thread.sleep(250);
        }
        return last == null ? JSON.readTree("{}") : last;
    }

    private static String frameId(JsonNode listed, String path) {
        for (JsonNode frame : listed.path("frames")) {
            String url = frame.path("url").asText("");
            if (!url.contains(path)) continue;
            if (!frame.path("session").asBoolean(false)) continue;
            if (frame.has("boot")) return "";
            if (!"ready".equals(frame.path("surface").asText(""))) continue;
            return frame.path("id").asText("");
        }
        return "";
    }

    private static String surfaceOf(JsonNode listed, String path) {
        for (JsonNode frame : listed.path("frames")) {
            if (frame.path("url").asText("").contains(path)) return frame.path("surface").asText("");
        }
        return "";
    }

    private static byte[] child(String label) {
        return ("<!DOCTYPE html><html><head><script>"
                + "parent.postMessage({hook: typeof window.__pkbOopif}, '*');</script></head><body><button id=\"inner\">"
                + label + "</button></body></html>").getBytes(StandardCharsets.UTF_8);
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
