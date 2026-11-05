package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Argv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.domoperations.LeanWaits;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite waits, nesting, and liveness drop extra round trips.
 * A visible wait is 1 query (before: a displayed check every 150 ms).
 * Nesting two or more anchored hits is 2 queries (before: one ancestor query per hit).
 * A live refresh is 2 evals and 0 finds (before: 1 find plus 1 snapshot script).
 */
class LeanWaitNestLiveTest {
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
        ElementOperations.resetFindCallsForTests();
    }

    @Test
    void queryForwardsPollAndClickDoesNot() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("css", "#host");
        body.put("wait_for", "visible");
        body.put("poll_ms", 150);
        body.put("stability_ms", 0);
        body.put("timeout_ms", 500);
        List<String> query = Argv.from("query", body);
        assertTrue(query.contains("--poll-ms"), query.toString());
        assertTrue(query.contains("150"), query.toString());
        assertTrue(query.contains("--wait-for"), query.toString());
        assertTrue(query.contains("visible"), query.toString());
        assertTrue(query.contains("--stability-ms"), query.toString());
        assertTrue(query.contains("0"), query.toString());
        List<String> click = Argv.from("click", body);
        assertFalse(click.contains("--poll-ms"), click.toString());
    }

    @Test
    void visibleWaitIsOneQueryAt150ms() throws Exception {
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, "ok", true));
        CompositeWebElement element = CompositeElements.of("host", "div", "Save", 1, 2, 3, 4);
        DomCompositeAdapter.get().resetCommandsForTests();
        WebElement waited = LeanWaits.waitForElementVisible(null, element, Duration.ofMillis(500));
        assertEquals(element, waited);
        assertEquals(List.of("query"), DomCompositeAdapter.get().commandsForTests());
        String calls = Files.readString(log);
        long queries = calls.lines().filter(line -> line.startsWith("query ")).count();
        assertEquals(1, queries, calls);
        assertTrue(calls.contains("--wait-for"), calls);
        assertTrue(calls.contains("visible"), calls);
        assertTrue(calls.contains("--poll-ms"), calls);
        assertTrue(calls.contains("150"), calls);
        assertTrue(calls.contains("--stability-ms"), calls);
        assertTrue(calls.contains(" 0"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("wait visible poll=150"), trace);
    }

    @Test
    void rejectedPollKeepsTheJavaWait() throws Exception {
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, "reject", false));
        CompositeWebElement element = CompositeElements.of("host", "div", "Save", 1, 2, 3, 4);
        DomCompositeAdapter.get().resetCommandsForTests();
        WebElement waited = LeanWaits.waitForElementVisible(null, element, Duration.ofMillis(500));
        assertEquals(element, waited);
        assertEquals(List.of("query"), DomCompositeAdapter.get().commandsForTests());
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("wait fallback"), trace);
    }

    @Test
    void engineTimeoutDoesNotWaitAgain() throws Exception {
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, "timeout", false));
        CompositeWebElement element = CompositeElements.of("host", "div", "Save", 1, 2, 3, 4);
        assertThrows(TimeoutException.class, () ->
                LeanWaits.waitForElementVisible(null, element, Duration.ofMillis(200)));
        assertEquals(List.of("query"), DomCompositeAdapter.get().commandsForTests());
        String calls = Files.readString(log);
        assertEquals(1, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
    }

    @Test
    void compositeOffDoesNotQuery() throws Exception {
        Files.createDirectories(temp.resolve("logs"));
        CompositeRuntime.use(
                temp.resolve("pin"),
                Map.of(
                        "pkb_composite", "off",
                        "pkb_composite_log", "adapter",
                        "pkb_composite_log_dir", temp.resolve("logs").toString()
                ),
                new ClassLoader(null) {},
                temp.resolve("home")
        );
        driver = new HtmlUnitDriver(true);
        URL page = LeanWaitNestLiveTest.class.getResource("/element-state/states.html");
        driver.get(Objects.requireNonNull(page, "states").toString());
        WebElement live = driver.findElement(By.id("host"));
        DomCompositeAdapter.get().resetCommandsForTests();
        WebElement waited = LeanWaits.waitForElementVisible(driver, live, Duration.ofMillis(800));
        assertEquals(live, waited);
        assertEquals(List.of(), DomCompositeAdapter.get().commandsForTests());
        assertFalse(ElementOperations.primary());
    }

    @Test
    void nestBatchIsOneExtraQuery() throws Exception {
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, "ok", false));
        driver = new HtmlUnitDriver(false);
        DomCompositeAdapter.get().resetCommandsForTests();
        ElementOperations.resetFindCallsForTests();
        List<WebElement> deepest = NestedByLocator.find(
                driver, By.xpath("//div"), false, NestedByLocator.NestingMode.DEEPEST_ONLY);
        assertEquals(List.of("main:2", "main:3"), ids(deepest));
        assertEquals(2, ElementOperations.findCallsForTests());
        String calls = Files.readString(log);
        assertEquals(2, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
        assertTrue(calls.contains("ancestor::*"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("nest batch"), trace);

        Files.writeString(log, "");
        ElementOperations.resetFindCallsForTests();
        List<WebElement> outermost = NestedByLocator.find(
                driver, By.xpath("//div"), false, NestedByLocator.NestingMode.OUTERMOST_ONLY);
        assertEquals(List.of("main:1", "main:2"), ids(outermost));
        assertEquals(2, ElementOperations.findCallsForTests());
        String again = Files.readString(log);
        assertEquals(2, again.lines().filter(line -> line.startsWith("query ")).count(), again);
        assertTrue(again.contains("descendant::*"), again);
    }

    @Test
    void liveRefreshIsTwoEvalsAndNoFind() throws Exception {
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, "ok", true));
        driver = new HtmlUnitDriver(false);
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(
                driver, CompositeElements.of("host", "div", "Hello", 1, 2, 3, 4));
        DomCompositeAdapter.get().resetCommandsForTests();
        ElementOperations.resetFindCallsForTests();
        long before = Files.readString(log).lines().filter(line -> line.startsWith("eval ")).count();
        wrapper.refreshSnapshot();
        assertEquals(List.of("eval", "eval"), DomCompositeAdapter.get().commandsForTests());
        assertEquals(0, ElementOperations.findCallsForTests());
        String calls = Files.readString(log);
        long evals = calls.lines().filter(line -> line.startsWith("eval ")).count();
        assertEquals(before + 2, evals, calls);
        assertTrue(calls.contains("isConnected"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("live main:42"), trace);
        assertEquals("div", wrapper.snapshot().tag());
        assertEquals("#host", wrapper.snapshot().stableSelector());
    }

    private void pin(Path binary) {
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
    }

    private static List<String> ids(List<WebElement> elements) {
        return elements.stream().map(el -> ((CompositeWebElement) el).nodeId()).toList();
    }

    private static Path stub(Path path, Path log, String queryMode, boolean eval) throws Exception {
        String actions = eval
                ? "[\"click\", \"type\", \"scroll\", \"eval\"]"
                : "[\"click\", \"type\", \"scroll\"]";
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                mode = %s
                actions = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                def node(node_id, html_id):
                    return {
                        "id": node_id,
                        "node_name": "DIV",
                        "text": html_id,
                        "attributes": {"id": html_id},
                        "bounds": {"x": 1, "y": 2, "width": 10, "height": 8}
                    }
                snapshot = {
                    "tag": "div",
                    "text": "Hello",
                    "value": "",
                    "selectedValue": "",
                    "attributes": {"id": "host"},
                    "siblingIndex": 0,
                    "sameTagIndex": 0,
                    "stableSelector": "#host",
                    "elementState": {
                        "disabled": False,
                        "blank": True,
                        "required": False,
                        "expanded": False,
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
                    text = " ".join(argv)
                    exit_code = 0
                    stderr = ""
                    if "version" in argv:
                        payload = {"version": "2.8.0"}
                    elif "capabilities" in argv:
                        payload = {"ok": True, "desktop": ["list", "query", "close"], "actions": actions}
                    elif "create" in argv:
                        payload = {"ok": True, "id": "sess-1", "launched": False}
                    elif argv and argv[0] == "eval":
                        if "isConnected" in text:
                            payload = {"ok": True, "kind": "eval", "value": True}
                        else:
                            payload = {"ok": True, "kind": "eval", "backend_node_id": 42, "value": snapshot}
                    elif argv and argv[0] == "query":
                        if mode == "reject":
                            exit_code = 2
                            stderr = "unexpected argument --poll-ms"
                            payload = {}
                        elif mode == "timeout":
                            exit_code = 1
                            stderr = json.dumps({"code": "timeout", "error": "timed out waiting"})
                            payload = {}
                        elif "ancestor::" in text:
                            payload = {"ok": True, "composite": [node("main:1", "outer")]}
                        elif "descendant::" in text:
                            payload = {"ok": True, "composite": [node("main:3", "inner")]}
                        else:
                            payload = {"ok": True, "composite": [
                                node("main:1", "outer"),
                                node("main:2", "mid"),
                                node("main:3", "inner")
                            ]}
                    else:
                        payload = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": exit_code,
                        "stdout": json.dumps(payload) + "\\n",
                        "stderr": stderr
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()), quote(queryMode), actions);
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
