package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.Argv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.seleniumextensions.ElementSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer that used to read only Bounds now reads Geometry spaces when the
 * binary lists {@code geometry}. One query. A binary without the flag keeps the AABB.
 */
class GeometryViewTest {
    private Path temp;

    @AfterEach
    void tearDown() {
        CompositeRuntime.resetForTests();
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
    }

    @Test
    void boundsOnlyHitHasNoGeometryAndADescribedHitKeepsBoth() {
        CompositeWebElement plain = CompositeElements.of("go", "button", "Go", 4, 5, 6, 7);
        assertNull(plain.geometry());
        assertEquals(4, plain.getLocation().getX());
        List<ElementView> plainViews = ElementOperations.describe(null, List.of(plain));
        assertNull(plainViews.get(0).geometry());
        assertEquals(6, plainViews.get(0).width());

        CompositeWebElement rich = CompositeElements.withGeometry("go", "button", "Go", 4, 5, 6, 7);
        assertNotNull(rich.geometry());
        assertTrue(rich.geometry().matches(new ElementSnapshot.Bounds(4, 5, 6, 7)));
        assertEquals("quad", rich.geometry().documentCssPx().kind());
        assertEquals(4, rich.geometry().frameDocumentRelative().corners().size());
        ElementView view = ElementReads.fromComposite(rich);
        assertTrue(view.geometry().matches(new ElementSnapshot.Bounds(
                view.x(), view.y(), view.width(), view.height())));
    }

