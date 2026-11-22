package tools.dscode.common.recording;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-page step editor, write-back, and pop-out placement. */
class StepEditorTest {
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
    void shellListsStepsAndThePageCanCollapseResizeAndDrag() throws Exception {
        Capture started = run("record", temp.toString(), "--start", "--session=edit1");
        assertEquals(0, started.code, started.out + started.err);
        Capture empty = run("record", temp.toString(), "--editor");
        assertEquals(0, empty.code, empty.out + empty.err);
        assertTrue(empty.out.contains("\"editor\":[]") || empty.out.contains("\"count\":0"), empty.out);

        InteractionLog.accept(temp, row("click", "Open", "button", "", "human"));
        InteractionLog.accept(temp, row("click", "Save", "button", "", "agent"));
        Map<String, String> desktop = row("click", "Save", "button", "", "agent");
        desktop.put("surface", "desktop");
        desktop.put("window", "Notes");
        desktop.put("frame", "desktop");
        InteractionLog.accept(temp, desktop);

        Capture editor = run("record", temp.toString(), "--editor");
        assertEquals(0, editor.code, editor.out + editor.err);
        assertTrue(editor.out.contains("\"id\":\"s1\""), editor.out);
        assertTrue(editor.out.contains("click the \\\"Open\\\" Button"), editor.out);
        assertTrue(editor.out.contains("click the \\\"Save\\\" Button"), editor.out);
        assertTrue(editor.out.contains("\"sentence\":\"editor ready\""), editor.out);

        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        String css = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/panel.css"));
        assertTrue(js.contains("data-pkb-editor"));
        assertTrue(js.contains("function editorOpen"));
        assertTrue(js.contains("function editorPlace"));
        assertTrue(js.contains("data-action', 'minimize'"));
        assertTrue(js.contains("pkb-grip"));
        assertTrue(js.contains("function dragger"));
        assertTrue(js.contains("Collapse step editor") || js.contains("aria-label', 'Collapse step editor'"));
        assertTrue(css.contains(".pkb-editor-row"));
        assertTrue(css.contains("prefers-reduced-motion"));
        assertTrue(js.contains("VERSION = '3.0.0-editor'"));
    }

    @Test
    void editsReorderNestCreateAndDeleteThenSurviveSave() throws Exception {
        assertEquals(0, run("record", temp.toString(), "--start", "--session=edit2").code);
        InteractionLog.accept(temp, row("click", "Open", "button", "", "human"));
        InteractionLog.accept(temp, row("click", "Save", "button", "", "human"));
        InteractionLog.accept(temp, row("click", "Cancel", "button", "", "human"));

        Capture edited = run("record", temp.toString(), "--edit", "--index=0", "--text=click the \"Begin\" Button");
        assertEquals(0, edited.code, edited.out + edited.err);
        assertTrue(edited.out.contains("click the \\\"Begin\\\" Button"), edited.out);
        assertTrue(edited.out.contains("\"sentence\":\"editor updated\""), edited.out);

        Capture moved = run("record", temp.toString(), "--move", "--from=2", "--to=0");
        assertEquals(0, moved.code, moved.out + moved.err);
        int cancel = moved.out.indexOf("Cancel");
        int begin = moved.out.indexOf("Begin");
        assertTrue(cancel >= 0 && begin > cancel, moved.out);

        Capture nested = run("record", temp.toString(), "--nest", "--index=1");
        assertEquals(0, nested.code, nested.out + nested.err);
        assertTrue(nested.out.contains(": And , click the \\\"Begin\\\" Button")
                || nested.out.contains(": And , click the \\\"Save\\\" Button"), nested.out);
        assertTrue(nested.out.contains("\"depth\":1"), nested.out);

        Capture added = run("record", temp.toString(), "--add", "--text=press \"Escape\"", "--index=1");
        assertEquals(0, added.code, added.out + added.err);
        assertTrue(added.out.contains("press \\\"Escape\\\""), added.out);
        assertTrue(added.out.contains("\"id\":\"m1\""), added.out);

        Capture deleted = run("record", temp.toString(), "--delete", "--index=0");
        assertEquals(0, deleted.code, deleted.out + deleted.err);
        assertFalse(deleted.out.contains("Cancel"), deleted.out);

        InteractionLog.accept(temp, row("click", "Again", "button", "", "human"));
        Capture appended = run("record", temp.toString(), "--script");
        assertEquals(0, appended.code, appended.out + appended.err);
        assertTrue(appended.out.contains("Again"), appended.out);
        assertTrue(appended.out.contains("Begin"), appended.out);

        Capture saved = run("record", temp.toString(), "--save", "--name=edited");
        assertEquals(0, saved.code, saved.out + saved.err);
        String stored = Files.readString(temp.resolve(".pickleball/recordings/routines/edited.json"));
        assertTrue(stored.contains("Begin"), stored);
        assertTrue(stored.contains("depths"), stored);

        Capture loaded = run("record", temp.toString(), "--load", "--name=edited");
        assertEquals(0, loaded.code, loaded.out + loaded.err);
        assertTrue(loaded.out.contains("Begin"), loaded.out);
        assertTrue(loaded.out.contains("\"depth\":1") || loaded.out.contains(": And"), loaded.out);

        Path feature = temp.resolve("edited.feature");
        Capture exported = run("record", temp.toString(), "--export", "--name=edited", "--format=feature", "--out=" + feature);
        assertEquals(0, exported.code, exported.out + exported.err);
        String gherkin = Files.readString(feature);
        assertTrue(gherkin.contains(": And , "), gherkin);

        Capture replay = run("record", temp.toString(), "--run", "--name=edited");
        assertEquals(0, replay.code, replay.out + replay.err);
        assertTrue(replay.out.contains("Begin"), replay.out);

        Map<String, String> pageEdit = new LinkedHashMap<>();
        pageEdit.put("type", "record");
        pageEdit.put("event", "record");
        pageEdit.put("action", "edit");
        pageEdit.put("index", "0");
        pageEdit.put("text", "click the \"From Page\" Button");
        pageEdit.put("source", "human");
        assertTrue(PushChannel.accept(temp, pageEdit));
        Capture absorbed = run("record", temp.toString(), "--script");
        assertEquals(0, absorbed.code, absorbed.out + absorbed.err);
        assertTrue(absorbed.out.contains("From Page"), absorbed.out);

        Capture missing = run("record", temp.toString(), "--edit", "--index=40", "--text=click the \"Nope\" Button");
        assertEquals(2, missing.code, missing.out + missing.err);
        assertTrue(missing.out.contains("out of range") || missing.err.contains("out of range"), missing.out + missing.err);
    }

