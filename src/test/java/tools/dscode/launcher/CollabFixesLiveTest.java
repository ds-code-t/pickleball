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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two-actor collaboration fixes (docs/roadmaps/3.0.0-collab-fixes-roadmap.md) on a live start host.
 * Each test starts its own headless Chrome through the production {@code start} verb.
 */
class CollabFixesLiveTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    Path page(String name, String body) throws Exception {
        Path page = temp.resolve(name);
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Collab</title></head><body>" + body + "</body></html>");
        return page;
    }

    void start(Path page, String runId) {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=" + runId);
        assertEquals(0, started.code, started.err + started.out);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void c2_cardClickIsAppliedANoOpIsNotAndTabMovesFocus() throws Exception {
        start(page("c2.html", """
                <input id="first" aria-label="First"><input id="second" aria-label="Second">
                <button id="noop">Nothing</button>
                <input id="email" name="email" aria-label="Email">
                """), "c2");
        Capture asked = run("ask", "--say=Pick", "--choices=Alpha,Beta", "--run-id=c2");
        assertEquals(0, asked.code, asked.err + asked.out);
        Capture card = run("act", "--target=browser:auto", "--query=Alpha", "--kind=click", "--pick=1", "--run-id=c2");
        JsonNode cardBody = JSON.readTree(card.out);
        assertEquals("true", cardBody.path("applied").asText(), card.out);

        Capture noop = run("act", "--target=browser:auto", "--query=Nothing", "--kind=click", "--run-id=c2");
        JsonNode noopBody = JSON.readTree(noop.out);
        assertNotEquals("true", noopBody.path("applied").asText(), "a click with no effect is never applied: " + noop.out);

        Capture tab = run("act", "--target=browser:auto", "--query=First", "--kind=press", "--value=Tab", "--run-id=c2");
        assertEquals(0, tab.code, tab.err + tab.out);
        assertEquals("true", JSON.readTree(tab.out).path("applied").asText(), tab.out);
        assertEquals("second", eval("document.activeElement && document.activeElement.id", "c2"));

        Capture fill = run("form-fill", "--target=browser:auto", "--fields=email=abc@example.com", "--run-id=c2");
        assertEquals(0, fill.code, fill.err + fill.out);
        Thread.sleep(800);
        List<String> rows = interactionRows("email");
        assertFalse(rows.isEmpty(), "form-fill left no interaction row");
        for (String row : rows) assertFalse(row.contains("\"source\":\"human\""), "agent form-fill tagged human: " + row);
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void c3_snapShowsCollabStateAndSinceReturnsOnlyChanges() throws Exception {
        start(page("c3.html", """
                <label>Email <input id="email" name="email"></label>
                <label>Secret <input id="secret" type="password"></label>
                <label><input id="agree" type="checkbox"> Agree</label>
                <button id="go">Go</button>
                """), "c3");
        assertEquals(0, run("form-fill", "--target=browser:auto", "--fields=email=ann@example.com", "--run-id=c3").code);
        assertEquals(0, run("form-fill", "--target=browser:auto", "--fields=secret=hunter2", "--run-id=c3").code);
        assertEquals(0, run("panel-open", "--run-id=c3").code);
        assertEquals(0, run("chat-send", "--text=hello human", "--run-id=c3").code);
        Capture paint = run("highlight", "--handle=#go", "--preset=outline-red", "--run-id=c3");
        assertEquals(0, paint.code, paint.err + paint.out);
        Capture guided = run("guide", "--steps=[{\"t\":\"#agree\",\"say\":\"Tick agree\",\"do\":\"check\"}]", "--id=g1", "--run-id=c3");
        assertEquals(0, guided.code, guided.err + guided.out);
        assertEquals(0, run("ask", "--say=Pick one", "--choices=Alpha,Beta", "--run-id=c3").code);
        Thread.sleep(400);

        Capture snap = run("snap", "--target=browser:auto", "--run-id=c3");
        assertEquals(0, snap.code, snap.err + snap.out);
        JsonNode body = JSON.readTree(snap.out);
        String lines = body.path("items").toString();
        assertTrue(lines.contains("value=ann@example.com"), "email value missing: " + lines);
        assertTrue(lines.contains("value=redacted"), "password not redacted: " + lines);
        assertFalse(lines.contains("hunter2"), "password leaked: " + lines);
        assertTrue(lines.contains("checked=false"), "checkbox state missing: " + lines);
        assertTrue(lines.contains("highlight=outline-red"), "highlight missing: " + lines);
        assertTrue(lines.contains("guide=") && lines.contains("g1 step 1/1"), "guide step missing: " + lines);
        assertTrue(lines.contains("card=") && lines.contains("ask-q1") && lines.contains("open"), "card missing: " + lines);
        assertTrue(lines.contains("chat-tab"), "panel purpose missing: " + lines);
        assertTrue(lines.contains("msg="), "chat msg attributes missing: " + lines);
        assertFalse(body.path("cursors").path("chat").asText().isBlank(), "cursors.chat did not advance: " + snap.out);
        assertEquals("open", body.path("panel").asText(), snap.out);
        String cursor = body.path("snapCursor").asText();
        assertFalse(cursor.isBlank(), snap.out);

        Capture narrowed = run("snap", "--target=browser:auto", "--include=values", "--run-id=c3");
        String narrowLines = JSON.readTree(narrowed.out).path("items").toString();
        assertTrue(narrowLines.contains("value=ann@example.com"), narrowLines);
        assertFalse(narrowLines.contains("highlight="), "--include=values kept highlight: " + narrowLines);

        assertEquals(0, run("form-fill", "--target=browser:auto", "--fields=email=bob@example.com", "--run-id=c3").code);
        Capture since = run("snap", "--target=browser:auto", "--since=" + cursor, "--run-id=c3");
        assertEquals(0, since.code, since.err + since.out);
        JsonNode delta = JSON.readTree(since.out);
        assertTrue(delta.path("changed").toString().contains("bob@example.com"), "changed row missing: " + since.out);
        assertTrue(delta.path("added").isEmpty(), "nothing was added: " + since.out);
        assertFalse(delta.path("items").toString().contains("\"Go\""), "--since printed an unchanged row: " + since.out);
        Capture stale = run("snap", "--target=browser:auto", "--since=s.nope.1", "--run-id=c3");
        assertNotEquals(0, stale.code, stale.out);

        java.util.concurrent.CompletableFuture<Capture> waiting = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> run("wait", "--target=browser:auto", "--condition=new-control", "--timeout=8000", "--run-id=c3"));
        Thread.sleep(1200);
        assertEquals(0, run("ask", "--say=Ready?", "--choices=Ship it", "--id=q2", "--run-id=c3").code);
        Capture waited = waiting.get(20, TimeUnit.SECONDS);
        assertEquals(0, waited.code, waited.err + waited.out);
        assertTrue(JSON.readTree(waited.out).path("control").asText().contains("Ship it"), waited.out);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void c4_exactNameBeatsSubstringPurposeSelectsAndTabsSwitchPanes() throws Exception {
        start(page("c4.html", """
                <button id="save">Save</button><button id="draft">Save draft</button>
                <button id="go1">Go</button><button id="go2">Go</button>
                """), "c4");
        assertEquals(0, run("panel-open", "--tab=guide", "--run-id=c4").code);
        assertEquals(0, run("chat-send", "--text=new message while on Guide", "--run-id=c4").code);
        Thread.sleep(300);
        assertEquals("1 unread", eval(tab("chat") + ".getAttribute('aria-description')", "c4"));

        Capture chat = run("find", "--target=browser:auto", "--query=Chat", "--run-id=c4");
        assertEquals(0, chat.code, chat.err + chat.out);
        JsonNode hits = JSON.readTree(chat.out).path("items");
        assertEquals(1, hits.size(), "a substring (Pop out chat) must not join an exact name: " + chat.out);
        assertTrue(hits.get(0).toString().contains("chat-tab"), chat.out);

        Capture purpose = run("find", "--target=browser:auto", "--purpose=popout", "--run-id=c4");
        assertEquals(0, purpose.code, purpose.err + purpose.out);
        assertTrue(purpose.out.contains("Pop out chat"), purpose.out);
        Capture inQuery = run("find", "--target=browser:auto", "--query=purpose=guide-tab", "--run-id=c4");
        assertTrue(inQuery.out.contains("Guide"), inQuery.out);

        Capture save = run("act", "--target=browser:auto", "--query=Save", "--kind=click", "--run-id=c4");
        assertFalse(save.out.contains("ambiguous"), "exact Save should not be ambiguous with Save draft: " + save.out);

        Capture click = run("act", "--target=browser:auto", "--purpose=chat-tab", "--kind=click", "--run-id=c4");
        assertEquals(0, click.code, click.err + click.out);
        assertEquals("true", JSON.readTree(click.out).path("applied").asText(), click.out);
        assertEquals("true", eval(tab("chat") + ".getAttribute('aria-selected')", "c4"));
        assertEquals("false", eval(pane() + ".querySelector('.pkb-chat').hidden + ''", "c4"));

        assertEquals(0, run("panel-open", "--tab=guide", "--run-id=c4").code);
        assertEquals("true", eval(tab("steps") + ".getAttribute('aria-selected')", "c4"));

        Capture picked = run("act", "--target=browser:auto", "--query=Go", "--kind=click", "--pick=2", "--run-id=c4");
        JsonNode pickedBody = JSON.readTree(picked.out);
        assertEquals(2, pickedBody.path("candidates").size(), picked.out);
        assertEquals(2, pickedBody.path("picked").path("index").asInt(), picked.out);
        assertTrue(pickedBody.path("picked").path("why").asText().contains("--pick=2"), picked.out);
    }

    static String pane() {
        return "document.querySelector('pkb-layer').shadowRoot";
    }

    static String tab(String name) {
        return pane() + ".querySelector('[data-tab=" + name + "]')";
    }

    List<String> interactionRows(String id) throws Exception {
        Path events = temp.resolve(".pickleball").resolve("agent-events.jsonl");
        if (!Files.exists(events)) return List.of();
        return Files.readAllLines(events).stream()
                .filter(line -> line.contains("\"interaction\"") && line.contains("\"id\":\"" + id + "\""))
                .toList();
    }

    String eval(String expr, String runId) throws Exception {
        Capture value = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, value.code, value.err + value.out);
        return JSON.readTree(value.out).path("value").asText();
    }

    Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        long started = System.nanoTime();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
                (System.nanoTime() - started) / 1_000_000L);
    }

    record Capture(int code, String out, String err, long millis) {
    }
}
