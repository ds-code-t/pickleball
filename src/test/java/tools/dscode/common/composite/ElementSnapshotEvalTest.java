package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Argv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.domoperations.elementstates.EnabledDisabledConditions;
import tools.dscode.common.seleniumextensions.CompositeSnapshotProducer;
import tools.dscode.common.seleniumextensions.ElementSnapshot;
import tools.dscode.common.seleniumextensions.ElementSnapshotScript;
import tools.dscode.common.seleniumextensions.SeleniumSnapshotProducer;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot after a composite hit is one eval when capabilities lists it,
 * and the old WebDriver script when it does not.
 * Before: 1 WebDriver script that re-finds the node. After: 1 eval, 0 scripts.
 */
class ElementSnapshotEvalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private HtmlUnitDriver driver;

    @AfterEach
    void clean() {
        if (driver != null) driver.quit();
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
        ElementSnapshotScript.resetScriptCallsForTests();
    }

    @BeforeEach
    void fresh() {
        ElementSnapshotScript.resetScriptCallsForTests();
        ElementOperations.resetStateMeasurementsForTests();
    }

    @Test
    void argvForwardsSourceAndNodeId() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("css", "#host");
        body.put("source", "function(){ return 1; }");
        body.put("node_id", "main:12");
        List<String> argv = Argv.from("eval", body);
        assertEquals("eval", argv.get(0));
        assertTrue(argv.contains("--source"), argv.toString());
        assertTrue(argv.contains("function(){ return 1; }"), argv.toString());
        assertTrue(argv.contains("--node-id"), argv.toString());
        assertTrue(argv.contains("main:12"), argv.toString());
        assertTrue(ElementSnapshotScript.EVAL_SOURCE.contains("pickleballSnapshot"));
        assertTrue(ElementSnapshotScript.EVAL_SOURCE.contains("pickleballElementState"));
        assertTrue(ElementSnapshotScript.EVAL_SOURCE.startsWith("function()"));
    }

    @Test
    void evalListedIsOneCallAndZeroScripts() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log, true);
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        WebElement found = ElementOperations.findElements(null, By.cssSelector("#host")).get(0);
        CompositeWebElement element = (CompositeWebElement) found;
        DomCompositeAdapter.get().resetCommandsForTests();
        ElementSnapshotScript.resetScriptCallsForTests();
        ElementSnapshot shot = CompositeSnapshotProducer.INSTANCE.snapshot(null, element);
        assertEquals(List.of("eval"), DomCompositeAdapter.get().commandsForTests());
        assertEquals(0, ElementSnapshotScript.scriptCallsForTests());
        assertEquals("div", shot.tag());
        assertEquals("Hello", shot.text());
        assertEquals("", shot.value());
        assertEquals("sv", shot.selectedValue());
        assertEquals("host", shot.attribute("id"));
        assertEquals(2, shot.siblingIndex());
        assertEquals(1, shot.sameTagIndex());
        assertEquals("#host", shot.stableSelector());
        assertEquals("main:12", shot.nodeId());
        assertNotNull(shot.elementState());
        assertTrue(shot.elementState().disabled());
        assertFalse(shot.elementState().blank());
        ElementOperations.resetFindCallsForTests();
        assertTrue(EnabledDisabledConditions.isDisabled(element));
        assertEquals(0, ElementOperations.findCallsForTests());
        assertEquals(0, ElementOperations.stateFallbacksForTests());
        String calls = Files.readString(log);
        assertTrue(calls.contains("--node-id"), calls);
        assertTrue(calls.contains("main:12"), calls);
        assertTrue(calls.contains("pickleballSnapshot"), calls);
        assertTrue(calls.contains("pickleballElementState"), calls);
        long evalLines = calls.lines().filter(line -> line.startsWith("eval ")).count();
        assertEquals(1, evalLines, calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("snapshot eval"), trace);
    }

    @Test
    void evalAbsentKeepsTheWebDriverScript() throws Exception {
        Path logDir = temp.resolve("state-logs");
        Files.createDirectories(logDir);
        CompositeRuntime.use(
                temp.resolve("pin"),
                Map.of(
                        "pkb_composite", "off",
                        "pkb_composite_log", "adapter",
                        "pkb_composite_log_dir", logDir.toString()
                ),
                new ClassLoader(null) {},
                temp.resolve("home")
        );
        driver = new HtmlUnitDriver(true);
        URL page = ElementSnapshotEvalTest.class.getResource("/element-state/states.html");
        driver.get(Objects.requireNonNull(page, "states").toString());
        WebElement live = driver.findElement(By.id("host"));
        ElementSnapshot selenium = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, live);
        ElementSnapshotScript.resetScriptCallsForTests();
        DomCompositeAdapter.get().resetCommandsForTests();
        CompositeWebElement hit = CompositeElements.of("host", "div", "STALE", 1, 2, 3, 4);
        ElementSnapshot composite = CompositeSnapshotProducer.INSTANCE.snapshot(driver, hit);
        assertEquals(1, ElementSnapshotScript.scriptCallsForTests());
        assertEquals(List.of(), DomCompositeAdapter.get().commandsForTests());
        assertEquals(selenium.tag(), composite.tag());
        assertEquals(selenium.text(), composite.text());
        assertEquals(selenium.elementState(), composite.elementState());
        assertFalse(DomCompositeAdapter.get().actionListed("eval"));
    }

    private static Path stub(Path path, Path log, boolean eval) throws Exception {
        String actions = eval
                ? "[\"click\", \"type\", \"scroll\", \"submit\", \"key\", \"dblclick\", \"contextclick\", \"drag\", \"eval\"]"
                : "[\"click\", \"type\", \"scroll\"]";
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                value = {
                    "tag": "div",
                    "text": "Hello",
                    "value": "",
                    "selectedValue": "sv",
                    "attributes": {"id": "host"},
                    "siblingIndex": 2,
                    "sameTagIndex": 1,
                    "stableSelector": "#host",
                    "elementState": {
                        "disabled": True,
                        "blank": False,
                        "required": False,
                        "expanded": True,
                        "attributeOn": False,
                        "selectable": False,
                        "domSelected": False
                    }
                }
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        body = {"version": "2.6.0"}
                    elif "capabilities" in argv:
                        body = {"ok": True, "desktop": ["list", "query", "close"], "actions": %s}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif argv and argv[0] == "eval":
                        body = {"ok": True, "kind": "eval", "backend_node_id": 12, "value": value}
                    elif "query" in argv or "click" in argv:
                        body = {"ok": True, "composite": [{
                            "id": "main:12",
                            "text": "Save",
                            "role": "button",
                            "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                            "attributes": {"id": "host"},
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
