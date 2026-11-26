package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.TargetVerb;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A short id stays the element. Placement must not turn it into the accessible name. */
class ShortIdPlacementTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void shortIdIsNotTheAccessibleName() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        TargetVerb.Outcome found = TargetVerb.run(temp, "short-ids", "find", Map.of(
                "target", "desktop:app~Notes",
                "fixture", fixture.toString(),
                "query", "Save"
        ));
        assertEquals(0, found.exit(), found.body().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((java.util.List<Map<String, Object>>) found.body().get("hits")).get(0);
        String id = String.valueOf(row.get("handle"));
        assertTrue(id.matches("s\\d+\\.h\\d+"), id);
        assertEquals("Save", String.valueOf(row.get("name")));
        String placed = TargetVerb.placementHandle(id);
        assertTrue(placed != null && placed.matches(".+:\\d+"), placed);
        assertNotEquals("Save", placed);
        assertNotEquals(id, placed);

        TargetVerb.Outcome acted = TargetVerb.run(temp, "short-ids", "act", Map.of(
                "query", id,
                "kind", "click",
                "target", "desktop:app~Notes",
                "fixture", fixture.toString()
        ));
        assertEquals(0, acted.exit(), acted.body().toString());
        assertFalse(acted.body().toString().contains("unknown handle: Save"), acted.body().toString());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void placementAndActUseAShortIdOnTheLivePage() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("short.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Short</title></head><body>
                <button>Save</button>
                <button id="keep">Keep</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=short-live");
        assertEquals(0, started.code, started.err + started.out);
        Capture found = run("find", "--target=browser:auto", "--query=Save", "--run-id=short-live");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode save = JSON.readTree(found.out).path("items").get(0);
        String id = save.path("handle").asText();
        assertTrue(id.matches("s\\d+\\.h\\d+"), found.out);
        assertEquals("Save", save.path("name").asText(), found.out);
        for (String[] verb : new String[][]{
                {"border", "--handle=" + id},
                {"label", "--text=Here", "--handle=" + id},
                {"magnifier", "--handle=" + id},
                {"spotlight", "--handle=" + id},
                {"highlight", "--handle=" + id, "--preset=yellow", "--target=browser:auto"},
                {"unhighlight", "--handle=" + id}
        }) {
            String[] args = new String[verb.length + 1];
            args[0] = verb[0];
            System.arraycopy(verb, 1, args, 1, verb.length - 1);
            args[args.length - 1] = "--run-id=short-live";
            Capture placed = run(args);
            assertEquals(0, placed.code, verb[0] + " " + placed.err + placed.out);
            assertFalse((placed.out + placed.err).contains("unknown handle"), verb[0] + " " + placed.out + placed.err);
        }
        Capture acted = run("act", "--query=" + id, "--kind=click", "--target=browser:auto", "--run-id=short-live");
        assertEquals(0, acted.code, acted.err + acted.out);
        assertFalse((acted.out + acted.err).contains("unknown handle"), acted.out);

        Capture keep = run("find", "--target=browser:auto", "--query=Keep", "--run-id=short-live");
        assertEquals(0, keep.code, keep.err + keep.out);
        String keepId = JSON.readTree(keep.out).path("items").get(0).path("handle").asText();
        Capture labeled = run("label", "--text=Kept", "--handle=" + keepId, "--run-id=short-live");
        assertEquals(0, labeled.code, labeled.err + labeled.out);
        assertFalse((labeled.out + labeled.err).contains("unknown handle"), labeled.out);
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
