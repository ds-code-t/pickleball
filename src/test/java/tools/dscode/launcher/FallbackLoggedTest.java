package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.FallbackLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 4: a real act fallback records from/to, and the CDP path records nothing. */
class FallbackLoggedTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        System.clearProperty("pkb_fallback_log");
        System.clearProperty("pkb_cdp_input");
        FallbackLog.resetForTests();
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_act() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        System.clearProperty("pkb_cdp_input");
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>Ready</title></head>
                <body>
                <button id="save" onclick="window.__n=(window.__n||0)+1;document.title='Saved:'+window.__n;this.textContent='Saved:'+window.__n">Save</button>
                <input id="name" placeholder="Name">
                <select id="color"><option>Red</option><option>Blue</option><option>Red</option></select>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);

        JsonNode found = find("Save", "plain");
        Capture acted = run("act", "--target=" + found.path("ref").asText(), "--kind=click", "--run-id=plain");
        assertEquals(0, acted.code, acted.err + acted.out);
        assertTrue(JSON.readTree(acted.out).path("applied").asBoolean(), acted.out);
        assertEquals("cdp", JSON.readTree(acted.out).path("used").asText(), acted.out);
        assertEquals("Saved:1", eval("document.title", "plain").path("value").asText(), acted.out);
        Path log = temp.resolve(".pickleball/logs/fallback-chain.jsonl");
        if (Files.isRegularFile(log)) {
            String text = Files.readString(log);
            assertFalse(text.contains("\"run\":\"plain\""), text);
        }

        ConsumerReadyCommands.stopQuiet(temp);
        System.setProperty("pkb_cdp_input", "off");
        Capture again = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, again.code, again.err + again.out);

        JsonNode save = find("Save", "fell");
        Capture fallback = run("act", "--target=" + save.path("ref").asText(), "--kind=click", "--run-id=fell");
        assertEquals(0, fallback.code, fallback.err + fallback.out);
        JsonNode first = JSON.readTree(fallback.out);
        assertTrue(first.path("applied").asBoolean(), fallback.out);
        assertEquals("js", first.path("used").asText(), fallback.out);
        assertEquals("Saved:1", eval("document.title", "fell").path("value").asText(), fallback.out);
        assertTrue(Files.isRegularFile(log), "fallback log");
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"chain\":\"act\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"from\":\"cdp\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"to\":\"js\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"at\":"), lines.get(0));

        JsonNode saved = find("Saved:1", "fell");
        Capture second = run("act", "--target=" + saved.path("ref").asText(), "--kind=click", "--run-id=fell");
        assertEquals(0, second.code, second.err + second.out);
        assertTrue(JSON.readTree(second.out).path("applied").asBoolean(), second.out);
        assertEquals("js", JSON.readTree(second.out).path("used").asText(), second.out);
        assertEquals("Saved:2", eval("document.title", "fell").path("value").asText(), second.out);
        assertEquals(1, Files.readAllLines(log).size(), "the same run, chain, and reason is one line");

        JsonNode named = find("Name", "fell");
        Capture typed = run(
                "act", "--target=" + named.path("ref").asText(), "--kind=type", "--value=hello", "--run-id=fell");
        assertEquals(0, typed.code, typed.err + typed.out);
        assertEquals("js", JSON.readTree(typed.out).path("used").asText(), typed.out);
        assertTrue(JSON.readTree(typed.out).path("applied").asBoolean(), typed.out);
        assertEquals("hello", eval("document.getElementById('name').value", "fell").path("value").asText(), typed.out);

        JsonNode color = find("color", "fell");
        Capture selected = run(
                "act", "--target=" + color.path("ref").asText(), "--kind=select", "--value=Blue", "--run-id=fell");
        assertEquals(0, selected.code, selected.err + selected.out);
        assertTrue(JSON.readTree(selected.out).path("applied").asBoolean(), selected.out);
        assertEquals("Blue", eval("document.getElementById('color').value", "fell").path("value").asText(), selected.out);
        assertEquals(1, Files.readAllLines(log).size(), "type and select reuse the one act fallback line");
    }

    @Test
    void covers_command_watch() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("watch.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Watch</title></head>
                <body><button id="save">Save</button></body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        String early = "pkb-early-" + System.nanoTime();
        String marker = "pkb-watch-" + System.nanoTime();
        Capture logged = run(
                "eval", "--target=browser:auto", "--world=main",
                "--expr=console.log('" + early + "')", "--run-id=watch");
        assertEquals(0, logged.code, logged.err + logged.out);
        Thread.sleep(400);
        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=watch",
                "--expr=setTimeout(function(){console.log('" + marker + "');},1500); 'armed'");
        assertEquals("armed", JSON.readTree(armed.out).path("value").asText(), armed.out);
        long startedAt = System.nanoTime();
        Capture watched = run(
                "watch", "--target=browser:auto", "--kinds=console", "--duration=4000", "--run-id=watch");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertEquals(0, watched.code, watched.err + watched.out);
        JsonNode body = JSON.readTree(watched.out);
        assertTrue(body.path("events").isArray(), watched.out);
        boolean seen = false;
        boolean sawEarly = false;
        for (JsonNode event : body.path("events")) {
            String text = event.path("text").asText("");
            if (text.contains(early)) sawEarly = true;
            if (text.contains(marker)) {
                seen = true;
                assertFalse(event.path("kind").asText("").isBlank(), event.toString());
                assertFalse(text.isBlank(), event.toString());
            }
        }
        assertFalse(sawEarly, watched.out);
        assertTrue(seen, watched.out);
        assertTrue(elapsedMs >= 1100, "watch returned before the marker: " + elapsedMs + " " + watched.out);
        assertTrue(elapsedMs < 8_000, watched.out);
    }

    private JsonNode eval(String expr, String runId) throws Exception {
        Capture capture = run("eval", "--target=browser:auto", "--expr=" + expr, "--world=main", "--run-id=" + runId);
        assertEquals(0, capture.code, capture.err + capture.out);
        return JSON.readTree(capture.out);
    }

    private JsonNode find(String query, String runId) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 25; attempt++) {
            Capture capture = run("find", "--target=browser:auto", "--query=" + query, "--run-id=" + runId);
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
