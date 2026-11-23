package tools.dscode.common.composite;

import tools.dscode.testsupport.StdioFake;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.seleniumextensions.ElementSnapshot;
import tools.dscode.common.seleniumextensions.ElementSnapshotScript;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bugs 1–3 once the binary lists {@code within} and {@code nesting}.
 * A binary that does not list them keeps the anchor fallback.
 */
class ContextNestGateTest {
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
    void withinFindsUnderAContextThatHasNoId() throws Exception {
        temp = Files.createTempDirectory("pkb-within");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        CompositeWebElement bare = element(null);
        assertNull(bare.anchor());
        List<WebElement> found = bare.findElements(By.xpath(".//span"));
        assertEquals(1, found.size());
        assertEquals("inside", found.getFirst().getText());
        String calls = Files.readString(log);
        assertTrue(calls.contains("--within main:bare"), calls);
        assertTrue(calls.contains(".//span"), calls);
        assertFalse(calls.contains("@id="), calls);
        assertEquals(1, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("within main:bare"), trace);
    }

    @Test
    void parenthesizedXPathStaysInsideTheContext() throws Exception {
        temp = Files.createTempDirectory("pkb-paren");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        CompositeWebElement host = element("panel");
        List<WebElement> found = host.findElements(By.xpath("(//span[@class='child'])[1]"));
        assertEquals("inside", found.getFirst().getText());
        String calls = Files.readString(log);
        assertTrue(calls.contains("--within main:panel"), calls);
        assertTrue(calls.contains("(//span[@class='child'])[1]"), calls);
        assertEquals(1, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
    }

    @Test
    void nestingWithoutAnchorsIsOneQuery() throws Exception {
        temp = Files.createTempDirectory("pkb-nest");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> deepest = NestedByLocator.find(
                    driver, By.cssSelector("div.nest"), true, NestedByLocator.NestingMode.DEEPEST_ONLY);
            assertEquals(List.of("main:inner"), ids(deepest));
            String calls = Files.readString(log);
            assertEquals(1, calls.lines().filter(line -> line.startsWith("query ")).count(), calls);
            assertTrue(calls.contains("--nesting deepest"), calls);
            assertTrue(calls.contains("--filter displayed"), calls);
            assertFalse(calls.contains("ancestor::"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("nesting deepest"), trace);
        } finally {
            driver.quit();
        }
    }

    @Test
    void missingFlagsKeepTheAnchorFallback() throws Exception {
        temp = Files.createTempDirectory("pkb-fallback");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, false));
        CompositeWebElement bare = element(null);
        assertEquals(List.of(), bare.findElements(By.xpath(".//span")));
        String calls = Files.readString(log);
        assertFalse(calls.contains("--within"), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("within fallback"), trace);
    }

    @Test
    void multiEvalIsOneCall() throws Exception {
        temp = Files.createTempDirectory("pkb-eval");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true));
        DomCompositeAdapter.get().resetCommandsForTests();
        CompositeWebElement first = hit("main:1");
        CompositeWebElement second = hit("main:2");
        Map<String, ElementSnapshot> shots = ElementSnapshotScript.onEvalAll(List.of(first, second));
        assertEquals(1, shots.get("main:1").siblingIndex());
        assertEquals(2, shots.get("main:2").siblingIndex());
        assertEquals(List.of("eval"), DomCompositeAdapter.get().commandsForTests());
        String calls = Files.readString(log);
        assertTrue(calls.contains("--node-ids main:1,main:2"), calls);
        assertEquals(1, calls.lines().filter(line -> line.startsWith("eval ")).count(), calls);
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("eval nodes=2"), trace);
    }

    private void pin(Path binary) {
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.KEY, "adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.resetForTests();
        assertTrue(ElementOperations.primary(), String.valueOf(CompositeLog.lastFallbackForTests()));
    }

    private static CompositeWebElement element(String id) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", "main:" + (id == null ? "bare" : id));
        node.put("node_name", "div");
        node.put("text", id == null ? "bare" : id);
        if (id != null) node.putObject("attributes").put("id", id);
        return (CompositeWebElement) CompositeWebElement.fromQuery("//div", node, 0);
    }

    private static CompositeWebElement hit(String nodeId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", nodeId);
        node.put("node_name", "div");
        node.put("text", nodeId);
        return (CompositeWebElement) CompositeWebElement.fromQuery("//div", node, 0);
    }

    private static List<String> ids(List<WebElement> elements) {
        return elements.stream().map(el -> ((CompositeWebElement) el).nodeId()).toList();
    }

    private static Path stub(Path path, Path log, boolean gates) throws Exception {
        return StdioFake.install(path, "nest", log.toString(), Boolean.toString(gates));
    }
}
