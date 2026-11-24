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

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void c5_chatTurnSendsFirstChatReadIsReadOnlyAndCardsLeaveATrail() throws Exception {
        start(page("c5.html", "<p>Chat</p>"), "c5");
        assertEquals(0, run("panel-open", "--tab=guide", "--run-id=c5").code);
        assertEquals(0, run("chat-send", "--source=human", "--text=earlier hi", "--run-id=c5").code);

        Capture quiet = run("chat-turn", "--text=Your turn", "--timeout=2s", "--run-id=c5");
        JsonNode quietBody = JSON.readTree(quiet.out);
        assertEquals("timeout", quietBody.path("error").asText(), quiet.out);
        assertFalse(quietBody.path("sentId").asText().isBlank(), quiet.out);
        assertEquals("delivered", quietBody.path("status").asText(), quiet.out);
        assertTrue(quietBody.path("earlier").toString().contains("earlier hi"), "earlier unread not listed: " + quiet.out);
        assertTrue(quietBody.path("earlier").get(0).path("earlier").asBoolean(), quiet.out);

        java.util.concurrent.CompletableFuture<Capture> turn = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> run("chat-turn", "--text=Ready?", "--timeout=10000ms", "--run-id=c5"));
        Thread.sleep(1500);
        assertEquals("1", eval(pane() + ".querySelectorAll('.pkb-msg[data-from=agent]').length - 1 + ''", "c5"),
                "the agent line is on the page before the human answers");
        assertEquals(0, run("chat-send", "--source=human", "--text=reply now", "--run-id=c5").code);
        Capture replied = turn.get(20, TimeUnit.SECONDS);
        assertEquals(0, replied.code, replied.err + replied.out);
        assertTrue(replied.out.contains("reply now"), replied.out);
        assertFalse(JSON.readTree(replied.out).path("items").toString().contains("earlier hi"), replied.out);
        assertEquals("1", eval("Array.from(" + pane() + ".querySelectorAll('.pkb-msg')).filter(function(n){return n.textContent.indexOf('reply now')>=0}).length + ''", "c5"));

        assertEquals("2 unread", eval(tab("chat") + ".getAttribute('aria-description')", "c5"));
        assertEquals(0, run("panel-open", "--tab=chat", "--run-id=c5").code);
        assertEquals(0, run("panel-open", "--tab=guide", "--run-id=c5").code);
        assertEquals("null", eval(tab("chat") + ".getAttribute('aria-description') + ''", "c5"), "seen messages stay seen");

        assertEquals(0, run("chat-send", "--source=human", "--text=unacked", "--run-id=c5").code);
        JsonNode read = JSON.readTree(run("chat-read", "--last=1", "--run-id=c5").out);
        assertEquals(1, read.path("items").size(), read.toString());
        assertFalse(read.path("items").get(0).path("read").asBoolean(), read.toString());
        assertEquals(0, read.path("markedRead").asInt(), read.toString());
        JsonNode again = JSON.readTree(run("chat-read", "--last=1", "--run-id=c5").out);
        assertFalse(again.path("items").get(0).path("read").asBoolean(), "chat-read acked without --mark-read: " + again);
        JsonNode marked = JSON.readTree(run("chat-read", "--last=1", "--mark-read=true", "--run-id=c5").out);
        assertTrue(marked.path("markedRead").asInt() > 0, marked.toString());
        JsonNode after = JSON.readTree(run("chat-read", "--last=1", "--run-id=c5").out);
        assertTrue(after.path("items").get(0).path("read").asBoolean(), after.toString());

        assertEquals(0, run("ask", "--say=Pick", "--choices=Alpha,Beta", "--id=q9", "--run-id=c5").code);
        assertEquals("true", JSON.readTree(run("act", "--target=browser:auto", "--query=Alpha", "--kind=click", "--run-id=c5").out).path("applied").asText());
        assertEquals(0, run("ask", "--say=Hold", "--choices=Ok", "--id=m1", "--min-show=1500", "--run-id=c5").code);
        Capture timedOut = run("answer-wait", "--id=m1", "--timeout=100", "--run-id=c5");
        assertEquals(1, timedOut.code, timedOut.out);
        assertEquals("1", eval("document.querySelector('pkb-layer').shadowRoot.querySelectorAll('[data-pkb-card-id=ask-m1]').length + ''", "c5"),
                "--min-show keeps the card up");
        Thread.sleep(2200);
        JsonNode trail = JSON.readTree(run("chat-read", "--last=1", "--run-id=c5").out).path("recent");
        assertTrue(trail.toString().contains("\"id\":\"ask-q9\"") && trail.toString().contains("answered"), trail.toString());
        assertTrue(trail.toString().contains("timed-out"), trail.toString());
        JsonNode snap = JSON.readTree(run("snap", "--target=browser:auto", "--run-id=c5").out);
        assertTrue(snap.path("recent").toString().contains("ask-m1"), snap.toString());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void covers_command_upload() throws Exception {
        start(page("c6.html", """
                <button id="up" type="button">Upload File</button>
                <input id="doc" type="file" multiple style="display:none">
                """), "c6");
        Path a = Files.writeString(temp.resolve("a.txt"), "alpha");
        Path b = Files.writeString(temp.resolve("b.txt"), "beta");

        Capture attached = run("act", "--target=browser:auto", "--query=Upload File", "--kind=attach", "--value=" + a, "--run-id=c6");
        assertEquals(0, attached.code, attached.err + attached.out);
        JsonNode body = JSON.readTree(attached.out);
        assertEquals("true", body.path("applied").asText(), attached.out);
        assertEquals("a.txt", body.path("files").get(0).asText(), attached.out);
        assertEquals("a.txt", eval("document.getElementById('doc').files[0].name", "c6"));

        Capture both = run("upload", "--target=browser:auto", "--query=Upload File", "--file=" + a, "--file=" + b, "--run-id=c6");
        assertEquals(0, both.code, both.err + both.out);
        assertEquals("2", eval("document.getElementById('doc').files.length + ''", "c6"));

        Capture missing = run("upload", "--target=browser:auto", "--query=Upload File", "--file=" + temp.resolve("nope.txt"), "--run-id=c6");
        assertNotEquals(0, missing.code, missing.out);
        assertTrue(missing.out.contains("file not found"), missing.out);

        // A person's click on the Upload File control offers the hand-off; its click posts upload-request.
        eval("document.getElementById('up').click(), 'ok'", "c6");
        Thread.sleep(300);
        Capture offer = run("find", "--target=browser:auto", "--purpose=request-upload", "--run-id=c6");
        assertEquals(0, offer.code, offer.err + offer.out);
        assertTrue(offer.out.contains("Ask agent to attach a file"), offer.out);
        assertEquals(0, run("act", "--target=browser:auto", "--purpose=request-upload", "--kind=click", "--run-id=c6").code);
        Capture polled = run("ui-poll", "--after=0", "--run-id=c6");
        assertTrue(polled.out.contains("upload-request") && polled.out.contains("#doc"), polled.out);
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void c7_guideFillsTheGuidePaneAdvancesOnAHumanAndSurvivesReload() throws Exception {
        start(page("c7.html", """
                <label>Name <input id="name"></label><button id="next">Next</button>
                """), "c7");
        assertEquals(0, run("panel-open", "--tab=chat", "--run-id=c7").code);
        Capture guided = run("guide", "--steps=[{\"t\":\"#name\",\"say\":\"Type your name\",\"do\":\"input\"},{\"t\":\"#next\",\"say\":\"Click Next\",\"do\":\"click\"}]", "--id=g1", "--run-id=c7");
        assertEquals(0, guided.code, guided.err + guided.out);
        assertEquals("true", eval(tab("steps") + ".getAttribute('aria-selected')", "c7"), "guide selects the Guide tab");
        String steps = eval(pane() + ".querySelector('[data-pkb-purpose~=guide-pane]').textContent", "c7");
        assertTrue(steps.contains("Type your name") && !steps.contains("No guide yet"), steps);
        assertEquals("g1 step 1/2 current", eval("document.getElementById('name').getAttribute('data-pkb-guide-step')", "c7"));
        assertEquals(0, run("panel-open", "--tab=chat", "--run-id=c7").code);
        assertEquals("2", eval(tab("steps") + ".querySelector('[data-pkb-badge]').getAttribute('data-pkb-badge')", "c7"));

        java.util.concurrent.CompletableFuture<Capture> polled = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> run("ui-poll", "--wait=10", "--run-id=c7"));
        Thread.sleep(800);
        // The person types and presses Enter (main-world events, no agent mark).
        eval("(function(){var n=document.getElementById('name');n.value='Ann';"
                + "n.dispatchEvent(new Event('input',{bubbles:true}));"
                + "n.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));return 'ok';})()", "c7");
        Capture event = polled.get(20, TimeUnit.SECONDS);
        assertEquals(0, event.code, event.err + event.out);
        assertTrue(event.out.contains("\"by\":\"human\"") && event.out.contains("\"s\":\"1\""), event.out);
        assertEquals("g1 step 2/2 current", eval("document.getElementById('next').getAttribute('data-pkb-guide-step')", "c7"));

        eval("location.reload(), 'ok'", "c7");
        Thread.sleep(2500);
        assertEquals("g1 step 2/2 current", eval("(document.getElementById('next').getAttribute('data-pkb-guide-step')||'') + ''", "c7"),
                "the guide is re-injected after navigation");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void covers_command_navigate() throws Exception {
        Path second = page("c8b.html", "<h1>Second</h1><button id=\"b\">Beta</button>");
        Path configs = Files.createDirectories(temp.resolve("src/test/resources/configs"));
        Files.writeString(configs.resolve("urls.properties"), "URL.second=" + second.toUri() + "\n");
        start(page("c8.html", "<button id=\"a\">Alpha</button>"), "c8");
        assertEquals(0, run("panel-open", "--tab=chat", "--run-id=c8").code);
        assertEquals(0, run("chat-send", "--text=before navigate", "--run-id=c8").code);

        Capture fixture = run("navigate", "--url=fixture:form", "--run-id=c8");
        assertEquals(0, fixture.code, fixture.err + fixture.out);
        JsonNode body = JSON.readTree(fixture.out);
        assertEquals("true", body.path("applied").asText(), fixture.out);
        assertEquals("Form", body.path("title").asText(), fixture.out);

        Capture keyed = run("navigate", "--url=URL.second", "--run-id=c8");
        assertEquals(0, keyed.code, keyed.err + keyed.out);
        assertTrue(JSON.readTree(keyed.out).path("url").asText().endsWith("c8b.html"), keyed.out);
        Thread.sleep(800);
        Capture snap = run("snap", "--target=browser:auto", "--run-id=c8");
        assertTrue(snap.out.contains("Beta"), snap.out);
        assertTrue(snap.out.contains("chat-tab"), "the panel stays after navigate: " + snap.out);

        Capture bad = run("navigate", "--url=URL.missing", "--run-id=c8");
        assertNotEquals(0, bad.code, bad.out);
        assertTrue(bad.out.contains("did not resolve"), bad.out);
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
