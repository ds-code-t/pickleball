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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: overlaps name the placements on the page now, and cards stack as separate rows. */
class PlacementNowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOXES = """
            (function(){
              var out = [];
              var seen = new Set();
              function add(root) {
                if (!root || seen.has(root) || !root.querySelectorAll) return;
                seen.add(root);
                var nodes = root.querySelectorAll('[data-pkb-id]');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var id = el.getAttribute('data-pkb-id') || '';
                  if (!id) continue;
                  if (el.parentElement && el.parentElement.getAttribute('data-pkb-id') === id) continue;
                  var rect = el.getBoundingClientRect();
                  var cardEl = el.hasAttribute('data-pkb-card') ? el : el.querySelector('[data-pkb-card]');
                  out.push({
                    id: id,
                    x: rect.left,
                    y: rect.top,
                    w: rect.width,
                    h: rect.height,
                    card: cardEl ? (cardEl.getAttribute('data-pkb-card') || '') : '',
                    text: (el.innerText || '').replace(/\\s+/g, ' ').trim()
                  });
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) if (all[j].shadowRoot) add(all[j].shadowRoot);
              }
              add(document);
              return JSON.stringify(out);
            })()
            """;

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void removedOverlayDoesNotOverlapTheNextOne() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        start("gone.html", "Gone", "place-gone");
        JsonNode first = body(run(
                "overlay", "--text=Alpha box", "--x=10", "--y=10", "--width=80", "--height=40",
                "--target=browser:auto", "--run-id=place-gone"));
        assertEquals(10, first.path("x").asInt(), first.toString());
        assertEquals(10, first.path("y").asInt(), first.toString());
        String firstId = first.path("id").asText();
        assertFalse(firstId.isBlank(), first.toString());
        Capture removed = run("overlay-remove", "--id=" + firstId, "--target=browser:auto", "--run-id=place-gone");
        assertEquals(0, removed.code, removed.err + removed.out);

        JsonNode second = body(run(
                "overlay", "--text=Beta box", "--x=10", "--y=10", "--width=80", "--height=40",
                "--target=browser:auto", "--run-id=place-gone"));
        assertEquals(10, second.path("x").asInt(), second.toString());
        assertEquals(10, second.path("y").asInt(), second.toString());
        assertTrue(second.has("overlaps"), second.toString());
        assertEquals(0, second.path("overlaps").size(), second.toString());
        String page = eval(PAGE_TEXT, "place-gone");
        assertTrue(page.contains("Beta box"), page);
        assertFalse(page.contains("Alpha box"), page);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void clearedPageDoesNotOverlapTheNextOverlay() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        start("clear.html", "Clear", "place-clear");
        JsonNode first = body(run(
                "overlay", "--text=Old box", "--x=30", "--y=30", "--width=90", "--height=40",
                "--target=browser:auto", "--run-id=place-clear"));
        assertFalse(first.path("id").asText().isBlank(), first.toString());
        Capture cleaned = run("html-cleanup", "--target=browser:auto", "--run-id=place-clear");
        assertEquals(0, cleaned.code, cleaned.err + cleaned.out);

        JsonNode second = body(run(
                "overlay", "--text=New box", "--x=30", "--y=30", "--width=90", "--height=40",
                "--target=browser:auto", "--run-id=place-clear"));
        assertEquals(30, second.path("x").asInt(), second.toString());
        assertEquals(30, second.path("y").asInt(), second.toString());
        assertTrue(second.has("overlaps"), second.toString());
        assertEquals(0, second.path("overlaps").size(), second.toString());
        String page = eval(PAGE_TEXT, "place-clear");
        assertTrue(page.contains("New box"), page);
        assertFalse(page.contains("Old box"), page);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void labelUnderAnOverlayNamesThatOverlay() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("under.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Under</title>
                <style>html,body{margin:0}</style></head><body>
                <button id="go" data-pkb-handle="go" style="position:fixed;left:40px;top:140px">Go</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=place-under");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        JsonNode cover = body(run(
                "overlay", "--text=Cover", "--x=0", "--y=0", "--width=480", "--height=360",
                "--target=browser:auto", "--run-id=place-under"));
        String coverId = cover.path("id").asText();
        assertFalse(coverId.isBlank(), cover.toString());
        JsonNode label = body(run(
                "label", "--text=On go", "--handle=go", "--target=browser:auto", "--run-id=place-under"));
        String labelId = label.path("id").asText();
        assertFalse(labelId.isBlank(), label.toString());
        boolean named = false;
        for (JsonNode id : label.path("overlaps")) {
            if (coverId.equals(id.asText())) named = true;
        }
        assertTrue(label.has("overlaps") && named, label.toString());

        JsonNode boxes = JSON.readTree(eval(BOXES, "place-under"));
        JsonNode coverBox = box(boxes, coverId);
        JsonNode labelBox = box(boxes, labelId);
        assertTrue(coverBox.path("w").asDouble() > 0 && coverBox.path("h").asDouble() > 0, boxes.toString());
        assertTrue(labelBox.path("w").asDouble() > 0 && labelBox.path("h").asDouble() > 0, boxes.toString());
        assertFalse(apart(coverBox, labelBox), boxes.toString());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pinsAndChecklistStackAndCheckOnlyTheChosenRow() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        start("cards.html", "Cards", "place-cards");
        JsonNode first = body(run("pin", "--text=One", "--target=browser:auto", "--run-id=place-cards"));
        assertEquals("pin", first.path("op").asText(), first.toString());
        assertEquals(24, first.path("y").asInt(), first.toString());
        JsonNode second = body(run("pin", "--text=Two", "--target=browser:auto", "--run-id=place-cards"));
        assertTrue(second.path("y").asInt() > first.path("y").asInt(), second.toString());
        JsonNode list = body(run(
                "checklist", "--items=Alpha|Beta", "--target=browser:auto", "--run-id=place-cards"));
        assertEquals("checklist", list.path("op").asText(), list.toString());
        assertTrue(list.path("y").asInt() > second.path("y").asInt(), list.toString());
        String listId = list.path("id").asText();
        assertFalse(listId.isBlank(), list.toString());

        JsonNode boxes = JSON.readTree(eval(BOXES, "place-cards"));
        List<JsonNode> cards = new ArrayList<>();
        for (JsonNode box : boxes) {
            String kind = box.path("card").asText();
            if ("pin".equals(kind) || "checklist".equals(kind)) cards.add(box);
        }
        assertEquals(3, cards.size(), boxes.toString());
        for (JsonNode card : cards) {
            assertTrue(card.path("w").asDouble() >= 1 && card.path("h").asDouble() >= 1, boxes.toString());
        }
        for (int i = 0; i < cards.size(); i++) {
            for (int j = i + 1; j < cards.size(); j++) {
                assertTrue(apart(cards.get(i), cards.get(j)), boxes.toString());
            }
        }

        JsonNode ticked = body(run(
                "checklist", "--id=" + listId, "--check=2", "--target=browser:auto", "--run-id=place-cards"));
        assertTrue(ticked.path("applied").asBoolean(), ticked.toString());
        assertEquals(2, ticked.path("checked").asInt(), ticked.toString());
        assertEquals("Beta", ticked.path("text").asText(), ticked.toString());
        JsonNode rows = JSON.readTree(eval(CHECK_ROWS, "place-cards"));
        assertEquals(2, rows.size(), rows.toString());
        assertEquals(1, rows.get(0).path("n").asInt(), rows.toString());
        assertEquals("Alpha", rows.get(0).path("text").asText(), rows.toString());
        assertFalse(rows.get(0).path("checked").asBoolean(), rows.toString());
        assertEquals(2, rows.get(1).path("n").asInt(), rows.toString());
        assertEquals("Beta", rows.get(1).path("text").asText(), rows.toString());
        assertTrue(rows.get(1).path("checked").asBoolean(), rows.toString());
    }

    private static final String CHECK_ROWS = """
            (function(){
              var out = [];
              function walk(root, seen) {
                if (!root || !root.querySelectorAll || seen.has(root)) return;
                seen.add(root);
                var nodes = root.querySelectorAll('[data-pkb-check]');
                for (var i = 0; i < nodes.length; i++) {
                  var row = nodes[i];
                  out.push({
                    n: Number(row.getAttribute('data-pkb-check')),
                    text: (row.textContent || '').replace(/\\s+/g, ' ').trim(),
                    checked: row.getAttribute('data-pkb-checked') === 'true'
                  });
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) if (all[j].shadowRoot) walk(all[j].shadowRoot, seen);
              }
              walk(document, new Set());
              return JSON.stringify(out);
            })()
            """;
    private static final String PAGE_TEXT = """
            (function(){
              var text = document.body ? document.body.innerText : '';
              var layers = document.querySelectorAll('pkb-layer');
              for (var i = 0; i < layers.length; i++) {
                if (layers[i].shadowRoot) text += '\\n' + layers[i].shadowRoot.textContent;
              }
              return text;
            })()
            """;

    private void start(String file, String title, String runId) throws Exception {
        Path page = temp.resolve(file);
        Files.writeString(page, "<!DOCTYPE html><html><head><title>" + title + "</title></head><body>" + title + "</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=" + runId);
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
    }

    private JsonNode body(Capture capture) throws Exception {
        assertEquals(0, capture.code, capture.err + capture.out);
        return JSON.readTree(capture.out);
    }

    private JsonNode box(JsonNode boxes, String id) {
        for (JsonNode box : boxes) {
            if (id.equals(box.path("id").asText())) return box;
        }
        return JSON.missingNode();
    }

    private static boolean apart(JsonNode a, JsonNode b) {
        double ax = a.path("x").asDouble();
        double ay = a.path("y").asDouble();
        double aw = a.path("w").asDouble();
        double ah = a.path("h").asDouble();
        double bx = b.path("x").asDouble();
        double by = b.path("y").asDouble();
        double bw = b.path("w").asDouble();
        double bh = b.path("h").asDouble();
        return ax + aw <= bx || bx + bw <= ax || ay + ah <= by || by + bh <= ay;
    }

    private String eval(String expr, String runId) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText();
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
