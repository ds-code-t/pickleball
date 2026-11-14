package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Position goes to the engine after displayed and nesting, unless a regex still
 * has to run or the binary does not list the flag. One query either way.
 */
class EnginePositionGateTest {
    private Path temp;

    @AfterEach
    void tearDown() {
        CompositeRuntime.resetForTests();
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
    }

    @Test
    void thirdLastAndEveryAreOneQuery() throws Exception {
        temp = Files.createTempDirectory("pkb-nth");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> third = NestedByLocator.findWithRetry(driver, By.cssSelector("span.ord"), match("3", ""));
            assertEquals(List.of("three"), texts(third));
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertTrue(calls.contains("--nth 2"), calls);
            assertTrue(calls.contains("--nesting deepest"), calls);
            assertTrue(calls.contains("--filter displayed"), calls);
            assertFalse(calls.contains("--count-only"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("nth 2"), trace);
            assertFalse(trace.contains("position java"), trace);

            Files.writeString(log, "");
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> every = NestedByLocator.findWithRetry(driver, By.cssSelector("span.ord"), match("2", "every"));
            assertEquals(List.of("two", "four"), texts(every));
            String everyCalls = Files.readString(log);
            assertEquals(1, queries(everyCalls), everyCalls);
            assertTrue(everyCalls.contains("--nth 1"), everyCalls);
            assertTrue(everyCalls.contains("--every 2"), everyCalls);

            Files.writeString(log, "");
            List<WebElement> last = NestedByLocator.findWithRetry(driver, By.cssSelector("span.ord"), match("last", ""));
            assertEquals(List.of("five"), texts(last));
            assertTrue(Files.readString(log).contains("--nth -1"), Files.readString(log));
        } finally {
            driver.quit();
        }
    }

    @Test
    void regexStaysInJavaBeforePosition() throws Exception {
        temp = Files.createTempDirectory("pkb-nth-regex");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            ElementMatch match = match("1", "");
            match.textOps.add(new ElementMatch.TextOp(
                    ValueWrapper.createValueWrapper("t.*"), ExecutionDictionary.Op.MATCHES));
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> found = NestedByLocator.findWithRetry(driver, By.cssSelector("span.ord"), match);
            assertEquals(List.of("two"), texts(found));
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertFalse(calls.contains("--nth"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("position java"), trace);
        } finally {
            driver.quit();
        }
    }

    @Test
    void missingNthKeepsJavaPosition() throws Exception {
        temp = Files.createTempDirectory("pkb-nth-off");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, false));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> third = NestedByLocator.findWithRetry(driver, By.cssSelector("span.ord"), match("3", ""));
            assertEquals(List.of("three"), texts(third));
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertFalse(calls.contains("--nth"), calls);
            assertTrue(calls.contains("--nesting deepest"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("position java"), trace);
        } finally {
            driver.quit();
        }
    }

    @Test
    void countOnlyIsOneQueryAndNoNodes() throws Exception {
        temp = Files.createTempDirectory("pkb-count");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        DomCompositeAdapter.get().resetCommandsForTests();
        int count = DomCompositeAdapter.get().countQuery(By.cssSelector("span.ord"), null, "deepest", "displayed", null);
        assertEquals(5, count);
        String calls = Files.readString(log);
        assertEquals(1, queries(calls), calls);
        assertTrue(calls.contains("--count-only"), calls);
        assertTrue(calls.contains("--nesting deepest"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("count 5"), trace);
        assertEquals(List.of("query"), DomCompositeAdapter.get().commandsForTests());
    }

    private void pin(Path binary) {
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.resetForTests();
        assertTrue(ElementOperations.primary(), String.valueOf(CompositeLog.lastFallbackForTests()));
    }

    private static ElementMatch match(String position, String selection) {
        ElementMatch match = ElementMatch.resolved("Span");
        match.elementPosition = position;
        match.selectionType = selection;
        return match;
    }

    private static List<String> texts(List<WebElement> elements) {
        return elements.stream().map(WebElement::getText).toList();
    }

    private static long queries(String calls) {
        return calls.lines().filter(line -> line.startsWith("query ")).count();
    }

    private static Path stub(Path path, Path log, boolean nth) throws Exception {
        String flags = nth
                ? "[\"within\", \"nesting\", \"nth\", \"every\", \"count-only\"]"
                : "[\"within\", \"nesting\"]";
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                flags = %s
                names = ["one", "two", "three", "four", "five"]
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                def node(node_id, text):
                    return {
                        "id": node_id,
                        "node_name": "SPAN",
                        "text": text,
                        "attributes": {},
                        "bounds": {"x": 1, "y": 2, "width": 10, "height": 8}
                    }
                def flag(argv, name):
                    if name not in argv:
                        return None
                    at = argv.index(name)
                    return argv[at + 1] if at + 1 < len(argv) else None
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        payload = {"version": "2.10.0"}
                    elif "capabilities" in argv:
                        payload = {"ok": True, "actions": ["click", "type", "scroll", "eval"], "query_flags": flags, "eval_nodes": None}
                    elif "create" in argv:
                        payload = {"ok": True, "id": "sess-1", "launched": False}
                    elif argv and argv[0] == "query":
                        rows = [node("main:" + str(i + 1), name) for i, name in enumerate(names)]
                        if "--count-only" in argv:
                            payload = {"ok": True, "count": len(rows), "nodes": [], "count_only": True}
                        else:
                            nth = flag(argv, "--nth")
                            every = flag(argv, "--every")
                            if nth is not None:
                                start = int(nth)
                                if start < 0:
                                    start = len(rows) + start
                                step = int(every) if every is not None else None
                                if start < 0 or start >= len(rows):
                                    chosen = []
                                elif step:
                                    chosen = rows[start::step]
                                else:
                                    chosen = [rows[start]]
                            else:
                                chosen = rows
                            payload = {"ok": True, "count": len(chosen), "nodes": chosen}
                    else:
                        payload = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(payload) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()), flags);
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