    @Test
    void popOutAndPopInEditorAndChat() throws Exception {
        assertEquals(0, run("record", temp.toString(), "--start").code);
        Capture editorOut = run("record", temp.toString(), "--popout", "--panel=editor");
        assertEquals(0, editorOut.code, editorOut.out + editorOut.err);
        assertTrue(editorOut.out.contains("\"where\":\"window\""), editorOut.out);
        assertTrue(editorOut.out.contains("\"editor\":\"window\""), editorOut.out);

        Capture chatOut = run("record", temp.toString(), "--popout", "--panel=chat");
        assertEquals(0, chatOut.code, chatOut.out + chatOut.err);
        assertTrue(chatOut.out.contains("\"chat\":\"window\""), chatOut.out);

        Capture editorIn = run("record", temp.toString(), "--popin", "--panel=editor");
        assertEquals(0, editorIn.code, editorIn.out + editorIn.err);
        assertTrue(editorIn.out.contains("\"editor\":\"page\""), editorIn.out);
        assertTrue(editorIn.out.contains("\"chat\":\"window\""), editorIn.out);

        Capture chatIn = run("record", temp.toString(), "--popin", "--panel=chat");
        assertEquals(0, chatIn.code, chatIn.out + chatIn.err);
        assertTrue(chatIn.out.contains("\"chat\":\"page\""), chatIn.out);

        Capture bad = run("record", temp.toString(), "--popout", "--panel=nope");
        assertEquals(2, bad.code, bad.out + bad.err);

        Map<String, String> pagePop = new LinkedHashMap<>();
        pagePop.put("type", "record");
        pagePop.put("event", "record");
        pagePop.put("action", "popout");
        pagePop.put("panel", "editor");
        pagePop.put("source", "human");
        assertTrue(PushChannel.accept(temp, pagePop));
        Capture absorbed = run("record", temp.toString(), "--status");
        assertEquals(0, absorbed.code, absorbed.out + absorbed.err);
        // status does not have to echo panels; the next editor read does
        Capture editor = run("record", temp.toString(), "--editor");
        assertTrue(editor.out.contains("\"editor\":\"window\""), editor.out);

        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        assertTrue(js.contains("function popOut"));
        assertTrue(js.contains("function popIn"));
        assertTrue(js.contains("pkb-step-editor"));
        assertTrue(js.contains("pkb-chat-panel"));
        assertTrue(js.contains("data-pkb-pop"));
        assertTrue(js.contains("function reattachPops"));
        assertTrue(js.contains("Pop out chat"));
        assertTrue(js.contains("Pop out step editor"));
        assertTrue(js.contains("data-pkb-feedback"));
        assertTrue(js.contains("chatMirror"));
        assertTrue(js.contains("function readIds"));
        assertTrue(js.contains("function reapplyChat"));
        assertTrue(js.contains("function recountUnread"));
        int pop = js.indexOf("function popIn");
        assertTrue(pop > 0 && js.substring(pop, pop + 700).contains("reapplyChat"));
        assertTrue(js.contains("row.state !== 'read'"));
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
