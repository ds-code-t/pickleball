package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.desktop.DesktopTree;
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

/** Phase 4: find and act use the desktop backend with the same verb strings. */
class DesktopSameVerbTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void resetDesktop() {
        DesktopTree.resetForTests();
    }

    @Test
    void covers_command_act() throws Exception {
        Capture unknown = agent("find", "--target=desktop:999999", "--query=Save", "--run-id=desk-missing");
        assertEquals(1, unknown.code, unknown.out + unknown.err);
        assertTrue(unknown.out.contains("unknown target"), unknown.out);
        assertFalse(unknown.out.contains("\"ok\":true"), unknown.out);

        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture found = agent(
                "find", "--target=desktop:app~Notes", "--fixture=" + fixture, "--query=Save", "--run-id=desk");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertFalse(hit.path("applied").asBoolean(), found.out);
        assertEquals("desktop", hit.path("used").asText(), found.out);
        assertEquals("Save", hit.path("hits").get(0).path("name").asText(), found.out);
        String ref = hit.path("ref").asText();
        assertTrue(ref.startsWith("ref:desktop:0:"), ref);

        Capture acted = agent("act", "--target=" + ref, "--kind=click", "--run-id=desk");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertFalse(act.path("applied").asBoolean(), acted.out);
        assertEquals("desktop:1", act.path("docVersion").asText(), acted.out);

        Capture stale = agent("act", "--target=" + ref, "--kind=click", "--run-id=desk");
        assertEquals(1, stale.code, stale.err + stale.out);
        assertTrue(stale.out.contains("stale_ref"), stale.out);
        assertTrue(stale.out.contains("\"applied\":false"), stale.out);
        assertFalse(stale.out.contains("\"ok\":true"), stale.out);

        long before = System.nanoTime();
        Capture missed = agent(
                "wait", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--condition=text=Missing", "--timeout=400", "--run-id=desk-wait");
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        assertEquals(1, missed.code, missed.err + missed.out);
        assertTrue(missed.out.contains("timeout"), missed.out);
        assertTrue(missed.out.contains("\"applied\":false"), missed.out);
        assertTrue(elapsedMs >= 300, "desktop wait returned after one check: " + elapsedMs);

        Capture browserOnly = agent("watch", "--target=desktop:app~Notes", "--fixture=" + fixture, "--run-id=desk-watch");
        assertEquals(1, browserOnly.code, browserOnly.err + browserOnly.out);
        assertTrue(browserOnly.out.contains("watch is browser only"), browserOnly.out);
        assertTrue(browserOnly.out.contains("\"applied\":false"), browserOnly.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void liveDesktopResolvesPidAndClicksOrSkips() throws Exception {
        dev.domengine.desktop.DesktopTree.Session probe;
        try {
            probe = dev.domengine.desktop.DesktopTree.attachLive("desk-probe", "");
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            assumeTrue(false, "skipped: " + message);
            return;
        }
        String pid = firstPid(probe.root());
        String name = pid == null ? null : firstName(probe.root(), pid);
        dev.domengine.desktop.DesktopTree.close("desk-probe");
        assumeTrue(pid != null && name != null, "skipped: AT-SPI tree has no named window for a process id");
        Capture found = agent("find", "--target=desktop:" + pid, "--query=" + name, "--run-id=desk-live");
        assertEquals(0, found.code, found.out + found.err);
        assertTrue(JSON.readTree(found.out).path("applied").asBoolean(), found.out);
        assertEquals(name, JSON.readTree(found.out).path("hits").get(0).path("name").asText(), found.out);
        Capture wrong = agent("find", "--target=desktop:" + ProcessHandle.current().pid(), "--run-id=desk-wrong");
        if (String.valueOf(ProcessHandle.current().pid()).equals(pid)) {
            wrong = agent("find", "--target=desktop:1", "--run-id=desk-wrong");
        }
        assertEquals(1, wrong.code, wrong.out + wrong.err);
        assertTrue(wrong.out.contains("unknown target") || wrong.out.contains("no desktop target"), wrong.out);

        assumeTrue(java.nio.file.Files.isExecutable(Path.of("/usr/bin/xmessage")), "skipped: xmessage is not installed");
        String title = "PkbLiveDesk" + Long.toUnsignedString(System.nanoTime());
        Process window = new ProcessBuilder("xmessage", "-buttons", "Go:0", "-title", title, "ready")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            Capture button = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                button = agent("find", "--target=desktop:app~" + title, "--query=Go", "--run-id=desk-click");
                if (button.code == 0 && button.out.contains("\"ref\"")) break;
                Thread.sleep(250);
            }
            assumeTrue(button != null && button.code == 0, "skipped: xmessage was not on the accessibility tree");
            String ref = JSON.readTree(button.out).path("ref").asText();
            Capture acted = agent("act", "--target=" + ref, "--kind=click", "--run-id=desk-click");
            assertEquals(0, acted.code, acted.err + acted.out);
            assertTrue(JSON.readTree(acted.out).path("applied").asBoolean(), acted.out);
            assertTrue(window.waitFor(5, TimeUnit.SECONDS), "click did not dismiss xmessage");
            assertEquals(0, window.exitValue());
        } finally {
            window.destroyForcibly();
        }
    }

    private static String firstPid(dev.domengine.tree.DomNode node) {
        if (node == null) return null;
        String pid = node.attributes.get("pid");
        if (pid != null && !pid.isBlank() && !"0".equals(pid) && node.accessibleName != null && !node.accessibleName.isBlank()) {
            return pid;
        }
        for (dev.domengine.tree.DomNode child : node.children) {
            String found = firstPid(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String firstName(dev.domengine.tree.DomNode node, String pid) {
        if (node == null) return null;
        if (pid.equals(node.attributes.get("pid")) && node.accessibleName != null && !node.accessibleName.isBlank()) {
            return node.accessibleName;
        }
        for (dev.domengine.tree.DomNode child : node.children) {
            String found = firstName(child, pid);
            if (found != null) return found;
        }
        return null;
    }

    private Capture agent(String... args) {
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