    @Test
    void argvForwardsGeometryOnlyOnQuery() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("css", "#go");
        body.put("geometry", true);
        List<String> query = Argv.from("query", body);
        assertTrue(query.contains("--geometry"), query.toString());
        List<String> resolve = Argv.from("resolve", body);
        assertFalse(resolve.contains("--geometry"), resolve.toString());
    }

    @Test
    void oneQueryReturnsSpacesWhenTheFlagIsListed() throws Exception {
        temp = Files.createTempDirectory("pkb-geometry");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true, false));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            DomCompositeAdapter.get().resetCommandsForTests();
            List<WebElement> found = ElementOperations.findElements(driver, By.cssSelector("#go"));
            assertEquals(1, found.size());
            CompositeWebElement hit = (CompositeWebElement) found.get(0);
            assertEquals(12, hit.getLocation().getX());
            assertEquals(16, hit.getSize().getHeight());
            assertNotNull(hit.geometry());
            assertTrue(hit.geometry().matches(new ElementSnapshot.Bounds(12, 20, 20, 16)));
            assertEquals(10.0, hit.geometry().visualViewportRelative().x());
            assertEquals(12.0, hit.geometry().layoutViewportRelative().x());
            assertEquals(2.0, hit.geometry().nearestScrollportRelative().x());
            assertEquals("main", hit.geometry().nearestScrollport().id());
            assertTrue(hit.geometry().closedShadowLimit().contains("retargets"));
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertTrue(calls.contains("--geometry"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("TRACE geometry request"), trace);
            assertTrue(trace.contains("INFO geometry real"), trace);
            assertTrue(trace.contains("INFO adapter geometry real"), trace);
            assertFalse(trace.contains("geometry fallback"), trace);
            List<ElementView> described = ElementOperations.describe(driver, found);
            assertEquals(1, described.size());
            assertNotNull(described.get(0).geometry());
            assertTrue(described.get(0).geometry().documentCssPx().corners().get(0).x() == 12.0);
        } finally {
            driver.quit();
        }
    }

    @Test
    void missingFlagKeepsTheAabbAndDoesNotAsk() throws Exception {
        temp = Files.createTempDirectory("pkb-geometry-off");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, false, false));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            List<WebElement> found = ElementOperations.findElements(driver, By.cssSelector("#go"));
            assertEquals(1, found.size());
            CompositeWebElement hit = (CompositeWebElement) found.get(0);
            assertEquals(12, hit.getLocation().getX());
            assertNull(hit.geometry());
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertFalse(calls.contains("--geometry"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("INFO geometry fallback"), trace);
            assertTrue(trace.contains("INFO adapter geometry fallback"), trace);
            assertFalse(trace.contains("geometry real"), trace);
        } finally {
            driver.quit();
        }
    }

    @Test
    void scrollableRegionExposesClientAndContentInOneQuery() throws Exception {
        temp = Files.createTempDirectory("pkb-scrollport");
        Path log = temp.resolve("calls.log");
        pin(stub(temp.resolve("composite"), log, true, true));
        CompositeRuntime.joinForTests("http://127.0.0.1:9222");
        HtmlUnitDriver driver = new HtmlUnitDriver(false);
        try {
            List<WebElement> found = ElementOperations.findElements(driver, By.cssSelector("#go"));
            assertEquals(1, found.size());
            CompositeWebElement hit = (CompositeWebElement) found.get(0);
            assertNotNull(hit.geometry());
            assertNotNull(hit.geometry().scrollport());
            ElementSnapshot.Spaces client = hit.geometry().scrollport().client();
            ElementSnapshot.Spaces content = hit.geometry().scrollport().content();
            assertNotNull(client);
            assertNotNull(content);
            assertEquals(108.0, client.documentCssPx().x());
            assertEquals(180.0, client.documentCssPx().width());
            assertEquals(78.0, content.documentCssPx().x());
            assertEquals(400.0, content.documentCssPx().width());
            assertEquals(800.0, content.documentCssPx().height());
            assertEquals("client-box", hit.geometry().nearestScrollport().origin());
            String calls = Files.readString(log);
            assertEquals(1, queries(calls), calls);
            assertTrue(calls.contains("--geometry"), calls);
            String trace = Files.readString(CompositeLog.file());
            assertTrue(trace.contains("INFO geometry scrollport client+content"), trace);
        } finally {
            driver.quit();
        }
    }

    @Test
    void clientBoxWithoutContentSizeDoesNotInventContent() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", "main:9");
        node.put("node_name", "DIV");
        ObjectNode bounds = node.putObject("bounds");
        bounds.put("x", 100);
        bounds.put("y", 40);
        bounds.put("width", 200);
        bounds.put("height", 150);
        ObjectNode geometry = node.putObject("geometry");
        ObjectNode document = geometry.putObject("document_css_px");
        document.put("kind", "quad");
        document.put("x", 100);
        document.put("y", 40);
        document.put("width", 200);
        document.put("height", 150);
        ObjectNode scrollport = geometry.putObject("scrollport");
        ObjectNode client = scrollport.putObject("client");
        ObjectNode clientDoc = client.putObject("document_css_px");
        clientDoc.put("kind", "aabb");
        clientDoc.put("x", 100);
        clientDoc.put("y", 40);
        clientDoc.put("width", 180);
        clientDoc.put("height", 140);
        CompositeWebElement hit = new CompositeWebElement(
                new CompositeSelector(null, "#pane", null, "web", null, "#pane"), node, 0);
        assertNotNull(hit.geometry().scrollport());
        assertEquals(180.0, hit.geometry().scrollport().client().documentCssPx().width());
        assertNull(hit.geometry().scrollport().content());
        assertEquals(100, hit.getLocation().getX());
    }

    private void pin(Path binary) {
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.KEY, "geometry,adapter");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        CompositeRuntime.resetForTests();
        assertTrue(ElementOperations.primary(), String.valueOf(CompositeLog.lastFallbackForTests()));
    }

    private static long queries(String calls) {
        return calls.lines().filter(line -> line.startsWith("query ")).count();
    }

    private static Path stub(Path path, Path log, boolean geometry, boolean scrollport) throws Exception {
        String flags = geometry
                ? "[\"within\", \"nesting\", \"nth\", \"every\", \"count-only\", \"geometry\"]"
                : "[\"within\", \"nesting\", \"nth\", \"every\", \"count-only\"]";
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                flags = %s
                scrollport = %s
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                def space(x, y, w, h):
                    return {
                        "kind": "quad",
                        "corners": [[x, y], [x + w, y], [x + w, y + h], [x, y + h]],
                        "x": x, "y": y, "width": w, "height": h
                    }
                def node(with_geometry):
                    row = {
                        "id": "main:7",
                        "node_name": "BUTTON",
                        "text": "Go",
                        "attributes": {"id": "go"},
                        "bounds": {"x": 12, "y": 20, "width": 20, "height": 16}
                    }
                    if with_geometry:
                        row["geometry"] = {
                            "closed_shadow": False,
                            "closed_shadow_limit": "Page script hit-testing retargets to the host. Corners are CDP or stored quads, not page-JS elementFromPoint.",
                            "document_css_px": space(12, 20, 20, 16),
                            "visual_viewport_relative": space(10, 17, 20, 16),
                            "layout_viewport_relative": space(12, 18, 20, 16),
                            "nearest_scrollport_relative": space(2, 4, 20, 16),
                            "frame_document_relative": space(12, 20, 20, 16),
                            "nearest_scrollport": {"kind": "viewport", "id": "main", "origin": "frame-scroll"}
                        }
                        if scrollport:
                            row["geometry"]["nearest_scrollport"]["origin"] = "client-box"
                            row["geometry"]["scrollport"] = {
                                "client": {"document_css_px": space(108, 104, 180, 140)},
                                "content": {"document_css_px": space(78, 54, 400, 800)}
                            }
                    return row
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    if "version" in argv:
                        payload = {"version": "2.11.0"}
                    elif "capabilities" in argv:
                        payload = {"ok": True, "actions": ["click", "type", "scroll", "eval"], "query_flags": flags, "eval_nodes": "one node"}
                    elif "create" in argv:
                        payload = {"ok": True, "id": "sess-1", "launched": False}
                    elif argv and argv[0] == "query":
                        payload = {"ok": True, "count": 1, "nodes": [node("--geometry" in argv)], "composite": [node("--geometry" in argv)]}
                    else:
                        payload = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(payload) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()), flags, scrollport ? "True" : "False");
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
