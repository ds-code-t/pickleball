package tools.dscode.common.recording;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.control.protocol.AgentEvents;
import tools.dscode.launcher.WorkbenchAgentCommands;

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

class CollaborativeRecordingTest {
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
    void covers_command_record() throws Exception {
        Capture started = run("record", temp.toString(), "--start", "--session=rec1");
        assertEquals(0, started.code, started.out + started.err);
        assertTrue(started.out.contains("\"status\":\"recording\""), started.out);
        assertTrue(started.out.contains("rec1"), started.out);

        InteractionLog.accept(temp, row("input", "Account Number", "textbox", "1001", "human"));
        InteractionLog.accept(temp, row("input", "Account Number", "textbox", "1001-extra", "human"));
        InteractionLog.accept(temp, row("select", "Account Type", "combobox", "Premium|Plus", "human"));
        InteractionLog.accept(temp, row("click", "Submit", "button", "", "human"));
        Map<String, String> empty = row("input", "Note", "textbox", "", "human");
        InteractionLog.accept(temp, empty);
        Map<String, String> quoted = row("input", "Motto", "textbox", "say \"hi\"", "human");
        InteractionLog.accept(temp, quoted);
        Map<String, String> secret = row("input", "Password", "textbox", "s3cret", "human");
        secret.put("sensitive", "true");
        InteractionLog.accept(temp, secret);
        Map<String, String> desktop = row("click", "Save", "button", "", "agent");
        desktop.put("surface", "desktop");
        desktop.put("window", "Calculator");
        desktop.put("frame", "desktop");
        InteractionLog.accept(temp, desktop);

        List<Map<String, String>> stream = InteractionLog.streamLines(temp);
        assertTrue(stream.size() >= 7, stream.toString());
        Map<String, String> first = stream.get(0);
        assertEquals("interaction", first.get("event"));
        assertEquals("input", first.get("type"));
        assertEquals("human", first.get("source"));
        assertEquals("Account Number", first.get("name"));
        assertEquals("textbox", first.get("role"));
        assertEquals("1001", first.get("value"));
        assertEquals("rec1", first.get("sessionId"));
        assertTrue(first.containsKey("at") && first.containsKey("frame") && first.containsKey("target"), first.toString());
        assertEquals("filled", stream.stream().filter(row -> "Password".equals(row.get("name"))).findFirst().orElseThrow().get("value"));

        tools.dscode.common.agentfast.PushChannel.Hit hit = tools.dscode.common.agentfast.PushChannel.follow(
                temp, "", "interaction", 0, false, 0);
        assertEquals(0, hit.exit(), String.valueOf(hit));
        assertEquals("interaction", hit.event().get("event"));

        Capture script = run("record", temp.toString(), "--script");
        assertEquals(0, script.code, script.out + script.err);
        assertTrue(script.out.contains("enter \\\"<accountNumber>\\\" in the \\\"Account Number\\\" Textbox"), script.out);
        assertTrue(script.out.contains("select \\\"<accountType>\\\" in the \\\"Account Type\\\" Dropdown"), script.out);
        assertTrue(script.out.contains("select \\\"<accountType2>\\\" in the \\\"Account Type\\\" Dropdown"), script.out);
        assertTrue(script.out.contains("click the \\\"Submit\\\" Button"), script.out);
        assertTrue(script.out.contains("enter \\\"<note>\\\" in the \\\"Note\\\" Textbox"), script.out);
        assertTrue(script.out.contains("click the \\\"Save\\\" Button"), script.out);
        assertTrue(script.out.contains("1001-extra"), script.out);
        assertFalse(script.out.contains("s3cret"), script.out);

        Capture renamed = run("record", temp.toString(), "--rename", "--from=accountNumber", "--to=acct");
        assertEquals(0, renamed.code, renamed.out + renamed.err);
        assertTrue(renamed.out.contains("<acct>"), renamed.out);
        assertFalse(renamed.out.contains("<accountNumber>"), renamed.out);

        Capture literal = run("record", temp.toString(), "--literal=note");
        assertEquals(0, literal.code, literal.out + literal.err);
        assertTrue(literal.out.contains("enter \\\"\\\" in the \\\"Note\\\" Textbox")
                || literal.out.contains("enter '' in the \\\"Note\\\" Textbox"), literal.out);

        Capture saved = run("record", temp.toString(), "--save", "--name=checkout");
        assertEquals(0, saved.code, saved.out + saved.err);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/routines/checkout.json")));

        Capture stopped = run("record", temp.toString(), "--stop");
        assertEquals(0, stopped.code, stopped.out + stopped.err);
        assertTrue(stopped.out.contains("recording stopped"), stopped.out);

        Capture listed = run("record", temp.toString(), "--list");
        assertEquals(0, listed.code, listed.out + listed.err);
        assertTrue(listed.out.contains("checkout"), listed.out);

        Capture loaded = run("record", temp.toString(), "--load", "--name=checkout");
        assertEquals(0, loaded.code, loaded.out + loaded.err);
        assertTrue(loaded.out.contains("Account Number"), loaded.out);

        Path feature = temp.resolve("checkout.feature");
        Capture exported = run("record", temp.toString(), "--export", "--name=checkout", "--format=feature", "--out=" + feature);
        assertEquals(0, exported.code, exported.out + exported.err);
        String gherkin = Files.readString(feature);
        assertTrue(gherkin.contains("Scenario Outline: checkout"), gherkin);
        assertTrue(gherkin.contains("Then , "), gherkin);
        assertTrue(gherkin.contains("Examples:"), gherkin);
        assertTrue(gherkin.contains("<acct>"), gherkin);

        Capture replay = run("record", temp.toString(), "--run", "--name=checkout", "--param=acct=42", "--param=accountType=Basic");
        assertEquals(0, replay.code, replay.out + replay.err);
        assertTrue(replay.out.contains("enter \\\"42\\\" in the \\\"Account Number\\\" Textbox"), replay.out);
        assertTrue(replay.out.contains("select \\\"Basic\\\" in the \\\"Account Type\\\" Dropdown"), replay.out);
        assertTrue(replay.out.contains("parameters applied"), replay.out);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/recordings/replay/checkout.steps.json")));

        parse(" enter \"42\" in the \"Account Number\" Textbox.");
        parse(" select \"Basic\" in the \"Account Type\" Dropdown.");
        parse(" click the \"Submit\" Button.");
        parse(" click the \"Save\" Button.");
        parse(" ensure the \"Receive Updates\" Checkbox is checked.");

        Capture injected = run("record", temp.toString(), "--start", "--session=rec2");
        assertEquals(0, injected.code, injected.out + injected.err);
        Capture action = run("record", temp.toString(), "--inject", "--kind=click", "--query=Approve", "--role=button");
        assertEquals(0, action.code, action.out + action.err);
        assertTrue(action.out.contains("action recorded"), action.out);
        Capture mixed = run("record", temp.toString(), "--script");
        assertEquals(0, mixed.code, mixed.out + mixed.err);
        assertTrue(mixed.out.contains("click the \\\"Approve\\\" Button"), mixed.out);

        Capture paused = run("record", temp.toString(), "--pause");
        assertEquals(0, paused.code, paused.out + paused.err);
        InteractionLog.accept(temp, row("click", "Hidden", "button", "", "human"));
        Capture whilePaused = run("record", temp.toString(), "--script");
        assertEquals(0, whilePaused.code, whilePaused.out + whilePaused.err);
        assertFalse(whilePaused.out.contains("Hidden"), whilePaused.out);

        String events = Files.readString(temp.resolve(".pickleball/agent-events.jsonl"));
        assertTrue(events.contains("\"event\":\"interaction\""), events);
        assertTrue(events.contains("Calculator"), events);
        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        assertTrue(js.contains("sense('click'"), js);
        assertTrue(js.contains("sense('select'"), js);
        assertTrue(js.contains("sense('key'"), js);
        assertTrue(js.contains("sense('hover'"), js);
        assertTrue(js.contains("sense('focus'"), js);
        assertTrue(js.contains("sense('drag'"), js);
        assertTrue(js.contains("data-pkb-record"), js);
        assertTrue(js.contains("setFeedback(node, 'sending')"), js);
        assertTrue(js.contains("setFeedback(node, 'delivered')"), js);
        long interactions = AgentEvents.lines(temp).stream().filter(row -> "interaction".equals(row.get("event"))).count();
        assertEquals(InteractionLog.streamLines(temp).size(), interactions);
    }

    private static void parse(String phrase) {
        QuoteParser quotes = new QuoteParser(phrase);
        String masked = quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
        MatchNode parsed = DefinitionContext.getNodeDictionary().parse(masked);
        assertNotNull(parsed, phrase);
        assertNotNull(parsed.getChild("phrase"), phrase + " -> " + parsed);
    }

    private static Map<String, String> row(String kind, String name, String role, String value, String source) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("type", kind);
        row.put("kind", kind);
        row.put("name", name);
        row.put("text", name);
        row.put("role", role);
        row.put("value", value);
        row.put("source", source);
        row.put("id", name.toLowerCase().replace(' ', '-'));
        row.put("frame", "top");
        row.put("bounds", "1,2,3,4");
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
