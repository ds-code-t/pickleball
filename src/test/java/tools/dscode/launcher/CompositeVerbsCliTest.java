package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.coredefinitions.NavigationSteps;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Composite verbs, call, url-key, fixture pages, and the human chat filter. */
class CompositeVerbsCliTest {
    @TempDir
    Path temp;

    @Test
    void covers_command_call() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Capture missing = run("call", temp.toString());
        assertEquals(2, missing.code, missing.err);
        assertTrue(missing.err.contains("error:"), missing.err);
        Capture sent = run("call", "chat-send", "--text=from-call", "--run-id=called", temp.toString());
        assertEquals(0, sent.code, sent.out + sent.err);
        assertTrue(sent.out.contains("from-call"), sent.out);
        Capture unavailable = run("call", "not-a-tool", temp.toString());
        assertEquals(1, unavailable.code, unavailable.out + unavailable.err);
        assertTrue(unavailable.out.contains("unavailable"), unavailable.out);
        assertTrue(unavailable.out.contains("\"v\":1"), unavailable.out);
    }

    @Test
    void covers_command_chat_turn() {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Capture usage = run("chat-turn", "--run-id=turn", temp.toString());
        assertEquals(2, usage.code, usage.err);
        assertTrue(usage.err.contains("error:"), usage.err);
        Capture timed = run(
                "chat-turn", "--text=hello-turn", "--until=human", "--timeout=0", "--run-id=turn", temp.toString()
        );
        assertEquals(1, timed.code, timed.out + timed.err);
        assertTrue(timed.out.contains("timeout") || timed.err.contains("timeout"), timed.out + timed.err);
        assertTrue(timed.out.contains("\"op\":\"chat-turn\""), timed.out);
    }

    @Test
    void covers_command_chat_turn_lists_the_human_once() throws Exception {
        Thread human = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            run("chat-send", "--text=only-once-human", "--source=human", "--run-id=once", temp.toString());
        });
        human.start();
        Capture turn = run(
                "chat-turn", "--text=agent-asks", "--until=human", "--timeout=5", "--run-id=once", temp.toString()
        );
        human.join(6000);
        assertEquals(0, turn.code, turn.out + turn.err);
        JsonNode node = new ObjectMapper().readTree(turn.out);
        assertEquals(1, node.path("v").asInt());
        assertTrue(node.path("state").isObject(), turn.out);
        assertEquals(1, node.path("messages").size(), turn.out);
        assertTrue(node.path("messages").toString().contains("only-once-human"), turn.out);
        assertFalse(node.has("lines"), turn.out);
        assertTrue(node.path("items").toString().contains("only-once-human"), turn.out);
        int copies = 0;
        int from = 0;
        while ((from = turn.out.indexOf("only-once-human", from)) >= 0) {
            copies++;
            from += "only-once-human".length();
        }
        assertEquals(2, copies, turn.out);
    }

    @Test
    void covers_command_execute_steps() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Path file = temp.resolve("steps.json");
        Files.writeString(file, """
                [{"op":"chat-send","args":{"text":"step-one","source":"human"}},{"op":"no-such-verb","args":{}}]
                """);
        Capture stopped = run("execute-steps", "--file=" + file, "--stop-on-fail", "--run-id=steps", temp.toString());
        assertEquals(2, stopped.code, stopped.out + stopped.err);
        assertTrue(stopped.out.contains("execute-steps"), stopped.out);
        assertTrue(stopped.out.contains("step-one") || stopped.out.contains("chat-send"), stopped.out);
        assertTrue(stopped.out.contains("\"stopped\":true"), stopped.out);
        assertTrue(stopped.out.contains("steps stopped"), stopped.out);
        assertFalse(stopped.out.contains("steps failed"), stopped.out);
        assertTrue(Files.readString(temp.resolve(".pickleball/runs/steps/agent/chat/run.jsonl")).contains("step-one"));
    }

    @Test
    void covers_command_execute_steps_failure_is_not_stopped() throws Exception {
        Path file = temp.resolve("steps-continue.json");
        Files.writeString(file, """
                [{"op":"chat-send","args":{"text":"kept-going","source":"human"}},{"op":"no-such-verb","args":{}}]
                """);
        Capture continued = run("execute-steps", "--file=" + file, "--run-id=continue", temp.toString());
        assertEquals(2, continued.code, continued.out + continued.err);
        assertTrue(continued.out.contains("\"stopped\":false"), continued.out);
        assertTrue(continued.out.contains("steps failed"), continued.out);
        assertFalse(continued.out.contains("steps stopped"), continued.out);
        assertTrue(continued.out.contains("\"ran\":2"), continued.out);
        assertTrue(Files.readString(temp.resolve(".pickleball/runs/continue/agent/chat/run.jsonl")).contains("kept-going"));
        assertFalse(continued.out.contains("unknown agent command"), continued.out);
    }

    @Test
    void covers_command_ui_verify() {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Capture missing = run("ui-verify", temp.toString());
        assertEquals(1, missing.code, missing.out + missing.err);
        assertTrue((missing.out + missing.err).contains("no live browser"), missing.out + missing.err);
        assertTrue(missing.out.contains("\"op\":\"ui-verify\""), missing.out);
    }

    @Test
    void actPickIsARealFlagAndFixtureUrlsResolve() throws Exception {
        Capture rejected = run("act", "--pick=1", "--query=Save", "--quiet", temp.toString());
        assertEquals(2, rejected.code, rejected.err);
        assertTrue(rejected.err.contains("does not use --quiet"), rejected.err);
        assertFalse(rejected.err.contains("does not use --pick"), rejected.err);
        String page = NavigationSteps.addressAfterConfigLookup("fixture:panel", null);
        assertTrue(page.startsWith("data:text/html"), page);
        String html = java.net.URLDecoder.decode(page.substring(page.indexOf(',') + 1), StandardCharsets.UTF_8);
        assertTrue(html.contains("Save"), html);
        Path yaml = temp.resolve("src/test/resources/URL.yaml");
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, "URL:\n  app: https://example.test/app\n");
        assertEquals("https://example.test/app", UrlKeys.resolve(temp, "URL.app"));
        LeaseHold.arm(temp, "30s");
        assertTrue(LeaseHold.left(temp).matches("\\d+"), LeaseHold.left(temp));
        Capture doctor = run("doctor", temp.toString());
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("leaseLeft="), doctor.out);
        assertTrue(doctor.out.contains("orphans="), doctor.out);
        assertTrue(doctor.out.contains("lease=held"), doctor.out);
        String chrome = BrowserSession.findChrome();
        if (chrome != null) assertTrue(Files.isRegularFile(Path.of(chrome)), chrome);
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
