package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A default snap stays inside a line and byte budget and spills the rest.
 * Kills "send every hit twice" and "walk the tree twice".
 */
class SnapBudgetTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void defaultSnapStaysInsideTheBudgetAndWithinScopesTheContainer() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("many.html");
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body>");
        html.append("<div id=\"box\"><button id=\"in\">In</button><div><button id=\"deep\">Deep</button></div></div>");
        html.append("<button id=\"out\">Out</button>");
        for (int i = 0; i < 1497; i++) {
            html.append("<button id=\"b").append(i).append("\">b").append(i).append("</button>");
        }
        html.append("</body></html>");
        Files.writeString(page, html.toString());

        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=snap-budget");
        assertEquals(0, started.code, started.err + started.out);

        Capture cold = run("snap", "--target=browser:auto", "--run-id=snap-budget");
        assertEquals(0, cold.code, cold.err + cold.out);
        Capture warm = run("snap", "--target=browser:auto", "--run-id=snap-budget");
        assertEquals(0, warm.code, warm.err + warm.out);
        JsonNode body = JSON.readTree(warm.out);
        assertTrue(warm.out.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024, warm.out.length() + " bytes");
        assertTrue(body.path("lines").size() <= 200, body.path("lines").size() + " lines");
        assertTrue(body.path("lines").size() > 1, warm.out);
        assertEquals("# button", body.path("lines").get(0).asText(), warm.out);
        assertTrue(body.path("truncated").asBoolean(), warm.out);
        assertFalse(body.has("hits"), warm.out);
        assertTrue(body.path("elapsedMs").asLong(-1) >= 0, warm.out);
        assertTrue(body.path("elapsedMs").asLong(9999) < 2000, warm.out);
        Path full = Path.of(body.path("full").asText());
        assertTrue(Files.isRegularFile(full), warm.out);
        long buttons = Files.readString(full).lines().filter(line -> line.startsWith("- button ")).count();
        assertEquals(1500, buttons, full.toString());

        Capture found = run("find", "--target=browser:auto", "--query=box", "--run-id=snap-budget");
        assertEquals(0, found.code, found.err + found.out);
        String ref = JSON.readTree(found.out).path("ref").asText();
        assertTrue(ref.startsWith("ref:"), found.out);

        Capture scoped = run("snap", "--target=browser:auto", "--within=" + ref, "--run-id=snap-budget");
        assertEquals(0, scoped.code, scoped.err + scoped.out);
        JsonNode inside = JSON.readTree(scoped.out);
        String scopedText = joined(inside.path("lines"));
        assertTrue(scopedText.contains("\"In\""), scopedText);
        assertTrue(scopedText.contains("\"Deep\""), scopedText);
        assertFalse(scopedText.contains("\"Out\""), scopedText);
        assertFalse(scopedText.contains("\"b0\""), scopedText);
        assertFalse(inside.path("truncated").asBoolean(), scopedText);
        assertEquals(2, buttonLines(inside), scopedText);

        Capture shallow = run(
                "snap", "--target=browser:auto", "--within=" + ref, "--depth=1", "--run-id=snap-budget");
        assertEquals(0, shallow.code, shallow.err + shallow.out);
        JsonNode top = JSON.readTree(shallow.out);
        String shallowText = joined(top.path("lines"));
        assertTrue(shallowText.contains("\"In\""), shallowText);
        assertFalse(shallowText.contains("\"Deep\""), shallowText);
        assertEquals(1, buttonLines(top), shallowText);
    }

    private static String joined(JsonNode lines) {
        StringBuilder text = new StringBuilder();
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) text.append(line.asText()).append('\n');
        }
        return text.toString();
    }

    private static int buttonLines(JsonNode body) {
        int count = 0;
        for (JsonNode line : body.path("lines")) {
            if (line.asText().startsWith("- button ")) count++;
        }
        return count;
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
