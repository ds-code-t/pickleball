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

/** Phase 5: find --scroll-search mounts a virtualized row that was not in the first tree. */
class VirtualizedScrollTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void scrollSearchMountsTheRowAndSnapReportsVirtualized() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path plain = temp.resolve("plain.html");
        Files.writeString(plain, "<!DOCTYPE html><html><body><button id=\"save\">Save</button></body></html>");
        Capture started = run("start", "--url=" + plain.toUri(), "--browser=chrome-headless", "--run-id=scroll");
        assertEquals(0, started.code, started.err + started.out);
        Capture flat = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=scroll");
        assertEquals(0, flat.code, flat.err + flat.out);
        assertFalse(JSON.readTree(flat.out).path("virtualized").asBoolean(true), flat.out);
        ConsumerReadyCommands.stopQuiet(temp);

        Path page = temp.resolve("list.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <div id="list" style="height:100px;overflow:auto">
                  <div id="pad" style="height:2000px;position:relative"></div>
                </div>
                <script>
                const list = document.getElementById('list');
                const pad = document.getElementById('pad');
                function render() {
                  const start = Math.floor(list.scrollTop / 20);
                  let html = '';
                  for (let i = start; i < start + 6; i++) {
                    html += '<div class="row" style="position:absolute;top:' + (i * 20) + 'px;height:20px">Row ' + i + '</div>';
                  }
                  pad.innerHTML = html;
                }
                list.addEventListener('scroll', render);
                render();
                </script>
                </body></html>
                """);
        Capture again = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=scroll");
        assertEquals(0, again.code, again.err + again.out);
        assertTrue(again.out.contains("navigated=true"), again.out);

        Capture missing = run("find", "--target=browser:auto", "--query=Row 40", "--run-id=scroll");
        assertEquals(1, missing.code, missing.err + missing.out);
        assertTrue(missing.out.contains("not found"), missing.out);

        Capture before = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll",
                "--expr=document.body.innerText.indexOf('Row 40') >= 0");
        assertEquals(0, before.code, before.err + before.out);
        assertEquals("false", JSON.readTree(before.out).path("value").asText(), before.out);

        Capture found = run(
                "find", "--target=browser:auto", "--query=Row 40", "--scroll-search=true", "--run-id=scroll");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertTrue(hit.path("scrollSearch").asBoolean(false), found.out);
        assertTrue(hit.path("applied").asBoolean(), found.out);
        assertEquals("Row 40", hit.path("hits").get(0).path("name").asText(), found.out);

        Capture after = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll",
                "--expr=document.body.innerText.indexOf('Row 40') >= 0");
        assertEquals(0, after.code, after.err + after.out);
        assertEquals("true", JSON.readTree(after.out).path("value").asText(), after.out);

        Capture snap = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=scroll");
        assertEquals(0, snap.code, snap.err + snap.out);
        assertTrue(JSON.readTree(snap.out).path("virtualized").asBoolean(false), snap.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void scrollSearchUsesTheVirtualListAndRestoresAfterAMiss() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("two-boxes.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <div id="static" style="height:40px;overflow:auto">
                  <div style="height:180px">Static head</div>
                </div>
                <div id="list" style="height:100px;overflow:auto">
                  <div id="pad" style="height:2000px;position:relative"></div>
                </div>
                <script>
                const list = document.getElementById('list');
                const pad = document.getElementById('pad');
                function render() {
                  const start = Math.floor(list.scrollTop / 20);
                  let html = '';
                  for (let i = start; i < start + 6; i++) {
                    html += '<div class="row" style="position:absolute;top:' + (i * 20) + 'px;height:20px">Row ' + i + '</div>';
                  }
                  pad.innerHTML = html;
                }
                list.addEventListener('scroll', render);
                render();
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=scroll-two");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertEquals("0,0", scrolls(), started.out);

        Capture found = run(
                "find", "--target=browser:auto", "--query=Row 40", "--scroll-search=true", "--run-id=scroll-two");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertTrue(hit.path("applied").asBoolean(), found.out);
        assertEquals("Row 40", hit.path("hits").get(0).path("name").asText(), found.out);
        String afterHit = scrolls();
        assertTrue(afterHit.startsWith("0,"), afterHit);
        int listTop = Integer.parseInt(afterHit.substring(2));
        assertTrue(listTop > 0, afterHit);

        Capture parked = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll-two",
                "--expr=document.getElementById('static').scrollTop=25; document.getElementById('list').scrollTop=80; 'set'");
        assertEquals(0, parked.code, parked.err + parked.out);
        assertEquals("25,80", scrolls(), parked.out);

        Capture missing = run(
                "find", "--target=browser:auto", "--query=No Such Row", "--scroll-search=true", "--run-id=scroll-two");
        assertEquals(1, missing.code, missing.err + missing.out);
        assertTrue(missing.out.contains("not found"), missing.out);
        assertEquals("25,80", scrolls(), missing.out);
    }

    /**
     * A tall static box sits before a decoy list and the real list.
     * Kills "first tall box wins", "single container only", and "no restore".
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void scrollSearchSkipsTheTallBoxAndReportsTheCap() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("tall-first.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><style>html,body{margin:0}</style></head><body>
                <div id="static" style="height:120px;overflow:auto;border:0">
                  <div style="height:2000px">Static panel</div>
                </div>
                <div id="decoy" style="height:100px;overflow:auto;border:0">
                  <div id="decoy-pad" style="height:500px;position:relative"></div>
                </div>
                <div id="list" style="height:100px;overflow:auto;border:0">
                  <div id="pad" style="height:2000px;position:relative"></div>
                </div>
                <script>
                function mount(box, pad, label, modulo) {
                  const start = Math.floor(box.scrollTop / 20);
                  let html = '';
                  for (let i = start; i < start + 6; i++) {
                    const n = modulo ? (i % 6) : i;
                    html += '<div class="row" style="position:absolute;top:' + (i * 20) + 'px;height:20px">'
                      + label + ' ' + n + '</div>';
                  }
                  pad.innerHTML = html;
                }
                const decoy = document.getElementById('decoy');
                const decoyPad = document.getElementById('decoy-pad');
                const list = document.getElementById('list');
                const pad = document.getElementById('pad');
                decoy.addEventListener('scroll', function(){ mount(decoy, decoyPad, 'Item', true); });
                list.addEventListener('scroll', function(){ mount(list, pad, 'Item', false); });
                mount(decoy, decoyPad, 'Item', true);
                mount(list, pad, 'Item', false);
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=scroll-tall");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals("0,0,0", tops("scroll-tall"), started.out);

        Capture found = run(
                "find", "--target=browser:auto", "--query=Item 50", "--scroll-search=true", "--run-id=scroll-tall");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertTrue(hit.path("scrollSearch").asBoolean(false), found.out);
        assertEquals("Item 50", hit.path("hits").get(0).path("name").asText(), found.out);
        String afterHit = tops("scroll-tall");
        assertTrue(afterHit.startsWith("0,0,"), afterHit);
        int listTop = Integer.parseInt(afterHit.substring(afterHit.lastIndexOf(',') + 1));
        assertTrue(listTop >= 800, afterHit);

        Capture parked = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll-tall",
                "--expr=document.getElementById('static').scrollTop=25;"
                        + "document.getElementById('decoy').scrollTop=11;"
                        + "document.getElementById('list').scrollTop=80; 'set'");
        assertEquals(0, parked.code, parked.err + parked.out);
        assertEquals("25,11,80", tops("scroll-tall"), parked.out);
        Capture missed = run(
                "find", "--target=browser:auto", "--query=Nope", "--scroll-search=true", "--run-id=scroll-tall");
        assertEquals(1, missed.code, missed.err + missed.out);
        assertEquals("not found", JSON.readTree(missed.out).path("error").asText(), missed.out);
        assertEquals("25,11,80", tops("scroll-tall"), missed.out);

        ConsumerReadyCommands.stopQuiet(temp);
        Path capped = temp.resolve("capped.html");
        Files.writeString(capped, """
                <!DOCTYPE html><html><head><style>html,body{margin:0}</style></head><body>
                <div id="static" style="height:120px;overflow:auto;border:0">
                  <div style="height:2000px">Static panel</div>
                </div>
                <div id="list" style="height:100px;overflow:auto;border:0">
                  <div id="pad" style="height:20000px;position:relative"></div>
                </div>
                <script>
                const list = document.getElementById('list');
                const pad = document.getElementById('pad');
                function render() {
                  const start = Math.floor(list.scrollTop / 20);
                  let html = '';
                  for (let i = start; i < start + 6; i++) {
                    html += '<div class="row" style="position:absolute;top:' + (i * 20) + 'px;height:20px">Item ' + i + '</div>';
                  }
                  pad.innerHTML = html;
                }
                list.addEventListener('scroll', render);
                render();
                </script>
                </body></html>
                """);
        Capture again = run("start", "--url=" + capped.toUri(), "--browser=chrome-headless", "--run-id=scroll-cap");
        assertEquals(0, again.code, again.err + again.out);
        Capture place = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll-cap",
                "--expr=document.getElementById('static').scrollTop=17; document.getElementById('list').scrollTop=30; 'set'");
        assertEquals(0, place.code, place.err + place.out);
        assertEquals("17,30", pair("scroll-cap"), place.out);
        Capture beyond = run(
                "find", "--target=browser:auto", "--query=Item 400", "--scroll-search=true", "--run-id=scroll-cap");
        assertEquals(1, beyond.code, beyond.err + beyond.out);
        JsonNode miss = JSON.readTree(beyond.out);
        assertEquals("not found", miss.path("error").asText(), beyond.out);
        assertEquals("list", miss.path("scrollSearch").path("container").asText(), beyond.out);
        assertEquals(40, miss.path("scrollSearch").path("steps").asInt(-1), beyond.out);
        assertTrue(miss.path("scrollSearch").path("capped").asBoolean(false), beyond.out);
        assertEquals("17,30", pair("scroll-cap"), beyond.out);
    }

    private String tops(String runId) throws Exception {
        return value(runId, "document.getElementById('static').scrollTop + ','"
                + " + document.getElementById('decoy').scrollTop + ','"
                + " + document.getElementById('list').scrollTop");
    }

    private String pair(String runId) throws Exception {
        return value(runId, "document.getElementById('static').scrollTop + ','"
                + " + document.getElementById('list').scrollTop");
    }

    private String value(String runId, String expression) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private String scrolls() throws Exception {
        Capture read = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=scroll-two",
                "--expr=document.getElementById('static').scrollTop + ',' + document.getElementById('list').scrollTop");
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
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
