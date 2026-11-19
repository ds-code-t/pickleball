package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 4: snap, find, act, wait, and capture drive the live page through the CLI. */
class TargetAgnosticApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void missingBrowserAndUnknownSessionFail() throws Exception {
        Capture missing = run("find", "--target=browser:auto", "--query=Save", "--run-id=none");
        assertEquals(1, missing.code, missing.out + missing.err);
        assertTrue(missing.err.contains("error: no live browser"), missing.err);
        assertTrue(missing.out.contains("\"error\":\"no live browser\""), missing.out);
        assertFalse(missing.out.contains("\"ok\":true"), missing.out);
        Capture unknown = run("find", "--target=browser:missing-session", "--query=Save", "--run-id=missing");
        assertEquals(1, unknown.code, unknown.out + unknown.err);
        assertTrue(unknown.out.contains("unknown target"), unknown.out);
        assertFalse(unknown.out.contains("\"ok\":true"), unknown.out);
    }

    @Test
    void liveVerbsChangeThePageOnAutoCdpAndRef() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>Ready</title></head>
                <body><button id="save" onclick="document.title='Saved';this.textContent='Saved'">Save</button></body>
                </html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        JsonNode found = retryFind("browser:auto", "Save");
        assertTrue(found.path("applied").asBoolean(), found.toString());
        assertEquals("cdp", found.path("used").asText(), found.toString());
        String ref = found.path("ref").asText();
        assertTrue(ref.startsWith("ref:"), ref);

        Capture acted = run("act", "--target=" + ref, "--kind=click", "--run-id=live");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertTrue(act.path("applied").asBoolean(), acted.out);
        JsonNode title = eval("document.title");
        assertEquals("Saved", title.path("value").asText(), title.toString());

        Capture diff = run("snap", "--target=browser:auto", "--mode=diff", "--run-id=live");
        assertEquals(0, diff.code, diff.err + diff.out);
        assertTrue(diff.out.contains("Saved"), diff.out);
        assertTrue(diff.out.contains("Ready") || diff.out.contains("->"), diff.out);

        Capture waited = run("wait", "--target=browser:auto", "--condition=title=Saved", "--timeout=3000", "--run-id=live");
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode wait = JSON.readTree(waited.out);
        assertTrue(wait.path("applied").asBoolean(), waited.out);
        assertEquals("Saved", wait.path("title").asText(), waited.out);
        assertEquals("poll", wait.path("mode").asText(), waited.out);

        Path png = temp.resolve("shot.png");
        Capture shot = run("capture", "--target=browser:auto", "--out=" + png, "--run-id=live");
        assertEquals(0, shot.code, shot.err + shot.out);
        byte[] bytes = Files.readAllBytes(png);
        assertTrue(bytes.length > 8, shot.out);
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]);

        String socket = healthCdp();
        assertTrue(socket.contains("/devtools/page/"), socket);
        JsonNode again = retryFind("browser:cdp=" + socket, "Saved");
        assertTrue(again.path("applied").asBoolean(), again.toString());
        String cdpRef = again.path("ref").asText();
        Capture refAct = run("act", "--target=browser:cdp=" + socket, "--ref=" + cdpRef, "--kind=click", "--run-id=live-cdp");
        assertEquals(0, refAct.code, refAct.err + refAct.out);
        assertTrue(JSON.readTree(refAct.out).path("applied").asBoolean(), refAct.out);
        assertEquals("Saved", eval("document.title").path("value").asText());
    }

    private JsonNode retryFind(String target, String query) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 25; attempt++) {
            Capture capture = run("find", "--target=" + target, "--query=" + query, "--run-id=live");
            if (capture.code == 0 && !capture.out.isBlank()) {
                last = JSON.readTree(capture.out);
                if (last.path("hits").size() > 0) return last;
            } else {
                last = JSON.readTree(capture.out.isBlank() ? "{\"error\":\"empty\"}" : capture.out);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("find missed " + query + " " + last);
    }

    private JsonNode eval(String expr) throws Exception {
        Capture capture = run("eval", "--target=browser:auto", "--expr=" + expr, "--world=main", "--run-id=live");
        assertEquals(0, capture.code, capture.err + capture.out);
        return JSON.readTree(capture.out);
    }

    private String healthCdp() throws Exception {
        JsonNode host = JSON.readTree(temp.resolve(".pickleball/live-browser/current.json").toFile());
        HttpRequest request = HttpRequest.newBuilder(URI.create(host.path("url").asText() + "/health"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + host.path("token").asText())
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String socket = JSON.readTree(response.body()).path("cdp").asText();
        assertFalse(socket.isBlank(), response.body());
        return socket;
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
