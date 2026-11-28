package tools.dscode.common.recording;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.StepCapture;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rename scope, literal checks, context comments, capture-steps, and selftest record. */
class RefinementTest {
    @TempDir
    Path temp;

    @BeforeEach
    void resetPushChannel() throws Exception {
        var reset = PushChannel.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @AfterEach
    void clearPushChannel() throws Exception {
        resetPushChannel();
    }

    @Test
    void renameIsExactLiteralFailsAndContextIsAComment() throws Exception {
        Capture started = run("record", temp.toString(), "--start", "--session=ref");
        assertEquals(0, started.code, started.out + started.err);
        InteractionLog.accept(temp, row("acct-a", "1"));
        InteractionLog.accept(temp, row("acct-b", "2"));
        Capture renamed = run("record", temp.toString(), "--rename", "--from=accountNumber", "--to=acct");
        assertEquals(0, renamed.code, renamed.out + renamed.err);
        assertTrue(renamed.out.contains("<acct>"), renamed.out);
        assertTrue(renamed.out.contains("<accountNumber2>"), renamed.out);
        assertFalse(renamed.out.contains("<acct2>"), renamed.out);

        Capture literal = run("record", temp.toString(), "--literal=note");
        assertEquals(2, literal.code, literal.out + literal.err);
        assertTrue((literal.out + literal.err).contains("unknown parameter note"), literal.out + literal.err);

        Capture known = run("record", temp.toString(), "--literal=accountNumber2");
        assertEquals(0, known.code, known.out + known.err);
        assertTrue(known.out.contains("enter \\\"2\\\" in the \\\"Account Number\\\" Textbox")
                || known.out.contains("enter \"2\" in the \\\"Account Number\\\" Textbox"), known.out);

        Capture context = run("record", temp.toString(), "--context", "--text=on the billing page");
        assertEquals(0, context.code, context.out + context.err);
        assertTrue(context.out.contains("# on the billing page"), context.out);
        assertFalse(context.out.contains("on the billing page, click"), context.out);
        assertFalse(context.out.contains("on the billing page, enter"), context.out);

        Capture replay = run("record", temp.toString(), "--run");
        assertEquals(0, replay.code, replay.out + replay.err);
        assertTrue(replay.out.contains("parameters applied"), replay.out);
        assertTrue(replay.out.contains("execute-steps --file="), replay.out);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/replay/recorded.steps.json")));
    }

    @Test
    void captureStepsDropsQuestionCardsAndPlacementIds() {
        Map<String, String> card = new LinkedHashMap<>();
        card.put("type", "answer");
        card.put("choice", "Yes");
        card.put("value", "Yes");
        card.put("id", "ask-q1");
        assertNull(StepCapture.stepOf(card));
        Map<String, String> placed = new LinkedHashMap<>();
        placed.put("type", "click");
        placed.put("id", "pkb-pause");
        placed.put("name", "Pause");
        Map<String, Object> click = StepCapture.stepOf(placed);
        assertTrue(click != null, "a labeled page control still needs a query");
        @SuppressWarnings("unchecked")
        Map<String, String> args = (Map<String, String>) click.get("args");
        assertFalse(args.containsKey("ref"), args.toString());
        assertEquals("Pause", args.get("query"));
        Map<String, String> ada = new LinkedHashMap<>();
        ada.put("type", "answer");
        ada.put("ref", "s1.h2");
        ada.put("value", "Ada");
        Map<String, Object> typed = StepCapture.stepOf(ada);
        @SuppressWarnings("unchecked")
        Map<String, String> typedArgs = (Map<String, String>) typed.get("args");
        assertEquals("s1.h2", typedArgs.get("ref"));
        assertEquals("Ada", typedArgs.get("value"));
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void selftestRecordClicksInsideTheFixtureIframe() throws Exception {
        Capture capture = run("selftest", temp.toString(), "record");
        String text = capture.out + capture.err;
        if (text.contains("no Chrome")) return;
        assertEquals(0, capture.code, text);
        assertTrue(text.contains("selftest record passed"), text);
        assertTrue(text.contains("billing"), text);
        assertTrue(text.contains("Pay"), text);
        assertFalse(text.contains("Pause"), text);
    }

    /** PKB-HCR bug 7: selftest record left the real project's recorder state as session selftest-record. */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void selftestRecordLeavesTheProjectsRecorderStateAlone() throws Exception {
        RecordingSession.start(temp, "keep-me");
        RecordingSession.stop(temp);
        Path state = RecordingSession.file(temp);
        String before = java.nio.file.Files.readString(state);
        Capture capture = run("selftest", temp.toString(), "record");
        String text = capture.out + capture.err;
        if (text.contains("no Chrome")) return;
        assertEquals(0, capture.code, text);
        assertEquals(before, java.nio.file.Files.readString(state), "recorder state of the project changed");
        assertFalse(java.nio.file.Files.readString(state).contains("selftest-record"));
        assertFalse(RecordingSession.recording(temp));
        try (java.util.stream.Stream<Path> list = java.nio.file.Files.list(temp)) {
            assertEquals(0, list.filter(p -> p.getFileName().toString().startsWith("selftest-record-")).count(),
                    "profile folder left in the project");
        }
        assertEquals(0, InteractionLog.streamLines(temp).size(), "selftest rows landed in the project stream");
    }

    private static Map<String, String> row(String id, String value) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("type", "input");
        row.put("kind", "input");
        row.put("name", "Account Number");
        row.put("text", "Account Number");
        row.put("role", "textbox");
        row.put("value", value);
        row.put("source", "human");
        row.put("id", id);
        row.put("frame", "top");
        return row;
    }

    private static Capture run(String... args) {
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
