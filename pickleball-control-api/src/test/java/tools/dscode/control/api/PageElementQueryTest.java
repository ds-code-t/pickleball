package tools.dscode.control.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.CompositeBinaryLocator;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementPhrase;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.composite.EngineQuery;
import tools.dscode.common.composite.SelectorTranslator;
import tools.dscode.control.protocol.DirectControlProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageElementQueryTest {
    @TempDir
    Path temp;

    @BeforeEach
    void isolate() {
        ElementOperations.resetBatchCallsForTests();
        CompositeRuntime.use(temp.resolve("project"), Map.of(), new ClassLoader(null) {
        }, temp.resolve("home"));
    }

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void countsRawAndDisplayedElementsWithoutClicking() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger finds = new AtomicInteger();
        List<WebElement> elements = List.of(
                element(true, clicks),
                element(false, clicks),
                element(true, clicks)
        );
        WebDriver driver = driver(elements, finds);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(3, hit.rawMatchCount());
        assertEquals(2, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertFalse(hit.xpath().isBlank(), hit.xpath());
        assertEquals(0, clicks.get());
        assertTrue(finds.get() > 0);
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void liveFindUsesCompositeInsteadOfTheDriver() throws Exception {
        Path binary = stub(temp.resolve("composite"));
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        AtomicInteger finds = new AtomicInteger();
        WebDriver driver = driver(List.of(), finds);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals(0, finds.get());
        assertTrue(CompositeRuntime.primary());
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void liveFindUsesOneScriptAndDoesNotReadEachElement() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = scriptDriver(scripts);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, "Button", "Save");
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertEquals(1, scripts.get());
        assertEquals(1, ElementOperations.batchCallsForTests());
    }

    @Test
    void inspectGroupsTheInteractiveBatchWithoutASecondQuery() {
        AtomicInteger scripts = new AtomicInteger();
        WebDriver driver = scriptDriver(scripts);
        List<PageElementQuery.Hit> hits = PageElementQuery.inspect(driver);
        assertEquals(1, scripts.get());
        assertEquals(1, ElementOperations.batchCallsForTests());
        assertEquals(1, hits.size());
        PageElementQuery.Hit hit = hits.get(0);
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertEquals("the \"Save\" Button", hit.phrase());
        assertFalse(hit.xpath().isBlank(), hit.xpath());
    }

    @Test
    void engineQueryForwardsTheSelectorAndKeepsCategoryCounts() throws Exception {
        Path binary = stub(temp.resolve("composite"));
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        System.setProperty(CompositeLog.KEY, "query");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        AtomicInteger finds = new AtomicInteger();
        WebDriver driver = driver(List.of(), finds);
        PageElementQuery.Hit hit = PageElementQuery.find(
                driver,
                EngineQuery.of("css=#save").exposure("lower").limit(2).explain(true)
        );
        assertEquals(0, finds.get());
        assertEquals("engine", hit.phrase());
        assertEquals("css=#save", hit.query());
        assertEquals(1, hit.rawMatchCount());
        assertEquals(1, hit.nodes().size());
        assertEquals("main:7", hit.nodes().get(0).handle());
        assertEquals(0, hit.nodes().get(0).rank());
        assertTrue(hit.nodes().get(0).humanCodes().contains("off_visual_viewport"));
        assertTrue(hit.nodes().get(0).humanSentence().contains("visual viewport"));
        String forwarded = Files.readString(temp.resolve("composite.requests"));
        assertTrue(forwarded.contains("--selector"), forwarded);
        assertTrue(forwarded.contains("css=#save"), forwarded);
        assertTrue(forwarded.contains("--exposure"), forwarded);
        assertTrue(forwarded.contains("lower"), forwarded);
        assertTrue(forwarded.contains("--explain"), forwarded);
        assertTrue(forwarded.contains("--limit"), forwarded);
        assertTrue(forwarded.contains("2"), forwarded);
        assertEquals(1, ElementOperations.batchCallsForTests());
        assertEquals("human", EngineQuery.controller("css=#save", null, null, null, null).exposure());
        assertEquals("lower", EngineQuery.controller("css=#save", "lower", null, null, null).exposure());
        assertEquals(null, EngineQuery.controller(" ", null, null, null, null));
    }

    @Test
    void humanActRefusesAndLowerActKeepsTheSentence() throws Exception {
        Path binary = stub(temp.resolve("composite"));
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        assertThrows(
                NoSuchElementException.class,
                () -> ElementOperations.act(EngineQuery.of("css=#ghost"), "click", null)
        );
        ElementView acted = ElementOperations.act(EngineQuery.of("css=#ghost").exposure("lower"), "click", null);
        assertEquals("main:9", acted.handle());
        assertTrue(acted.humanSentence().contains("opacity:0"), acted.humanSentence());
        assertTrue(acted.humanCodes().contains("human_could_not_click"));
        assertTrue(acted.humanCodes().contains("off_visual_viewport"));
        String forwarded = Files.readString(temp.resolve("composite.requests"));
        assertTrue(forwarded.contains("\"human\""), forwarded);
        assertTrue(forwarded.contains("\"lower\""), forwarded);
    }

    @Test
    void singleCssStageFallsBackToSeleniumAndAPipelineDoesNot() {
        AtomicInteger finds = new AtomicInteger();
        List<WebElement> elements = List.of(element(true, new AtomicInteger()), element(false, new AtomicInteger()));
        WebDriver driver = driver(elements, finds);
        PageElementQuery.Hit hit = PageElementQuery.find(driver, EngineQuery.of("css=#save"));
        assertEquals(2, hit.rawMatchCount());
        assertEquals(1, hit.visibleCount());
        assertTrue(finds.get() > 0);
        WebDriver empty = driver(List.of(), new AtomicInteger());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PageElementQuery.find(empty, EngineQuery.of("css=#a >> css=#b"))
        );
        assertTrue(failure.getMessage().contains("composite runtime"), failure.getMessage());
    }

    @Test
    void visualRelativeForwardsTheSelectorAndLeavesGeometryNull() throws Exception {
        Path binary = stub(temp.resolve("composite"), true);
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        WebDriver driver = driver(List.of(), new AtomicInteger());
        String selector = "css=#a >> left-of=#b";
        PageElementQuery.Hit hit = PageElementQuery.find(driver, EngineQuery.of(selector));
        assertEquals(selector, hit.query());
        assertEquals(1, hit.nodes().size());
        assertNull(hit.nodes().get(0).geometry());
        String forwarded = Files.readString(temp.resolve("composite.requests"));
        assertTrue(forwarded.contains(selector), forwarded);
        assertEquals("left-of", EngineQuery.visualRelative(selector));
        assertEquals("above", EngineQuery.visualRelative("css=#a AND above=#b"));
        assertEquals(null, EngineQuery.visualRelative("css=#save"));
        assertEquals(null, EngineQuery.visualRelative("text=left=foo"));
        assertTrue(SelectorTranslator.phrase(
                ElementPhrase.of("Button", "Save").context("after", "Billing", "Section")
        ).pipeline().contains("below=1"));
    }

    @Test
    void visualRelativeWithoutGeometryDoesNotInventABox() throws Exception {
        Path binary = stub(temp.resolve("composite-old"), false);
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        WebDriver driver = driver(List.of(), new AtomicInteger());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PageElementQuery.find(driver, EngineQuery.of("css=#a >> left-of=#b"))
        );
        assertTrue(failure.getMessage().contains("no boxes were invented"), failure.getMessage());
        String forwarded = Files.readString(temp.resolve("composite-old.requests"));
        assertFalse(forwarded.contains("left-of"), forwarded);
    }

    @Test
    void imagePromoteBindsANodeAndAVisualHitStaysLabeled() throws Exception {
        Path binary = stub(temp.resolve("composite"), true);
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        WebDriver driver = driver(List.of(), new AtomicInteger());
        PageElementQuery.Hit visual = PageElementQuery.find(driver, EngineQuery.of("image=logo"));
        assertTrue(visual.nodes().get(0).visualOnly());
        assertTrue(visual.nodes().get(0).humanCodes().contains("source_only"));
        PageElementQuery.Hit bound = PageElementQuery.find(
                driver, EngineQuery.of("image=logo").promoteVisual(true));
        assertFalse(bound.nodes().get(0).visualOnly());
        assertEquals("main:4", bound.nodes().get(0).handle());
        String forwarded = Files.readString(temp.resolve("composite.requests"));
        assertTrue(forwarded.contains("image=logo"), forwarded);
        assertTrue(forwarded.contains("--promote-visual"), forwarded);
    }

    @Test
    void fastSurfingBatchReturnsHandlesForOneFollowUpAct() throws Exception {
        Path binary = stub(temp.resolve("composite"), true);
        CompositeRuntime.use(
                temp.resolve("project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                new ClassLoader(null) {
                },
                temp.resolve("home")
        );
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        WebDriver driver = driver(List.of(), new AtomicInteger());
        PageElementQuery.Hit batch = PageElementQuery.find(
                driver, EngineQuery.of("css=a").limit(2).explain(true));
        assertEquals(2, batch.nodes().size());
        assertEquals(0, batch.nodes().get(0).rank());
        assertEquals(0.9, batch.nodes().get(0).score(), 0.0001);
        assertEquals(1, batch.nodes().get(1).rank());
        assertEquals("main:7", batch.nodes().get(0).handle());
        assertEquals("main:8", batch.nodes().get(1).handle());
        ElementView acted = ElementOperations.act(EngineQuery.of(batch.nodes().get(0).handle()), "click", null);
        assertEquals("main:7", acted.handle());
        assertFalse(acted.visualOnly());
        String forwarded = Files.readString(temp.resolve("composite.requests"));
        assertTrue(forwarded.contains("\"css=a\""), forwarded);
        assertTrue(forwarded.contains("--explain"), forwarded);
        assertTrue(forwarded.contains("--limit"), forwarded);
        assertTrue(forwarded.contains("\"main:7\""), forwarded);
    }

    private static WebDriver scriptDriver(AtomicInteger scripts) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> {
                    if ("executeScript".equals(method.getName())) {
                        scripts.incrementAndGet();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("tag", "button");
                        row.put("text", "Save");
                        row.put("displayed", true);
                        row.put("enabled", true);
                        row.put("selected", false);
                        row.put("attributes", Map.of("id", "save"));
                        row.put("value", "Save");
                        row.put("outerHtml", "<button id=\"save\">Save</button>");
                        row.put("x", 1);
                        row.put("y", 2);
                        row.put("width", 10);
                        row.put("height", 8);
                        return List.of(row);
                    }
                    if ("findElements".equals(method.getName()) || "findElement".equals(method.getName())
                            || "executeAsyncScript".equals(method.getName())) {
                        throw new AssertionError("page query must not call " + method.getName());
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static WebDriver driver(List<WebElement> elements, AtomicInteger finds) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("findElements".equals(method.getName())) {
                        finds.incrementAndGet();
                        return elements;
                    }
                    if ("findElement".equals(method.getName())) {
                        throw new AssertionError("page query must not call findElement");
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static WebElement element(boolean displayed, AtomicInteger clicks) {
        return (WebElement) Proxy.newProxyInstance(
                WebElement.class.getClassLoader(),
                new Class<?>[]{WebElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisplayed" -> displayed;
                    case "click" -> {
                        clicks.incrementAndGet();
                        yield null;
                    }
                    case "getTagName" -> "button";
                    case "getText" -> "Save";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == String.class) return "";
        return null;
    }

    private static Path stub(Path path) throws Exception {
        return stub(path, true);
    }

    private static Path stub(Path path, boolean geometry) throws Exception {
        String flags = geometry
                ? "\"within\", \"nesting\", \"nth\", \"every\", \"count-only\", \"geometry\", \"point\", \"exposure\", \"limit\""
                : "\"within\", \"nesting\", \"nth\", \"every\", \"count-only\", \"point\", \"exposure\", \"limit\"";
        String engines = geometry
                ? "\"css\", \"xpath\", \"text\", \"near\", \"below\", \"above\", \"left-of\", \"right-of\""
                : "\"css\", \"xpath\", \"text\"";
        String script = """
                #!/usr/bin/env python3
                import json, sys, os
                log = os.path.splitext(sys.argv[0])[0] + ".requests"
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(json.dumps(argv) + "\\n")
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    elif "capabilities" in argv:
                        body = {"ok": True, "query_flags": [__FLAGS__], "engines": [__ENGINES__]}
                    elif "query" in argv:
                        selector = ""
                        if "--selector" in argv:
                            selector = argv[argv.index("--selector") + 1]
                        promote = "--promote-visual" in argv
                        if selector.startswith("image="):
                            if promote:
                                nodes = [{
                                    "id": "main:4",
                                    "handle": "main:4",
                                    "text": "logo",
                                    "role": "img",
                                    "source": "cdp",
                                    "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                                    "attributes": {"id": "logo"},
                                    "states": ["enabled"],
                                    "rank": 0,
                                    "score": 0.8
                                }]
                            else:
                                nodes = [{
                                    "id": "visual:1",
                                    "text": "logo",
                                    "role": "image",
                                    "source": "visual",
                                    "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                                    "states": ["enabled"],
                                    "rank": 0,
                                    "score": 0.8,
                                    "human_limits": {
                                        "codes": ["source_only", "human_could_not_follow"],
                                        "sentence": "a person could not follow this hit because it is a visual match with no DOM node"
                                    }
                                }]
                        elif selector == "css=a":
                            nodes = [
                                {
                                    "id": "main:7",
                                    "handle": "main:7",
                                    "text": "Docs",
                                    "role": "link",
                                    "source": "cdp",
                                    "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                                    "attributes": {"id": "docs"},
                                    "states": ["enabled"],
                                    "rank": 0,
                                    "score": 0.9
                                },
                                {
                                    "id": "main:8",
                                    "handle": "main:8",
                                    "text": "Api",
                                    "role": "link",
                                    "source": "cdp",
                                    "bounds": {"x": 20, "y": 2, "width": 10, "height": 8},
                                    "attributes": {"id": "api"},
                                    "states": ["enabled"],
                                    "rank": 1,
                                    "score": 0.4
                                }
                            ]
                        else:
                            nodes = [{
                                "id": "main:7",
                                "handle": "main:7",
                                "text": selector or "Save",
                                "role": "button",
                                "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                                "attributes": {"id": "save"},
                                "states": ["enabled"],
                                "rank": 0,
                                "score": 0.5,
                                "human_limits": {
                                    "codes": ["off_visual_viewport"],
                                    "sentence": "scrolled out of the visual viewport"
                                }
                            }]
                        body = {"ok": True, "composite": nodes}
                    elif "click" in argv or "type" in argv:
                        selector = ""
                        if "--selector" in argv:
                            selector = argv[argv.index("--selector") + 1]
                        exposure = ""
                        if "--exposure" in argv:
                            exposure = argv[argv.index("--exposure") + 1]
                        if exposure == "human" and ":" not in selector:
                            body = {"ok": True}
                        else:
                            handle = selector if ":" in selector else "main:9"
                            body = {"ok": True, "node": {
                                "id": handle,
                                "handle": handle,
                                "text": "hidden" if exposure == "lower" else "Docs",
                                "role": "button",
                                "bounds": {"x": 1, "y": 900, "width": 10, "height": 8},
                                "states": ["hidden"] if exposure == "lower" else ["enabled"],
                                "human_limits": {
                                    "codes": ["human_could_not_see", "human_could_not_click", "off_visual_viewport", "opacity_zero"],
                                    "sentence": "a person could not have seen or clicked it because it is scrolled out of the visual viewport and has opacity:0"
                                } if exposure == "lower" else None
                            }}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.replace("__FLAGS__", flags).replace("__ENGINES__", engines);
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }
}
