package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2b live on a start-host Chrome session: pushed DOM change batches drive watch, wait --until-change,
 * wait --condition=new-control, snap --since deltas, and the answer-wait wake.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class DomPushLiveTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN = "--run-id=dom-push";
    @TempDir
    static Path temp;
    static ExecutorService pool;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("dom.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Dom push</title></head><body>
                <ul id="list"><li>one</li></ul>
                <div id="other">other</div>
                <div id="status" data-state="idle" title="t">Idle</div>
                <p id="counter">Clicked 0</p>
                <button id="b" onclick="window.__clickAt=Date.now();var c=document.getElementById('counter');c.textContent='Clicked '+(parseInt(c.textContent.split(' ')[1])+1);">Bump</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", RUN);
        assertEquals(0, started.code, started.err + started.out);
        pool = Executors.newCachedThreadPool();
    }

    @AfterAll
    static void stop() {
        if (pool != null) pool.shutdownNow();
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_watch_selector_until_change() throws Exception {
        Future<Capture> watch = pool.submit(() -> run("watch", "--target=browser:auto", "--selector=#list", "--kinds=childList",
                "--debounce=100", "--duration=8000", "--until=change", RUN));
        mutateUntilDone(watch, "(function(){var l=document.getElementById('list');for(var i=0;i<3;i++){var li=document.createElement('li');li.textContent='n'+i;l.appendChild(li);}"
                + "document.getElementById('other').textContent='o'+Date.now();return 'ok';})()");
        Capture done = watch.get(15, TimeUnit.SECONDS);
        assertEquals(0, done.code, done.err + done.out);
        JsonNode body = JSON.readTree(done.out);
        assertEquals("push", body.path("mode").asText(), done.out);
        assertEquals(1, items(body).size(), done.out);
        JsonNode batch = items(body).path(0);
        assertEquals("mutation", batch.path("subkind").asText(), done.out);
        assertEquals(3, batch.path("added").asInt(), done.out);
        assertTrue(batch.path("targets").toString().contains("ul#list"), done.out);
        assertFalse(batch.path("targets").toString().contains("other"), done.out);
    }

    @Test
    void pickleballOverlayChangesAreExcludedUnlessAsked() throws Exception {
        String own = "(function(){var d=document.createElement('div');d.setAttribute('data-pkb-ui','');d.textContent='mine';document.body.appendChild(d);"
                + "document.body.setAttribute('data-pkb-mark',''+Date.now());var x=document.createElement('pkb-layer');document.body.appendChild(x);return 'ok';})()";
        Future<Capture> watch = pool.submit(() -> run("watch", "--target=browser:auto", "--kinds=childList,attributes",
                "--duration=2500", "--until=change", RUN));
        mutateUntilDone(watch, own);
        Capture quiet = watch.get(15, TimeUnit.SECONDS);
        assertEquals(1, quiet.code, quiet.out + quiet.err);
        assertTrue(quiet.out.contains("timeout") || quiet.err.contains("timeout"), quiet.out + quiet.err);
        assertFalse(quiet.out.contains("\"subkind\":\"mutation\""), quiet.out);

        Future<Capture> asked = pool.submit(() -> run("watch", "--target=browser:auto", "--kinds=childList,attributes", "--own",
                "--duration=8000", "--until=change", RUN));
        mutateUntilDone(asked, own);
        Capture seen = asked.get(15, TimeUnit.SECONDS);
        assertEquals(0, seen.code, seen.err + seen.out);
        assertEquals(1, items(JSON.readTree(seen.out)).size(), seen.out);
    }

    @Test
    void attributeAndTextFiltersApply() throws Exception {
        Future<Capture> attrs = pool.submit(() -> run("watch", "--target=browser:auto", "--selector=#status", "--kinds=attributes",
                "--attributes=data-state", "--duration=8000", "--until=change", RUN));
        mutateUntilDone(attrs, "(function(){var s=document.getElementById('status');s.title='x'+Date.now();"
                + "setTimeout(function(){s.setAttribute('data-state','busy'+Date.now());},150);return 'ok';})()");
        Capture a = attrs.get(15, TimeUnit.SECONDS);
        assertEquals(0, a.code, a.err + a.out);
        JsonNode batch = items(JSON.readTree(a.out)).path(0);
        assertEquals("[\"data-state\"]", batch.path("attributeNames").toString(), a.out);

        Future<Capture> text = pool.submit(() -> run("watch", "--target=browser:auto", "--selector=#status", "--kinds=childList,text",
                "--contains=Ready", "--duration=8000", "--until=change", RUN));
        mutateUntilDone(text, "(function(){var s=document.getElementById('status');s.textContent='Loading';"
                + "setTimeout(function(){s.textContent='Ready now';},150);return 'ok';})()");
        Capture t = text.get(15, TimeUnit.SECONDS);
        assertEquals(0, t.code, t.err + t.out);
        JsonNode textBatch = items(JSON.readTree(t.out)).path(0);
        assertTrue(textBatch.path("sample").asText().contains("Ready"), t.out);
        assertFalse(textBatch.path("sample").asText().contains("Loading"), t.out);
    }

    @Test
    void covers_command_wait_until_change() throws Exception {
        Future<Capture> wait = pool.submit(() -> run("wait", "--target=browser:auto", "--until-change", "--selector=#list",
                "--timeout=8000", RUN));
        mutateUntilDone(wait, "(function(){var li=document.createElement('li');li.textContent='w';document.getElementById('list').appendChild(li);return 'ok';})()");
        Capture done = wait.get(15, TimeUnit.SECONDS);
        assertEquals(0, done.code, done.err + done.out);
        JsonNode body = JSON.readTree(done.out);
        assertEquals("push", body.path("wake").asText(), done.out);
        assertTrue(body.path("change").path("targets").toString().contains("ul#list"), done.out);
    }

    @Test
    void newControlWaitWakesOnPush() throws Exception {
        Future<Capture> wait = pool.submit(() -> run("wait", "--target=browser:auto", "--condition=new-control", "--timeout=8000", RUN));
        Thread.sleep(1500);
        eval("(function(){var b=document.createElement('button');b.textContent='Fresh '+Date.now();document.body.appendChild(b);return 'ok';})()");
        Capture done = wait.get(15, TimeUnit.SECONDS);
        assertEquals(0, done.code, done.err + done.out);
        JsonNode body = JSON.readTree(done.out);
        assertEquals("push", body.path("wake").asText(), done.out);
        assertTrue(body.path("control").asText().contains("Fresh"), done.out);
    }

    @Test
    void snapSinceUsesThePushDelta() throws Exception {
        JsonNode first = JSON.readTree(ok("snap", "--target=browser:auto", RUN).out);
        String cursor = first.path("snapCursor").asText();
        assertFalse(cursor.isBlank(), first.toString());
        eval("(function(){var li=document.createElement('li');li.textContent='snap';document.getElementById('list').appendChild(li);return 'ok';})()");
        JsonNode delta = null;
        String next = cursor;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JsonNode since = JSON.readTree(ok("snap", "--target=browser:auto", "--since=" + cursor, RUN).out);
            delta = since.path("delta");
            next = since.path("snapCursor").asText();
            if (delta.path("batches").asInt() > 0) break;
            Thread.sleep(100);
        }
        assertEquals("push", delta.path("mode").asText(), String.valueOf(delta));
        assertTrue(delta.path("batches").asInt() >= 1, String.valueOf(delta));
        assertTrue(delta.path("targets").toString().contains("ul#list"), String.valueOf(delta));
        JsonNode quiet = JSON.readTree(ok("snap", "--target=browser:auto", "--since=" + next, RUN).out).path("delta");
        assertEquals("push", quiet.path("mode").asText(), quiet.toString());
        assertTrue(quiet.path("unchanged").asBoolean(), quiet.toString());
    }

    @Test
    void answerWaitWakesOnCardClickWithoutPolling() throws Exception {
        Capture asked = ok("ask-and-wait", "--say=Push?", "--choices=Yes,No", "--async", RUN);
        String id = JSON.readTree(asked.out).path("id").asText();
        Future<Capture> waiting = pool.submit(() -> run("answer-wait", "--id=" + id, "--timeout=10s", RUN));
        Thread.sleep(1500);
        assertFalse(waiting.isDone(), "answer-wait returned before the click");
        String clicked = "no";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!"yes".equals(clicked) && System.nanoTime() < deadline) {
            clicked = eval(clickCard(id));
            if (!"yes".equals(clicked)) Thread.sleep(50);
        }
        assertEquals("yes", clicked);
        Capture choice = waiting.get(15, TimeUnit.SECONDS);
        assertEquals(0, choice.code, choice.err + choice.out);
        JsonNode answer = JSON.readTree(choice.out);
        assertEquals("Yes", answer.path("value").asText(), choice.out);
        assertEquals("push", answer.path("wake").asText(), choice.out);
        // The wait returned within the push latency, not after a poll backstop (250 ms).
        assertTrue(answer.path("wakeMs").asLong(9999) < tools.dscode.common.agentfast.PushWake.BACKSTOP_MS, choice.out);
    }

    /** Human click to agent event, before (polling watch --until=text=) and after (pushed watch). Numbers go to stdout. */
    @Test
    void clickToAgentLatencyPushVersusPoll() throws Exception {
        List<Long> push = new ArrayList<>();
        List<Long> poll = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            int now = Integer.parseInt(eval("document.getElementById('counter').textContent.split(' ')[1]"));
            String want = "Clicked " + (now + 1);
            push.add(clickLatency(() -> run("watch", "--target=browser:auto", "--selector=#counter", "--kinds=childList,text",
                    "--contains=" + want, "--debounce=0", "--duration=8000", "--until=change", RUN)));
            String want2 = "Clicked " + (now + 2);
            poll.add(clickLatency(() -> run("watch", "--target=browser:auto", "--until=text=" + want2, "--duration=8000", RUN)));
        }
        long pushMedian = push.stream().sorted().toList().get(1);
        long pollMedian = poll.stream().sorted().toList().get(1);
        System.out.println("E2b click-to-agent latency ms: push=" + push + " median=" + pushMedian + " poll=" + poll + " median=" + pollMedian);
        assertTrue(pushMedian < 2000, "push latency " + push);
        assertNotEquals(0, poll.size());
    }

    /** Page click time (Date.now in the click handler) to the agent's receipt time, both wall-clock ms on this machine. */
    private long clickLatency(java.util.concurrent.Callable<Capture> waiter) throws Exception {
        Future<Capture> future = pool.submit(waiter);
        Thread.sleep(1500);
        assertFalse(future.isDone(), "the watch ended before the click");
        eval("(function(){setTimeout(function(){document.getElementById('b').click();},0);return 'ok';})()");
        Capture done = future.get(15, TimeUnit.SECONDS);
        assertEquals(0, done.code, done.err + done.out);
        long clickedAt = Long.parseLong(eval("String(window.__clickAt)"));
        JsonNode body = JSON.readTree(done.out);
        long agentAt = body.has("metAt") ? body.path("metAt").asLong() : items(body).path(0).path("receivedAt").asLong();
        assertTrue(agentAt > 0, done.out);
        return agentAt - clickedAt;
    }

    /** The reply envelope may rename {@code events} to {@code items}. */
    static JsonNode items(JsonNode body) {
        return body.has("events") ? body.path("events") : body.path("items");
    }

    static void mutateUntilDone(Future<Capture> waiter, String script) throws Exception {
        Thread.sleep(800);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!waiter.isDone() && System.nanoTime() < deadline) {
            eval(script);
            Thread.sleep(400);
        }
    }

    static String clickCard(String id) {
        String quoted = id.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){var layers=document.querySelectorAll('pkb-layer');for(var i=0;i<layers.length;i++){"
                + "var root=layers[i].shadowRoot; if(!root) continue;var card=root.querySelector('[data-pkb-id$=\"ask-" + quoted + "\"]');"
                + "if(!card) continue;var buttons=card.querySelectorAll('button');for(var j=0;j<buttons.length;j++){"
                + "if(buttons[j].textContent==='Yes'){buttons[j].click(); return 'yes';}}}return 'no';})()";
    }

    static String eval(String expr) throws Exception {
        Capture result = ok("eval", "--target=browser:auto", "--world=main", RUN, "--expr=" + expr);
        return JSON.readTree(result.out).path("value").asText();
    }

    static Capture ok(String... args) {
        Capture capture = run(args);
        assertEquals(0, capture.code, capture.err + capture.out);
        return capture;
    }

    static Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
