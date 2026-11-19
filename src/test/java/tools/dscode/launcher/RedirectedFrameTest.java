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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A redirected cross-site iframe is matched by its frame owner.
 * A warm find of a main-frame button does not pay 1.5 seconds per iframe.
 */
class RedirectedFrameTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void redirectedIframesDoNotStallAMainFrameFind() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String land = "http://localhost:" + port + "/land";
        server.createContext("/parent", exchange -> write(exchange, parent(port).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/hop", exchange -> {
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            String n = "0";
            for (String part : query.split("&")) {
                if (part.startsWith("n=")) n = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            }
            byte[] body = ("<!DOCTYPE html><html><body><script>location.replace('"
                    + land + "?n=" + n + "');</script></body></html>").getBytes(StandardCharsets.UTF_8);
            write(exchange, body);
        });
        server.createContext("/land", exchange -> {
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            String n = "0";
            for (String part : query.split("&")) {
                if (part.startsWith("n=")) n = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            }
            byte[] body = ("<!DOCTYPE html><html><body><button id=\"land\">Land" + n + "</button></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            write(exchange, body);
        });
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/parent",
                    "--browser=chrome-headless", "--run-id=redirect");
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("navigated=true"), started.out);

            JsonNode listed = awaitLands();
            String landId = frameUrl(listed, "/land?n=2");
            assertFalse(landId.isBlank(), listed.toString());

            for (int call = 0; call < 2; call++) {
                Capture found = run("find", "--target=browser:auto", "--query=Save", "--run-id=redirect");
                assertEquals(0, found.code, found.err + found.out);
                JsonNode body = JSON.readTree(found.out);
                assertEquals("Save", body.path("hits").get(0).path("name").asText(), found.out);
                long elapsed = body.path("elapsedMs").asLong(Long.MAX_VALUE);
                assertTrue(elapsed < 1000, "call " + call + " elapsedMs=" + elapsed + " " + found.out);
            }

            Capture inner = run("find", "--target=browser:auto", "--query=Land2", "--run-id=redirect");
            assertEquals(0, inner.code, inner.err + inner.out);
            JsonNode hit = JSON.readTree(inner.out);
            assertEquals("Land2", hit.path("hits").get(0).path("name").asText(), inner.out);
            assertEquals(landId, hit.path("hits").get(0).path("frame").asText(""), inner.out);
        } finally {
            server.stop(0);
        }
    }

    private JsonNode awaitLands() throws Exception {
        JsonNode last = JSON.readTree("{}");
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture listed = run("frame", "--target=browser:auto", "--list", "--run-id=redirect");
            assertEquals(0, listed.code, listed.err + listed.out);
            last = JSON.readTree(listed.out);
            int lands = 0;
            for (JsonNode frame : last.path("frames")) {
                if (frame.path("url").asText("").contains("/land?n=") && frame.path("session").asBoolean(false)) lands++;
            }
            if (lands >= 4) return last;
            Thread.sleep(250);
        }
        return last;
    }

    private static String frameUrl(JsonNode listed, String path) {
        for (JsonNode frame : listed.path("frames")) {
            if (!frame.path("url").asText("").contains(path) || !frame.path("session").asBoolean(false)) continue;
            return frame.path("id").asText("");
        }
        return "";
    }

    private static String parent(int port) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body><button id=\"save\">Save</button>");
        for (int n = 1; n <= 4; n++) {
            html.append("<iframe id=\"hop").append(n).append("\" src=\"http://localhost:")
                    .append(port).append("/hop?n=").append(n).append("\"></iframe>");
        }
        html.append("</body></html>");
        return html.toString();
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
