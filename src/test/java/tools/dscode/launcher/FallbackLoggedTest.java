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
        FallbackLog.resetForTests();
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_act() throws Exception {
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

        JsonNode found = find("Save", "plain");
        Capture acted = run("act", "--target=" + found.path("ref").asText(), "--kind=click", "--run-id=plain");
        assertEquals(0, acted.code, acted.err + acted.out);
        assertTrue(JSON.readTree(acted.out).path("applied").asBoolean(), acted.out);
        Path log = temp.resolve(".pickleball/logs/fallback-chain.jsonl");
        if (Files.isRegularFile(log)) {
            String text = Files.readString(log);
            assertFalse(text.contains("\"run\":\"plain\""), text);
        }

        JsonNode saved = find("Saved", "fell");
        Capture fallback = run(
                "act", "--target=" + saved.path("ref").asText(), "--kind=click",
                "--cdp-input=off", "--run-id=fell");
        assertEquals(0, fallback.code, fallback.err + fallback.out);
        assertTrue(JSON.readTree(fallback.out).path("applied").asBoolean(), fallback.out);
        assertTrue(Files.isRegularFile(log), "fallback log");
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"chain\":\"act\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"from\":\"cdp\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"to\":\"js\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"at\":"), lines.get(0));

        run("act", "--target=" + saved.path("ref").asText(), "--kind=click", "--cdp-input=off", "--run-id=fell");
        assertEquals(1, Files.readAllLines(log).size(), "the same run, chain, and reason is one line");
    }

    @Test
    void covers_command_watch() throws Exception {
        FallbackLog.resetForTests();
        System.setProperty("pkb_fallback_log", "off");
        Capture capture = run("find", "--target=browser:auto", "--query=Save", "--run-id=watch-off");
        assertEquals(1, capture.code, capture.out + capture.err);
        assertFalse(Files.exists(temp.resolve(".pickleball/logs/fallback-chain.jsonl")));
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
