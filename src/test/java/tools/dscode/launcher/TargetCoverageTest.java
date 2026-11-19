package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Production-path covers for the target verbs. Each method drives the live host. */
class TargetCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_attach() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        Capture capture = run("attach", "--target=browser:auto", "--run-id=cover-attach");
        assertEquals(0, capture.code, capture.err + capture.out);
        JsonNode body = JSON.readTree(capture.out);
        assertTrue(body.path("cdp").asText("").contains("/devtools/page/"), capture.out);
        assertFalse(body.path("docVersion").asText("").isBlank(), capture.out);
        assertEquals("attach", body.path("op").asText(), capture.out);
    }

    @Test
    void covers_command_find() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        JsonNode found = find("Save");
        assertEquals("Save", found.path("hits").get(0).path("name").asText(), found.toString());
        assertTrue(found.path("ref").asText("").startsWith("ref:"), found.toString());
        assertEquals("cdp", found.path("used").asText(), found.toString());
    }

    @Test
    void covers_command_snap() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        Capture capture = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=cover-snap");
        assertEquals(0, capture.code, capture.err + capture.out);
        JsonNode body = JSON.readTree(capture.out);
        assertEquals("compact", body.path("mode").asText(), capture.out);
        assertTrue(capture.out.contains("Save"), capture.out);
        assertTrue(body.path("ref").asText("").startsWith("ref:"), capture.out);
        assertFalse(body.has("full"), capture.out);
    }

    @Test
    void covers_command_capture() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        Path png = temp.resolve("cover.png");
        Capture capture = run("capture", "--target=browser:auto", "--out=" + png, "--run-id=cover-shot");
        assertEquals(0, capture.code, capture.err + capture.out);
        byte[] bytes = Files.readAllBytes(png);
        assertTrue(bytes.length > 24, capture.out);
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]);
        assertEquals((byte) 0x4E, bytes[2]);
        assertEquals((byte) 0x47, bytes[3]);
    }

    @Test
    void covers_command_eval() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        Capture capture = run(
                "eval", "--target=browser:auto", "--world=main", "--expr=document.title", "--run-id=cover-eval");
        assertEquals(0, capture.code, capture.err + capture.out);
        JsonNode body = JSON.readTree(capture.out);
        assertEquals("main", body.path("world").asText(), capture.out);
        assertEquals("Ready", body.path("value").asText(), capture.out);
    }

    @Test
    void covers_command_explain() throws Exception {
        start("Ready");
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        Capture capture = run("explain", "--target=browser:auto", "--query=Save", "--run-id=cover-explain");
        assertEquals(0, capture.code, capture.err + capture.out);
        JsonNode report = JSON.readTree(capture.out).path("report");
        assertEquals("button", report.path("role").asText(), capture.out);
        assertEquals("Save", report.path("name").asText(), capture.out);
        assertTrue(report.has("visible") && report.has("enabled") && report.has("actionable"), capture.out);
    }

    @Test
    void covers_command_wait() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>Ready</title></head>
                <body><button id="save">Save</button></body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        Capture beforeTitle = run("eval", "--target=browser:auto", "--expr=document.title", "--world=main", "--run-id=cover-wait");
        assertEquals("Ready", JSON.readTree(beforeTitle.out).path("value").asText(), beforeTitle.out);
        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=cover-wait",
                "--expr=setTimeout(function(){document.title='Arrived'},1200); 'armed'");
        assertEquals("armed", JSON.readTree(armed.out).path("value").asText(), armed.out);
        long before = System.nanoTime();
        Capture waited = run(
                "wait", "--target=browser:auto", "--condition=title=Arrived", "--timeout=4000", "--run-id=cover-wait");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertTrue(body.path("applied").asBoolean(), waited.out);
        assertEquals("Arrived", body.path("title").asText(), waited.out);
        assertTrue(elapsedMs >= 300, "wait returned before the title changed: " + elapsedMs + " " + waited.out);
        assertTrue(body.path("elapsedMs").asLong(-1) >= 300, waited.out);
    }

    private void start(String title) throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>%s</title></head>
                <body><button id="save">Save</button></body></html>
                """.formatted(title));
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
    }

    private JsonNode find(String query) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 25; attempt++) {
            Capture capture = run("find", "--target=browser:auto", "--query=" + query, "--run-id=cover-find");
            if (capture.code == 0 && !capture.out.isBlank()) {
                last = JSON.readTree(capture.out);
                if (last.path("hits").size() > 0) return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("find missed " + query + " " + last);
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
