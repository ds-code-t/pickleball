package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.AgentDriverHooks;
import tools.dscode.common.agentfast.LiveBrowserHost;
import tools.dscode.common.agentfast.LiveChromeDriver;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(missing.out.contains("\"applied\":false"), missing.out);
        assertFalse(missing.out.contains("\"ok\":true"), missing.out);
        Capture unknown = run("find", "--target=browser:missing-session", "--query=Save", "--run-id=missing");
        assertEquals(1, unknown.code, unknown.out + unknown.err);
        assertTrue(unknown.out.contains("unknown target"), unknown.out);
        assertTrue(unknown.out.contains("\"applied\":false"), unknown.out);
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

        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=live",
                "--expr=setTimeout(function(){var p=document.createElement('p');p.textContent='Arrived';document.body.appendChild(p);},900); 'armed'");
        assertEquals("armed", JSON.readTree(armed.out).path("value").asText(), armed.out);
        long beforeWait = System.nanoTime();
        Capture waited = run("wait", "--target=browser:auto", "--condition=text=Arrived", "--timeout=4000", "--run-id=live");
        long waitedMs = (System.nanoTime() - beforeWait) / 1_000_000L;
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode wait = JSON.readTree(waited.out);
        assertTrue(wait.path("applied").asBoolean(), waited.out);
        assertEquals("Saved", wait.path("title").asText(), waited.out);
        assertEquals("poll", wait.path("mode").asText(), waited.out);
        assertTrue(waitedMs >= 300, "wait returned before Arrived: " + waitedMs + " " + waited.out);

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

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void namesSelectExplainEvalAndTargets() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("names.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>Ready</title>
                <style>#ghost{visibility:hidden}</style></head>
                <body>
                <button id="save">Save</button>
                <button id="go" aria-label="Launch">X</button>
                <label for="city">City</label><input id="city" placeholder="Town">
                <label for="color">Color</label>
                <select id="color"><option>Red</option><option>Blue</option><option>Red</option></select>
                <button id="off" disabled>Off</button>
                <button id="muted" aria-disabled="true">Muted</button>
                <fieldset disabled><input id="boxed" aria-label="Boxed"></fieldset>
                <button id="ghost">Ghost</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);

        Capture snap = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=names");
        assertEquals(0, snap.code, snap.err + snap.out);
        assertTrue(snap.out.contains("Color Red"), snap.out);
        assertFalse(snap.out.contains("RedBlueRed"), snap.out);

        JsonNode launch = retryFind("browser:auto", "Launch");
        assertEquals("button", launch.path("hits").get(0).path("role").asText(), launch.toString());
        String launchName = launch.path("hits").get(0).path("name").asText();
        assertTrue("Launch".equals(launchName) || "X".equals(launchName), launch.toString());
        JsonNode city = retryFind("browser:auto", "City");
        JsonNode town = retryFind("browser:auto", "Town");
        JsonNode byId = retryFind("browser:auto", "city");
        assertFalse("label".equals(city.path("hits").get(0).path("role").asText()), city.toString());
        long handle = city.path("hits").get(0).path("handle").asLong();
        assertEquals(handle, town.path("hits").get(0).path("handle").asLong(), town.toString());
        assertEquals(handle, byId.path("hits").get(0).path("handle").asLong(), byId.toString());

        JsonNode save = explain("Save");
        assertTrue(save.path("report").path("visible").asBoolean(), save.toString());
        assertTrue(save.path("report").path("enabled").asBoolean(), save.toString());
        assertTrue(save.path("report").path("actionable").asBoolean(), save.toString());
        assertEquals("explain", save.path("chain").asText(), save.toString());
        JsonNode off = explain("Off");
        assertTrue(off.path("report").path("visible").asBoolean(), off.toString());
        assertFalse(off.path("report").path("enabled").asBoolean(), off.toString());
        assertFalse(off.path("report").path("actionable").asBoolean(), off.toString());
        JsonNode muted = explain("Muted");
        assertFalse(muted.path("report").path("enabled").asBoolean(), muted.toString());
        JsonNode boxed = explain("Boxed");
        assertFalse(boxed.path("report").path("enabled").asBoolean(), boxed.toString());
        JsonNode ghost = explain("Ghost");
        assertFalse(ghost.path("report").path("visible").asBoolean(), ghost.toString());

        Capture object = run(
                "eval", "--target=browser:auto", "--world=pkb", "--run-id=names",
                "--expr=({n:1,ok:true})");
        assertEquals(0, object.code, object.err + object.out);
        JsonNode evaluated = JSON.readTree(object.out);
        assertEquals("pkb", evaluated.path("world").asText(), object.out);
        assertEquals("inject", evaluated.path("chain").asText(), object.out);
        JsonNode payload = JSON.readTree(evaluated.path("value").asText());
        assertEquals(1, payload.path("n").asInt(), object.out);
        assertTrue(payload.path("ok").asBoolean(), object.out);

        Capture watcher = run(
                "eval", "--target=browser:auto", "--world=pkb", "--run-id=names",
                "--expr=typeof globalThis.__pkbUi");
        assertEquals("object", JSON.readTree(watcher.out).path("value").asText(), watcher.out);
        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=event-primary",
                "--expr=setTimeout(function(){var s=document.createElement('span');s.id='arrived';s.textContent='Arrived';document.body.appendChild(s);},800); 'armed'");
        assertEquals("armed", JSON.readTree(armed.out).path("value").asText(), armed.out);
        long beforeWait = System.nanoTime();
        Capture waited = run(
                "wait", "--target=browser:auto", "--mode=event", "--condition=mutation",
                "--timeout=4000", "--run-id=event-primary");
        long waitedMs = (System.nanoTime() - beforeWait) / 1_000_000L;
        assertEquals(0, waited.code, waited.err + waited.out);
        assertTrue(JSON.readTree(waited.out).path("applied").asBoolean(), waited.out);
        assertTrue(waitedMs >= 400, "event wait returned before the mutation: " + waitedMs + " " + waited.out);
        Path log = temp.resolve(".pickleball/logs/fallback-chain.jsonl");
        if (Files.isRegularFile(log)) {
            assertFalse(Files.readString(log).contains("\"run\":\"event-primary\""), Files.readString(log));
        }

        JsonNode color = retryFind("browser:auto", "color");
        String colorRef = "";
        for (JsonNode hit : color.path("hits")) {
            String role = hit.path("role").asText();
            if ("select".equals(role) || "combobox".equals(role)) colorRef = hit.path("ref").asText();
        }
        assertFalse(colorRef.isBlank(), color.toString());
        Capture selected = run(
                "act", "--target=" + colorRef, "--kind=select", "--value=Blue", "--run-id=names");
        assertEquals(0, selected.code, selected.err + selected.out);
        assertTrue(JSON.readTree(selected.out).path("applied").asBoolean(), selected.out);
        assertEquals("Blue", eval("document.getElementById('color').value").path("value").asText(), selected.out);
        Capture after = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=names");
        assertTrue(after.out.contains("Color Blue"), after.out);
        assertFalse(after.out.contains("RedBlueRed"), after.out);

        Capture element = run("find-element", "--target=browser:auto", "--query=Save", "--run-id=names");
        assertEquals(0, element.code, element.err + element.out);
        JsonNode found = JSON.readTree(element.out);
        assertTrue(found.path("applied").asBoolean(), element.out);
        assertEquals("Save", found.path("hits").get(0).path("name").asText(), element.out);
        Capture inspected = run("inspect-page", "--target=browser:auto", "--run-id=names");
        assertEquals(0, inspected.code, inspected.err + inspected.out);
        assertEquals("snap", JSON.readTree(inspected.out).path("op").asText(), inspected.out);
        assertTrue(inspected.out.contains("Save"), inspected.out);

        Path png = temp.resolve("target-shot.png");
        Capture shot = run("screenshot", "--target=browser:auto", "--out=" + png, "--run-id=names");
        assertEquals(0, shot.code, shot.err + shot.out);
        assertEquals("capture", JSON.readTree(shot.out).path("op").asText(), shot.out);
        byte[] bytes = Files.readAllBytes(png);
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 0x50, bytes[1]);

        Capture observed = run(
                "observe", "--target=browser:auto", "--wait-ms=200", "--type=console", "--run-id=names");
        assertEquals(0, observed.code, observed.err + observed.out);
        JsonNode watch = JSON.readTree(observed.out);
        assertEquals("watch", watch.path("op").asText(), observed.out);
        assertTrue(watch.path("events").isArray(), observed.out);

        String countExpr = "(function(){var n=0;function w(r){if(!r||!r.querySelectorAll)return;n+=r.querySelectorAll('[data-pkb-id]').length;var all=r.querySelectorAll('*');for(var i=0;i<all.length;i++){if(all[i].shadowRoot)w(all[i].shadowRoot);}}w(document);return String(n);})()";
        String before = eval(countExpr).path("value").asText();
        Capture highlight = run(
                "highlight", "--handle=save", "--target=desktop:999999", "--preset=yellow", "--run-id=names");
        assertEquals(1, highlight.code, highlight.err + highlight.out);
        assertTrue(highlight.out.contains("unknown target"), highlight.out);
        assertTrue(highlight.out.contains("\"applied\":false"), highlight.out);
        assertFalse(highlight.out.contains("\"ok\":true"), highlight.out);
        assertEquals(before, eval(countExpr).path("value").asText(), highlight.out);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void workerBrowserAnswersAutoAndRunId() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        assertNull(LiveBrowserHost.chrome());
        Path page = temp.resolve("worker.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head>
                <body><button id="save">Save</button></body></html>
                """);
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        BrowserSession session = BrowserSession.launch(
                "worker-page", page.toUri().toString(), false, true, profile, List.of());
        try {
            LiveChromeDriver driver = new LiveChromeDriver(session);
            driver.get(page.toUri().toString());
            AgentDriverHooks.template("worker");
            AgentDriverHooks.host(false);
            AgentDriverHooks.ready(driver);
            AgentDriverHooks.template(null);
            Map<String, String> args = new LinkedHashMap<>();
            args.put("target", "browser:auto");
            args.put("query", "Save");
            AgentControl.Result found = AgentControl.execute(temp, "worker-run", "find", args, List.of(), null);
            assertEquals(0, found.exit(), String.valueOf(found.body()));
            assertEquals(Boolean.TRUE, found.body().get("applied"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) found.body().get("hits");
            assertEquals("Save", hits.get(0).get("name"));
            args.put("target", "browser:worker-run");
            AgentControl.Result again = AgentControl.execute(temp, "worker-run", "find", args, List.of(), null);
            assertEquals(0, again.exit(), String.valueOf(again.body()));
            assertEquals(Boolean.TRUE, again.body().get("applied"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> second = (List<Map<String, Object>>) again.body().get("hits");
            assertEquals("Save", second.get(0).get("name"));
        } finally {
            AgentDriverHooks.host(false);
            AgentDriverHooks.template(null);
            try {
                AgentDriverHooks.quit();
            } catch (RuntimeException ignored) {
                // The session close still stops Chrome.
            }
            session.close();
        }
    }

    private JsonNode explain(String query) throws Exception {
        Capture capture = run("explain", "--target=browser:auto", "--query=" + query, "--run-id=names");
        assertEquals(0, capture.code, capture.err + capture.out);
        return JSON.readTree(capture.out);
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
