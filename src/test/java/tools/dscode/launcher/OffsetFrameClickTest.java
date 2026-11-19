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
 * A same-origin iframe whose document point is not the parent viewport point.
 * Kills "skip clickInPlace": that click lands on Decoy, and a second click would make Inner:2.
 */
class OffsetFrameClickTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void offsetSameOriginFrameClickLandsOnce() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/parent", exchange -> write(exchange, parent(port).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/frame", exchange -> write(exchange, FRAME.getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/parent",
                    "--browser=chrome-headless", "--run-id=offset-frame");
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("navigated=true"), started.out);

            Capture acted = run(
                    "act", "--target=browser:auto", "--query=Inner", "--kind=click", "--run-id=offset-frame");
            assertEquals(0, acted.code, acted.err + acted.out);
            JsonNode body = JSON.readTree(acted.out);
            assertTrue(body.path("applied").asBoolean(), acted.out);
            assertTrue(body.path("dispatched").asBoolean(), acted.out);
            assertFalse(body.has("error"), acted.out);

            String read = eval(
                    "document.getElementById('box').contentDocument.getElementById('inner').textContent"
                            + "+'|'+document.getElementById('decoy').textContent"
                            + "+'|'+document.getElementById('seen').textContent");
            assertEquals("Inner:1|Decoy|seen:1", read, acted.out);
        } finally {
            server.stop(0);
        }
    }

    private String eval(String expression) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=offset-frame", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private static String parent(int port) {
        return """
                <!DOCTYPE html><html><body style="margin:0">
                <button id="decoy" style="position:fixed;left:8px;top:8px;width:80px;height:30px">Decoy</button>
                <span id="seen">seen:0</span>
                <iframe id="box" src="http://127.0.0.1:%d/frame"
                    style="position:absolute;left:0;top:0;width:240px;height:160px;border:0;margin-left:360px;transform:translate(80px,40px)"></iframe>
                <script>
                let decoyN = 0;
                document.getElementById('decoy').addEventListener('click', () => {
                  decoyN++;
                  document.getElementById('decoy').textContent = 'Decoy:' + decoyN;
                });
                </script>
                </body></html>
                """.formatted(port);
    }

    private static final String FRAME = """
            <!DOCTYPE html><html><body style="margin:0">
            <button id="inner" style="position:absolute;left:8px;top:8px;width:80px;height:30px">Inner</button>
            <script>
            let innerN = 0;
            document.getElementById('inner').addEventListener('click', () => {
              innerN++;
              document.getElementById('inner').textContent = 'Inner:' + innerN;
              parent.document.getElementById('seen').textContent = 'seen:' + innerN;
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
