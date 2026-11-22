package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AnswerRecovery;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.common.agentfast.SessionIds;
import tools.dscode.common.recording.InteractionLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No-browser regression for the high-impact field-report items.
 * Live Chrome checks stay in the tests named by
 * {@code docs/maintainer/3.0.0-field-report-regression.md}.
 */
class FieldReportRegressionTest {
    @TempDir
    Path temp;

    @BeforeEach
    void resetPush() throws Exception {
        var reset = PushChannel.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @AfterEach
    void clearPush() throws Exception {
        resetPush();
        System.clearProperty(AgentHop.INSIDE);
        AgentController.clearStopAfterReplyForTests();
    }

    @Test
    void controllerPathHopsLiveVerbsAndKeepsStopLocal() {
        assertFalse(AgentHop.shouldHop("live-stop", new String[]{"live-stop"}));
        assertFalse(AgentHop.shouldHop("start", new String[]{"start", "--stop"}));
        assertFalse(AgentHop.shouldHop("export-guidance", new String[]{"export-guidance"}));
        assertTrue(AgentHop.shouldHop("chat-turn", new String[]{"chat-turn"}));
        assertTrue(AgentHop.shouldHop("watch", new String[]{"watch"}));
        assertTrue(AgentHop.shouldHop("execute-steps", new String[]{"execute-steps"}));
        assertTrue(AgentHop.shouldHop("act", new String[]{"act"}));
        System.setProperty(AgentHop.INSIDE, "true");
        assertFalse(AgentHop.shouldHop("chat-turn", new String[]{"chat-turn"}));
        assertFalse(AgentHop.shouldHop("live-stop", new String[]{"live-stop"}));
    }

    @Test
    void executeStepsRunsDoctorHereAndDoesNotStopWithoutTheFlag() throws Exception {
        Path file = temp.resolve("steps.json");
        Files.writeString(file, "[{\"op\":\"doctor\",\"args\":{}}]");
        Capture capture = agent("execute-steps", temp.toString(), "--file=" + file);
        assertEquals(0, capture.code, capture.out + capture.err);
        assertTrue(capture.out.contains("chrome="), capture.out);
        assertTrue(capture.out.contains("\"op\":\"execute-steps\""), capture.out);
        assertTrue(capture.out.contains("\"op\":\"doctor\""), capture.out);
        assertTrue(capture.out.contains("\"stopped\":false"), capture.out);
        assertTrue(capture.out.contains("steps ok"), capture.out);
        assertTrue(page("execute-steps").contains("doctor runs in this process"));
        assertTrue(page("validate").contains("noop:true"));
    }

    @Test
    void chatTurnAndWatchStayOnTheDocumentedContract() {
        Capture turn = agent(
                "chat-turn", temp.toString(), "--text=hello-turn", "--until=human", "--timeout=0", "--run-id=reg"
        );
        assertEquals(1, turn.code, turn.out + turn.err);
        assertTrue(turn.out.contains("\"op\":\"chat-turn\""), turn.out);
        assertTrue(turn.out.contains("timeout") || turn.err.contains("timeout"), turn.out + turn.err);
        String watch = page("watch");
        assertTrue(watch.contains("iframe"));
        assertTrue(page("chat-turn").contains("--until"));
    }

    @Test
    void shortIdsAndLeanRepliesPreferTheShortId() {
        String full = "ref:" + "z".repeat(80);
        String id = SessionIds.intern("field-report", 42, full);
        assertTrue(id.matches("s\\d+\\.h\\d+"), id);
        assertEquals(full, SessionIds.expand(id));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", "Say \\\"hi\\\"");
        row.put("ref", "r".repeat(80));
        row.put("handle", "h".repeat(60));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("elapsedMs", 1);
        body.put("lines", List.of(row));
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 0, "snap", temp, "field-report");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) wrapped.get("items");
        assertEquals(1, items.size());
        assertEquals("Say \"hi\"", items.get(0).get("name"));
        assertFalse(items.get(0).containsKey("ref"));
        assertEquals(id, items.get(0).get("handle"));
    }

    @Test
    void askAndWaitReturnsTheStoredChoice() throws Exception {
        Path events = temp.resolve(".pickleball/agent-events.jsonl");
        Files.createDirectories(events.getParent());
        Files.writeString(events, """
                {"type":"answer","id":"q1","choice":"Yes,No","value":"Yes,No","at":"2026-01-01T00:00:00Z"}
                {"type":"answer","id":"q1","choice":"Yes","value":"Yes","at":"2026-01-01T00:00:01Z"}
                """);
        Map<String, String> hit = AnswerRecovery.find(temp, "reg", 0, "q1", List.of("Yes", "No"));
        assertNotNull(hit);
        assertEquals("Yes", hit.get("choice"));
        assertTrue(page("ask-and-wait").contains("clicked choice"));
        assertTrue(page("guide").contains("digit-leading"));
        assertTrue(page("guide").contains("Enter"));
        String runtime = Files.readString(Path.of(
                "src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        assertFalse(runtime.contains("TYPE_SETTLE_MS"));
        assertTrue(runtime.contains("ev.key === 'Enter'"));
        assertTrue(runtime.contains("function clearFieldChrome"));
        assertTrue(runtime.contains("function byDomId"));
    }

    @Test
    void recordingSensesGeneratesSavesAndReplays() throws Exception {
        Capture started = agent("record", temp.toString(), "--start", "--session=reg");
        assertEquals(0, started.code, started.out + started.err);
        Map<String, String> click = new LinkedHashMap<>();
        click.put("type", "click");
        click.put("kind", "click");
        click.put("name", "Submit");
        click.put("text", "Submit");
        click.put("role", "button");
        click.put("value", "");
        click.put("source", "human");
        click.put("frame", "top");
        InteractionLog.accept(temp, click);
        Capture script = agent("record", temp.toString(), "--script");
        assertEquals(0, script.code, script.out + script.err);
        assertTrue(script.out.contains("click the \\\"Submit\\\" Button"), script.out);
        Capture saved = agent("record", temp.toString(), "--save", "--name=ready");
        assertEquals(0, saved.code, saved.out + saved.err);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/routines/ready.json")));
        Path feature = temp.resolve("ready.feature");
        Capture exported = agent(
                "record", temp.toString(), "--export", "--name=ready", "--format=feature", "--out=" + feature
        );
        assertEquals(0, exported.code, exported.out + exported.err);
        String gherkin = Files.readString(feature);
        assertTrue(gherkin.contains("Scenario: ready"), gherkin);
        assertTrue(gherkin.contains("click the \"Submit\" Button"), gherkin);
        Capture replay = agent("record", temp.toString(), "--run", "--name=ready");
        assertEquals(0, replay.code, replay.out + replay.err);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/replay/ready.steps.json")));
    }

    @Test
    void consumerExampleAndGuidanceStayCurrent() throws Exception {
        String feature = Files.readString(Path.of(
                "maven-consumer-project/src/test/resources/features/recorded-collaboration.feature"));
        assertTrue(feature.contains("record --editor"));
        assertTrue(feature.contains("--popout"));
        assertTrue(feature.contains("--popin"));
        assertTrue(feature.contains("| Ava | Green |"));
        String skill = Files.readString(Path.of(".agents/skills/pickleball-functionality-change/SKILL.md"));
        assertTrue(skill.contains("recorded-collaboration.feature"));
        assertTrue(skill.contains("Chrome DevTools"));
        assertTrue(skill.contains("selftest ui --component=feedback"));
        String checklist = Files.readString(Path.of("docs/agent/change-checklist.md"));
        assertTrue(checklist.contains("recorded-collaboration.feature"));
        assertTrue(checklist.contains("observe --request"));
        byte[] guide = Files.readAllBytes(Path.of("docs/consumer-agent-guide.md"));
        String text = new String(guide, StandardCharsets.UTF_8);
        assertTrue(guide.length <= 5120, "first-read guide is " + guide.length + " bytes");
        assertTrue(text.contains("start --url-key"));
        assertTrue(text.contains("chat-turn"));
        assertTrue(text.contains("act --query"));
        assertTrue(text.contains("ui-verify"));
        assertTrue(text.contains("doctor"));
    }

    private static String page(String command) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchCliHelp.answer(
                new String[]{"help", command},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, command);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private static Capture agent(String... args) {
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
