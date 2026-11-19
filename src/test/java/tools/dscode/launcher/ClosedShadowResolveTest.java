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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 5: a closed-shadow button is clicked through DOM.resolveNode in the pkb world. */
class ClosedShadowResolveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void closedShadowClickUsesResolveNodeAndChangesTheTitle() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("closed.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <div id="host"></div>
                <script>
                const root = document.getElementById('host').attachShadow({mode:'closed'});
                const button = document.createElement('button');
                button.id = 'secret';
                button.textContent = 'Secret';
                button.style.pointerEvents = 'none';
                button.onclick = () => { document.title = 'SecretClicked'; };
                root.appendChild(button);
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=shadow");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture hidden = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=shadow",
                "--expr=document.querySelector('#secret') === null");
        assertEquals(0, hidden.code, hidden.err + hidden.out);
        assertEquals("true", JSON.readTree(hidden.out).path("value").asText(), hidden.out);

        Capture found = run("find", "--target=browser:auto", "--query=Secret", "--run-id=shadow");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertEquals("Secret", hit.path("hits").get(0).path("name").asText(), found.out);
        assertEquals("closed", hit.path("hits").get(0).path("shadow").asText(), found.out);
        assertEquals("/html[1]/body[1]/div[1]", hit.path("hits").get(0).path("shadow_path").asText(), found.out);

        Capture acted = run("act", "--target=browser:auto", "--query=Secret", "--kind=click", "--run-id=shadow");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertTrue(act.path("applied").asBoolean(), acted.out);
        assertEquals("resolveNode", act.path("used").asText(), acted.out);

        Capture title = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=shadow",
                "--expr=document.title");
        assertEquals(0, title.code, title.err + title.out);
        assertEquals("SecretClicked", JSON.readTree(title.out).path("value").asText(), title.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void closedShadowClickFiresOnceWhenTheTitleStays() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("once.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <div id="host"></div>
                <div id="openhost"></div>
                <script>
                const root = document.getElementById('host').attachShadow({mode:'closed'});
                const button = document.createElement('button');
                button.id = 'once';
                button.textContent = 'Once';
                button.style.pointerEvents = 'none';
                window.__h = 0;
                button.onclick = () => { window.__h++; button.dataset.n = String(window.__h); };
                root.appendChild(button);
                const open = document.getElementById('openhost').attachShadow({mode:'open'});
                const other = document.createElement('button');
                other.id = 'openbtn';
                other.textContent = 'OpenBtn';
                open.appendChild(other);
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=once");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture open = run("find", "--target=browser:auto", "--query=OpenBtn", "--run-id=once");
        assertEquals(0, open.code, open.err + open.out);
        JsonNode openHit = JSON.readTree(open.out);
        assertEquals("OpenBtn", openHit.path("hits").get(0).path("name").asText(), open.out);
        assertEquals("open", openHit.path("hits").get(0).path("shadow").asText(), open.out);
        assertEquals("/html[1]/body[1]/div[2]", openHit.path("hits").get(0).path("shadow_path").asText(), open.out);

        Capture found = run("find", "--target=browser:auto", "--query=Once", "--run-id=once");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertEquals("Once", hit.path("hits").get(0).path("name").asText(), found.out);
        assertEquals("closed", hit.path("hits").get(0).path("shadow").asText(), found.out);
        assertEquals("/html[1]/body[1]/div[1]", hit.path("hits").get(0).path("shadow_path").asText(), found.out);
        assertEquals("diff", hit.path("contexts").asText(""), found.out);
        assertEquals(
                "steps stay on the main document, same-origin frames, and open shadow; agent also has closed-shadow #host",
                hit.path("contextDiff").asText(""),
                found.out);

        Capture acted = run("act", "--target=browser:auto", "--query=Once", "--kind=click", "--run-id=once");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertTrue(act.path("applied").asBoolean(), acted.out);
        assertEquals("resolveNode", act.path("used").asText(), acted.out);

        Capture count = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=once",
                "--expr=String(window.__h) + '|' + document.title");
        assertEquals(0, count.code, count.err + count.out);
        assertEquals("1|Ready", JSON.readTree(count.out).path("value").asText(), count.out);
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
