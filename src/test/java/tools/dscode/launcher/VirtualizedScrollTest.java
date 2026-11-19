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
