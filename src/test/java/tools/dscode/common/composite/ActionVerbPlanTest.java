package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Argv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.KeyParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real verbs are one command when capabilities lists them.
 * The 2.4 mapping stays when the verb is absent.
 * Before: element drag was click, scroll, click (3). After: one drag.
 * Before: double-click was two clicks. After: one dblclick.
 */
class ActionVerbPlanTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void plansCountTheRoundTrips() {
        Set<String> old = Set.copyOf(VerbPlan.ACTIONS_2_4_0);
        Set<String> now = Set.of("click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag");
        assertEquals(List.of("click"), VerbPlan.submit(old));
        assertEquals(List.of("submit"), VerbPlan.submit(now));
        assertEquals(List.of("click", "click"), VerbPlan.doubleClick(old));
        assertEquals(List.of("dblclick"), VerbPlan.doubleClick(now));
        assertEquals(List.of("click"), VerbPlan.contextClick(old));
        assertEquals(List.of("contextclick"), VerbPlan.contextClick(now));
        assertEquals(List.of("click", "scroll", "click"), VerbPlan.drag(old, true, 0, 0));
        assertEquals(List.of("drag"), VerbPlan.drag(now, true, 0, 0));
        assertEquals(List.of("click", "scroll", "click"), VerbPlan.drag(now, true, 4, 6));
        assertEquals(List.of("click", "scroll"), VerbPlan.drag(now, false, 4, 6));
    }

    @Test
    void argvForwardsSubmitKeyAndDrag() {
        ObjectNode submit = MAPPER.createObjectNode();
        submit.put("css", "#save");
        assertEquals(List.of("submit", "--css", "#save"), Argv.from("submit", submit));

        ObjectNode key = MAPPER.createObjectNode();
        key.put("css", "#name");
        key.put("key", "CONTROL+A");
        List<String> keyArgv = Argv.from("key", key);
        assertEquals("key", keyArgv.get(0));
        assertTrue(keyArgv.contains("--key"), keyArgv.toString());
        assertTrue(keyArgv.contains("CONTROL+A"), keyArgv.toString());

        ObjectNode drag = MAPPER.createObjectNode();
        drag.putObject("source").put("css", "#a");
        drag.putObject("target").put("css", "#b");
        drag.put("session", "sess-1");
        List<String> dragArgv = Argv.from("drag", drag);
        assertEquals("drag", dragArgv.get(0));
        assertTrue(dragArgv.contains("--source"), dragArgv.toString());
        assertTrue(dragArgv.contains("--target"), dragArgv.toString());
        int sourceAt = dragArgv.indexOf("--source");
        assertTrue(dragArgv.get(sourceAt + 1).startsWith("{"), dragArgv.toString());
        assertTrue(dragArgv.get(sourceAt + 1).contains("\"css\":\"#a\""), dragArgv.toString());
    }

    @Test
    void chordsMatchTheKeyVerb() {
        assertEquals(List.of("ENTER"), KeyParser.compositeChords("ENTER"));
        assertEquals(List.of("CONTROL+A"), KeyParser.compositeChords("CONTROL[A]"));
        assertEquals(List.of("CONTROL+A"), KeyParser.compositeChords("CONTROL+A"));
        assertEquals(List.of("CONTROL+A"), KeyParser.compositeChords("control[a]"));
        assertEquals(List.of("CONTROL+A"), KeyParser.compositeChords("CTRL+A"));
        assertEquals(List.of("SHIFT+TAB"), KeyParser.compositeChords("SHIFT+TAB"));
        assertEquals(List.of("ENTER", "TAB"), KeyParser.compositeChords("ENTER TAB"));
        assertEquals(List.of("CONTROL+A", "CONTROL+B"), KeyParser.compositeChords("CONTROL[A B]"));
        assertEquals(List.of("CONTROL+SHIFT+A"), KeyParser.compositeChords("CONTROL+SHIFT[A]"));
        assertEquals(List.of("BACKSPACE"), KeyParser.compositeChords("BACK_SPACE"));
        assertEquals(List.of("ARROWDOWN"), KeyParser.compositeChords("Down"));
        assertNull(KeyParser.compositeChords("A+B"));
        assertNull(KeyParser.compositeChords("CONTROL[A+B]"));
        assertNull(KeyParser.compositeChords("NOT_A_KEY"));
    }

    @Test
    void dryRunUsesOneCommandWhenTheVerbIsListed() {
        DomCompositeAdapter adapter = DomCompositeAdapter.get();
        CompositeWebElement source = element("save", "Save");
        CompositeWebElement target = element("drop", "Drop");
        adapter.dryRunForTests(Set.copyOf(VerbPlan.ACTIONS_2_4_0));
        ElementOperations.doubleClick(null, source);
        ElementOperations.contextClick(null, source);
        source.submit();
        ElementOperations.drag(null, source, target, 0, 0);
        ElementOperations.drag(null, source, target, 4, 6);
        assertFalse(ElementOperations.pressKeys(null, source, "ENTER"));
        assertEquals(List.of(
                "click", "click",
                "click",
                "click",
                "click", "scroll", "click",
                "click", "scroll", "click"
        ), adapter.commandsForTests());

        adapter.dryRunForTests(Set.of("click", "type", "scroll", "submit", "dblclick", "contextclick", "drag", "key"));
        ElementOperations.doubleClick(null, source);
        ElementOperations.contextClick(null, source);
        source.submit();
        ElementOperations.drag(null, source, target, 0, 0);
        ElementOperations.drag(null, source, target, 4, 6);
        assertTrue(ElementOperations.pressKeys(null, source, "ENTER"));
        assertTrue(ElementOperations.pressKeys(null, source, "ENTER TAB"));
        assertFalse(ElementOperations.pressKeys(null, source, "A+B"));
        assertEquals(List.of(
                "dblclick",
                "contextclick",
                "submit",
                "drag",
                "click", "scroll", "click",
                "key",
                "key", "key"
        ), adapter.commandsForTests());
    }

    @Test
    void binaryListsTheVerbOnce() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log, true);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        WebElement found = ElementOperations.findElements(null, org.openqa.selenium.By.cssSelector("#save")).get(0);
        CompositeWebElement element = (CompositeWebElement) found;
        CompositeWebElement drop = element("drop", "Drop");
        DomCompositeAdapter.get().resetCommandsForTests();
        element.submit();
        ElementOperations.doubleClick(null, element);
        ElementOperations.contextClick(null, element);
        ElementOperations.drag(null, element, element, 0, 0);
        ElementOperations.drag(null, element, drop, 3, 1);
        assertTrue(ElementOperations.pressKeys(null, element, "CONTROL+A"));
        assertEquals(List.of("submit", "dblclick", "contextclick", "drag", "click", "scroll", "click", "key"),
                DomCompositeAdapter.get().commandsForTests());
        String calls = Files.readString(log);
        assertTrue(calls.contains("\nsubmit ") || calls.startsWith("submit "), calls);
        assertTrue(calls.contains("dblclick "), calls);
        assertTrue(calls.contains("contextclick "), calls);
        assertTrue(calls.contains("drag "), calls);
        assertTrue(calls.contains("--source"), calls);
        assertTrue(calls.contains("--key"), calls);
        assertTrue(calls.contains("CONTROL+A"), calls);
        long dragLines = calls.lines().filter(line -> line.startsWith("drag ")).count();
        assertEquals(1, dragLines, calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("verb submit real"), trace);
        assertTrue(trace.contains("verb drag real"), trace);
        assertTrue(trace.contains("verb drag fallback"), trace);
        assertTrue(trace.contains("verb key real"), trace);
    }

    @Test
    void binaryWithoutTheVerbKeepsTheOldMapping() throws Exception {
        Path log = temp.resolve("old.log");
        Path binary = stub(temp.resolve("old").resolve("composite"), log, false);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        WebElement found = ElementOperations.findElements(null, org.openqa.selenium.By.cssSelector("#save")).get(0);
        CompositeWebElement element = (CompositeWebElement) found;
        DomCompositeAdapter.get().resetCommandsForTests();
        element.submit();
        ElementOperations.doubleClick(null, element);
        ElementOperations.drag(null, element, element, 0, 0);
        assertFalse(ElementOperations.pressKeys(null, element, "ENTER"));
        assertEquals(List.of("click", "click", "click", "click", "scroll", "click"),
                DomCompositeAdapter.get().commandsForTests());
        String calls = Files.readString(log);
        assertFalse(calls.contains("submit "), calls);
        assertFalse(calls.contains("dblclick "), calls);
        assertFalse(calls.contains("drag "), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("verb submit fallback"), trace);
        assertTrue(trace.contains("verb dblclick fallback"), trace);
        assertTrue(trace.contains("verb drag fallback"), trace);
    }

    private static CompositeWebElement element(String id, String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("text", text);
        node.put("role", "button");
        node.putObject("attributes").put("id", id);
        ObjectNode bounds = node.putObject("bounds");
        bounds.put("x", 1);
        bounds.put("y", 2);
        bounds.put("width", 10);
        bounds.put("height", 8);
        node.putArray("states").add("enabled");
        return new CompositeWebElement(new CompositeSelector(null, "#" + id, null, null, null, id), node, 0);
    }

    private static Path stub(Path path, Path log, boolean verbs) throws Exception {
        String actions = verbs
                ? "[\"click\", \"type\", \"scroll\", \"submit\", \"key\", \"dblclick\", \"contextclick\", \"drag\"]"
                : "[\"click\", \"type\", \"scroll\"]";
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        body = {"version": "2.5.0"}
                    elif "capabilities" in argv:
                        body = {"ok": True, "desktop": ["list", "query", "close"], "actions": %s}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif "query" in argv or "click" in argv or "type" in argv or "scroll" in argv or "submit" in argv or "key" in argv or "dblclick" in argv or "contextclick" in argv or "drag" in argv:
                        body = {"ok": True, "composite": [{
                            "id": "main:1",
                            "text": "Save",
                            "role": "button",
                            "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                            "attributes": {"id": "save"},
                            "states": ["enabled"],
                            "source": "Dom"
                        }]}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()), actions);
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
