package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import dev.domengine.session.BrowserSession;
import tools.dscode.control.protocol.VerbTiers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** D4: every verb has a tier, and a human-locked session runs only human-tier input. */
class HumanTierTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @org.junit.jupiter.api.AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void everyCliVerbAndMcpToolHasATierInTheOneRegistry() throws Exception {
        JsonNode inventory = JSON.readTree(Files.readString(Path.of("docs/roadmaps/workbench-cli-inventory.json")));
        List<String> missing = new ArrayList<>();
        inventory.path("commands").forEach(list -> list.forEach(verb -> {
            if (VerbTiers.tier(verb.asText()) == null) missing.add(verb.asText());
        }));
        assertTrue(missing.isEmpty(), "verbs with no tier: " + missing);
        Map<String, String> listed = new TreeMap<>();
        inventory.path("tiers").fields().forEachRemaining(entry -> listed.put(entry.getKey(), entry.getValue().asText()));
        assertEquals(new TreeMap<>(VerbTiers.table()), listed, "the inventory tiers column is the registry");
        for (String human : List.of("snap", "find", "act", "wait", "capture", "chat-read", "doctor")) {
            assertEquals(VerbTiers.HUMAN, VerbTiers.tier(human), human);
        }
        for (String control : List.of("eval", "ui-apply", "form-fill", "upload", "navigate", "guide", "ask", "highlight", "start")) {
            assertEquals(VerbTiers.CONTROL, VerbTiers.tier(control), control);
        }
        assertEquals(VerbTiers.HUMAN, VerbTiers.toolTier("workbench_snap"));
        assertEquals(VerbTiers.CONTROL, VerbTiers.toolTier("workbench_form_fill"));
        assertEquals("Tier: control.\n", WorkbenchCliHelp.tierLine("eval"));
        assertEquals("Tier: human.\n", WorkbenchCliHelp.tierLine("snap"));
        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        assertEquals(0, WorkbenchCliHelp.answer(new String[]{"help", "snap"}, new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(helpOut.toString(StandardCharsets.UTF_8).contains("Tier: human."), helpOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void covers_command_session_mode() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("d4.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Human</title></head><body>
                <button id="open" onclick="document.title='opened'">Open</button>
                <input id="name" aria-label="Name">
                <div style="position:relative;width:200px;height:60px">
                  <button id="under" style="position:absolute;inset:0" onclick="window.underClicked=true">Under</button>
                  <div style="position:absolute;inset:0;background:#fff" aria-hidden="true"></div>
                </div>
                </body></html>""");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=d4", "--human");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("human=on"), started.out);
        assertTrue(Files.exists(temp.resolve(".pickleball/human-lock/d4.json")));

        for (String[] control : List.of(
                new String[]{"eval", "--target=browser:auto", "--expr=1", "--run-id=d4"},
                new String[]{"ui-apply", "--ops=[]", "--run-id=d4"},
                new String[]{"form-fill", "--target=browser:auto", "--fields=Name=x", "--run-id=d4"})) {
            Capture refused = run(control);
            assertEquals(2, refused.code, control[0] + ": " + refused.out + refused.err);
            assertTrue(refused.err.contains("error: " + control[0] + " is a control-tier verb"), refused.err);
            assertTrue(refused.err.contains("fix: ") && refused.err.contains("session-mode --human=off"), refused.err);
        }
        Capture attach = run("act", "--target=browser:auto", "--kind=attach", "--query=Name", "--value=/tmp/x", "--run-id=d4");
        assertEquals(2, attach.code);
        assertTrue(attach.err.contains("unavailable in human mode"), attach.err);

        Capture snap = run("snap", "--target=browser:auto", "--run-id=d4");
        assertEquals(0, snap.code, snap.err + snap.out);
        assertEquals("human", JSON.readTree(snap.out).path("exposure").asText(), "queries default to the human mask: " + snap.out);
        assertEquals(0, run("find", "--target=browser:auto", "--query=Open", "--run-id=d4").code);
        Capture click = run("act", "--target=browser:auto", "--kind=click", "--query=Open", "--run-id=d4");
        assertEquals(0, click.code, click.err + click.out);
        assertEquals(0, run("act", "--target=browser:auto", "--kind=type", "--query=Name", "--value=Ann", "--run-id=d4").code);
        assertEquals(0, run("act", "--target=browser:auto", "--kind=press", "--query=Name", "--value=Tab", "--run-id=d4").code);
        assertEquals(0, run("chat-read", "--run-id=d4").code);

        Capture covered = run("act", "--target=browser:auto", "--kind=click", "--query=Under", "--exposure=full", "--run-id=d4");
        assertTrue(covered.code != 0 || covered.out.contains("covered"), covered.out + covered.err);
        assertTrue((covered.out + covered.err).contains("covered"), "a covered element is not clicked through: " + covered.out + covered.err);

        Capture off = run("session-mode", "--human=off", "--run-id=d4");
        assertEquals(0, off.code, off.err);
        Capture underClicked = run("eval", "--target=browser:auto", "--world=main", "--expr=String(!!window.underClicked)", "--run-id=d4");
        assertEquals(0, underClicked.code, "leaving human mode is explicit and control verbs run again: " + underClicked.err);
        assertEquals("false", JSON.readTree(underClicked.out).path("value").asText());
        assertNotNull(JSON.readTree(run("session-mode", "--run-id=d4").out).path("human").asText());
        assertEquals("off", JSON.readTree(run("session-mode", "--run-id=d4").out).path("human").asText());
    }

    @Test
    void covers_flag_human() throws Exception {
        Capture refused = run("session-mode", "--human=maybe", "--run-id=f1");
        assertEquals(2, refused.code);
        assertTrue(refused.err.contains("error: session-mode --human must be on or off"), refused.err);
        assertEquals(0, run("session-mode", "--human=on", "--run-id=f1").code);
        assertTrue(Files.exists(temp.resolve(".pickleball/human-lock/f1.json")));
        assertEquals("on", JSON.readTree(run("session-mode", "--run-id=f1").out).path("human").asText());
        Capture blocked = run("note", "--text=hi", "--run-id=f1");
        assertEquals(2, blocked.code, blocked.err);
        assertTrue(blocked.err.contains("note is a control-tier verb"), blocked.err);
    }

    @Test
    void covers_flag_human_override() throws Exception {
        assertEquals(0, run("session-mode", "--human=on", "--agent-id=owner-a", "--run-id=f2").code);
        Capture stranger = run("note", "--text=hi", "--human-override=debug", "--agent-id=someone-else", "--run-id=f2");
        assertEquals(2, stranger.code, stranger.err);
        assertTrue(stranger.err.contains("only for the session owner"), stranger.err);
        Capture owner = run("note", "--text=hi", "--human-override=debug", "--agent-id=owner-a", "--run-id=f2");
        assertTrue(!owner.err.contains("control-tier") && !owner.err.contains("session owner"), "the owner's logged override passes the lock: " + owner.err);
        assertTrue(Files.exists(temp.resolve(".pickleball/human-lock/f2.json")), "an override does not leave human mode");
    }

    /** D4/D5: act and call accept --human-override for the owner and still refuse a stranger. */
    @Test
    void covers_act_and_call_human_override() throws Exception {
        assertEquals(0, run("session-mode", "--human=on", "--agent-id=owner-a", "--run-id=f3").code);
        Capture act = run("act", "--target=browser:auto", "--kind=click", "--query=Go",
                "--human-override=agent", "--agent-id=owner-a", "--run-id=f3");
        assertFalse((act.out + act.err).contains("does not use --human-override"), act.out + act.err);
        Capture stranger = run("act", "--target=browser:auto", "--kind=click", "--query=Go",
                "--human-override=agent", "--agent-id=someone-else", "--run-id=f3");
        assertTrue((stranger.out + stranger.err).contains("only for the session owner"), stranger.out + stranger.err);
        java.nio.file.Path file = temp.resolve("call-act.json");
        java.nio.file.Files.writeString(file, "{\"target\":\"browser:auto\",\"kind\":\"click\",\"query\":\"Go\"}");
        Capture call = run("call", "act", "--args-file=" + file, "--human-override=agent", "--agent-id=owner-a", "--run-id=f3");
        assertFalse((call.out + call.err).contains("does not use --human-override"), call.out + call.err);
    }

    Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
