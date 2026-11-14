package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.snapshot.CompositeSnapshot;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.SnapshotSchemas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.BindingListener;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.FrameNode;

import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacadeSnapshotProducerTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @AfterEach
    void reset() {
        SnapshotProducers.useFacadeForTests(null);
    }

    @Test
    void facadeWalkBuildsDistinctBoxesSourceAndPremap() throws Exception {
        SnapshotProducers.useFacadeForTests(driver -> new ScriptedFacade());
        JsonNode json = SnapshotProducers.page(null, new SnapshotOptions());
        JsonNode schema = Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
        SnapshotSchemas.assertValid(schema, json);
        assertEquals("bidi", json.path("transport").asText());
        assertEquals("firefox", json.path("platform").asText());
        assertEquals("main:4", json.path("doc_version").asText());
        assertTrue(json.path("source").path("text").asText().contains("<!-- frame:main -->"));
        assertTrue(json.path("source").path("text").asText().contains("<!-- frame:child -->"));
        assertTrue(json.path("source").path("text").asText().contains("framed"));
        assertTrue(json.path("premap").has("indexes"));
        assertEquals("main", json.path("frames").get(0).path("id").asText());
        assertEquals("child", json.path("frames").get(1).path("id").asText());
        JsonNode button = byId(json, "go");
        assertTrue(button.path("states").toString().contains("disabled"));
        assertEquals(200, button.path("boxes").path("document_css_px").path("y").asDouble(), 1e-9);
        assertEquals(200, button.path("boxes").path("frame_document").path("y").asDouble(), 1e-9);
        assertEquals(120, button.path("boxes").path("layout_viewport").path("y").asDouble(), 1e-9);
        assertEquals(110, button.path("boxes").path("visual_viewport").path("y").asDouble(), 1e-9);
        assertFalse(button.path("boxes").path("document_css_px").isNull());
        JsonNode inside = byId(json, "inside");
        assertTrue(inside.path("frame_path").asText().contains("iframe"), inside.toString());
        assertEquals(6, inside.path("boxes").path("frame_document").path("y").asDouble(), 1e-9);
        assertEquals(306, inside.path("boxes").path("document_css_px").path("y").asDouble(), 1e-9);
        assertFalse(inside.path("xpath").asText().isBlank());
        String producer = Files.readString(java.nio.file.Path.of(
                "src/main/java/tools/dscode/common/seleniumextensions/FacadeSnapshotProducer.java"));
        String page = Files.readString(java.nio.file.Path.of(
                "src/main/java/tools/dscode/common/seleniumextensions/SnapshotProducers.java"));
        String walk = Files.readString(java.nio.file.Path.of(
                "dom-engine/src/main/resources/dev/domengine/snapshot/page-walk.js"));
        assertFalse(producer.contains("executeCdpCommand"));
        assertFalse(page.contains("executeCdpCommand"));
        assertFalse(walk.contains("executeCdpCommand"));
    }

    private static JsonNode byId(JsonNode json, String id) {
        for (JsonNode node : json.path("nodes")) {
            if (id.equals(node.path("attributes").path("id").asText())) return node;
        }
        throw new AssertionError("missing " + id);
    }

    private static final class ScriptedFacade implements BrowserFacade {
        @Override
        public String protocol() {
            return "bidi";
        }

        @Override
        public Object send(String method, Map<String, ?> params) {
            return childWalk();
        }

        @Override
        public Object evaluateIsolated(String worldName, String fn, Object... args) {
            if (fn != null && (fn.contains("MutationObserver") || fn.contains("__pkbPremapCount"))) return "4";
            return rootWalk();
        }

        @Override
        public void addPreload(String script) {
        }

        @Override
        public void addBinding(String name, BindingListener listener) {
        }

        @Override
        public byte[] screenshot(Map<String, ?> options) {
            return PNG;
        }

        @Override
        public FrameNode frameTree() {
            return new FrameNode("main", null, "http://fixture/", List.of(FrameNode.leaf("child", "main", "http://fixture/frame")));
        }

        @Override
        public List<String> find(String using, String value) {
            return List.of();
        }

        @Override
        public void click(String handle) {
        }

        @Override
        public void type(String handle, String text) {
        }
    }

    private static Map<String, Object> rootWalk() {
        Map<String, Object> button = node("button", "go", Map.of("disabled", "true"), Map.of("x", 10, "y", 120, "width", 80, "height", 20), List.of());
        Map<String, Object> iframe = node("iframe", "child", Map.of(), Map.of("x", 0, "y", 220, "width", 200, "height", 150), List.of());
        Map<String, Object> body = node("body", null, Map.of(), Map.of("x", 0, "y", 0, "width", 800, "height", 600), List.of(button, iframe));
        Map<String, Object> html = node("html", null, Map.of(), Map.of("x", 0, "y", 0, "width", 800, "height", 600), List.of(body));
        Map<String, Object> walk = metrics(80, 10);
        walk.put("outerHTML", "<html><body><button id=\"go\" disabled>Go</button><iframe id=\"child\"></iframe></body></html>");
        walk.put("root", html);
        return walk;
    }

    private static Map<String, Object> childWalk() {
        Map<String, Object> paragraph = node("p", "inside", Map.of(), Map.of("x", 4, "y", 6, "width", 40, "height", 12), List.of());
        Map<String, Object> body = node("body", null, Map.of(), Map.of("x", 0, "y", 0, "width", 200, "height", 150), List.of(paragraph));
        Map<String, Object> html = node("html", null, Map.of(), Map.of("x", 0, "y", 0, "width", 200, "height", 150), List.of(body));
        Map<String, Object> walk = metrics(0, 0);
        walk.put("outerHTML", "<html><body><p id=\"inside\">framed</p></body></html>");
        walk.put("root", html);
        return walk;
    }

    private static Map<String, Object> metrics(double scrollY, double offsetY) {
        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("url", "http://fixture/");
        walk.put("scrollX", 0);
        walk.put("scrollY", scrollY);
        walk.put("innerWidth", 800);
        walk.put("innerHeight", 600);
        walk.put("devicePixelRatio", 1);
        walk.put("visual", Map.of("offsetX", 0, "offsetY", offsetY, "scale", 1, "width", 800, "height", 600));
        return walk;
    }

    private static Map<String, Object> node(String tag, String id, Map<String, String> extra, Map<String, Object> rect, List<Map<String, Object>> children) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (id != null) attributes.put("id", id);
        attributes.putAll(extra);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("tag", tag);
        node.put("attributes", attributes);
        node.put("role", "button".equals(tag) ? "button" : "");
        node.put("name", "button".equals(tag) ? "Go" : "");
        node.put("states", "button".equals(tag) ? List.of("disabled") : List.of());
        node.put("style", Map.of("display", "block", "visibility", "visible", "opacity", "1"));
        node.put("rect", rect);
        node.put("clientRects", List.of(rect));
        node.put("xpath", "");
        node.put("children", children);
        return node;
    }
}
