package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.snapshot.DesktopSnapshotProducer;
import dev.domengine.snapshot.SnapshotDocuments;
import dev.domengine.snapshot.SnapshotSchemas;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSnapshotTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void fixtureNeedsAScreenshotAndThenValidates() throws Exception {
        Path fixture = Files.createTempFile("desktop-snap", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":100,"height":50,"children":[
                  {"role":"button","name":"Go","x":1,"y":1,"width":10,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "d", "--fixture", fixture.toString()));
        assertEquals(0, attached.exit(), attached.stderr());
        CliResult missing = DomEngineMain.dispatch(List.of("desktop", "snapshot", "--id", "d"));
        assertEquals(1, missing.exit(), missing.stderr());
        assertTrue(missing.stderr().contains("error: desktop fixture snapshot needs --screenshot"), missing.stderr());

        Path png = Files.createTempFile("shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        CliResult shot = DomEngineMain.dispatch(List.of(
                "desktop", "snapshot", "--id", "d", "--screenshot", png.toString()));
        assertEquals(0, shot.exit(), shot.stderr());
        JsonNode json = Jsons.MAPPER.readTree(shot.stdout());
        SnapshotSchemas.assertValid(SnapshotDocuments.schema(json.path("version").asInt(1)), json);
        assertEquals(2, json.path("version").asInt());
        assertEquals("html-ish", json.path("canonical").path("kind").asText());
        assertEquals("a11y-xml", json.path("source").path("kind").asText());
        assertEquals(json.path("source").path("text").asText(), json.path("canonical").path("side").path("a11y_xml").asText());
        assertEquals("data-pkb-native-path", json.path("canonical").path("side").path("native_path_attr").asText());
        assertTrue(frameHtml(json).contains("role=\"window\""), frameHtml(json));
        assertTrue(frameHtml(json).contains(">Go<"), frameHtml(json));
        assertEquals("desktop:0", json.path("doc_version").asText());
        assertTrue(json.path("premap").path("sections").size() > 0);
        JsonNode button = null;
        for (JsonNode node : json.path("nodes")) {
            if ("button".equals(node.path("role").asText())) button = node;
        }
        assertTrue(button != null, json.path("nodes").toString());
        assertEquals(10, button.path("boxes").path("screen_px").path("width").asDouble(), 0);
        assertTrue(button.path("boxes").path("image_px").isObject());
        assertTrue(button.path("xpath").asText().contains("["));

        CliResult click = DomEngineMain.dispatch(List.of("desktop", "click", "--id", "d", "--role", "button"));
        assertEquals(0, click.exit(), click.stderr());
        CliResult again = DomEngineMain.dispatch(List.of(
                "desktop", "snapshot", "--id", "d", "--screenshot", png.toString()));
        assertEquals("desktop:1", Jsons.MAPPER.readTree(again.stdout()).path("doc_version").asText());
        assertThrows(CdpException.class, () -> DesktopTree.snapshot("missing", java.util.Map.of()));
    }

    @Test
    void synthesizesHtmlIshForWindowsMacAndLinux() throws Exception {
        assertBackend("windows", "windows-uia", "uia", "uia-runtime-id", "42.7");
        assertBackend("macos", "macos-ax", "ax", "ax-path", "0.1.2");
        assertBackend("linux", "linux-atspi", "atspi", "atspi-path", "/org/a11y/1");
        assertDesktopRootIsNotEmitted();
    }

    private static void assertBackend(
            String family, String platform, String backend, String pathAttr, String path
    ) throws Exception {
        Path fixture = Files.createTempFile("desktop-" + family, ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":100,"height":40,"platformRole":"Window","backend":"%s","%s":"%s","children":[
                  {"role":"button","name":"Go","x":1,"y":1,"width":10,"height":10,"automationId":"SaveBtn","className":"Button Control","helpText":"Saves","platformRole":"Button","backend":"%s","%s":"%s","states":["focused"],"attributes":{"labeled-by":"1.2"}},
                  {"role":"button","name":"Bad","x":2,"y":2,"width":10,"height":10,"automationId":"9no","backend":"%s"},
                  {"role":"textbox","name":"Name","value":"Ada","x":1,"y":12,"width":20,"height":8},
                  {"role":"checkbox","name":"On","x":1,"y":22,"width":8,"height":8,"states":["checked","disabled"]},
                  {"role":"combobox","name":"Pick","x":20,"y":1,"width":20,"height":8},
                  {"role":"combobox","name":"Open","x":40,"y":1,"width":20,"height":8,"states":["expanded"]},
                  {"role":"pane","name":"Extra","x":1,"y":1,"width":4,"height":4},
                  {"role":"img","name":"Logo","x":50,"y":1,"width":8,"height":8},
                  {"role":"dialog","name":"Ask","x":5,"y":5,"width":30,"height":20},
                  {"role":"label","name":"Hi","x":1,"y":30,"width":8,"height":8,"states":["offscreen"]},
                  {"role":"menuitem","name":"File","text":"File item","x":1,"y":1,"width":8,"height":8}
                ]}
                """.formatted(backend, pathAttr, path, backend, pathAttr, path, backend));
        DesktopTree.Session attached = DesktopTree.attach("syn-" + family, fixture);
        DesktopTree.Session named = new DesktopTree.Session(
                attached.id(), attached.backend(), attached.root(), false, family, "", attached.sequence());
        Path png = Files.createTempFile("syn-shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        JsonNode json = DesktopSnapshotProducer.produce(named, java.util.Map.of("screenshot", png.toString()));
        assertEquals(platform, json.path("platform").asText());
        assertEquals(2, json.path("version").asInt());
        assertEquals("a11y-xml", json.path("source").path("kind").asText());
        assertEquals(json.path("source").path("text").asText(), json.path("canonical").path("side").path("a11y_xml").asText());
        SnapshotSchemas.assertValid(SnapshotDocuments.schema(2), json);
        String html = frameHtml(json);
        assertTrue(html.startsWith("<html><body><section"), html);
        assertTrue(html.contains("role=\"window\""), html);
        assertTrue(html.contains("aria-label=\"W\""), html);
        assertTrue(html.contains("data-pkb-backend=\"" + backend + "\""), html);
        assertTrue(html.contains("data-pkb-native-path=\"" + path + "\""), html);
        assertTrue(html.contains("data-platform-role=\"Button\""), html);
        assertTrue(html.contains("data-pkb-attr-labeled-by=\"1.2\""), html);
        assertTrue(html.contains("data-pkb-focused=\"true\""), html);
        assertTrue(html.contains("<input type=\"text\""), html);
        assertTrue(html.contains("aria-label=\"Name\""), html);
        assertTrue(html.contains("value=\"Ada\""), html);
        assertTrue(html.contains("type=\"checkbox\""), html);
        assertTrue(html.contains("checked=\"checked\""), html);
        assertTrue(html.contains("disabled=\"disabled\""), html);
        assertTrue(html.contains("aria-disabled=\"true\""), html);
        assertTrue(html.contains("aria-expanded=\"false\""), html);
        assertTrue(html.contains("aria-expanded=\"true\""), html);
        assertTrue(html.contains("role=\"pane\""), html);
        assertTrue(html.contains("aria-label=\"Logo\""), html);
        assertTrue(html.contains("<dialog open=\"open\""), html);
        assertTrue(html.contains("data-pkb-offscreen=\"true\""), html);
        assertTrue(html.contains(">Hi<"), html);
        assertTrue(html.contains(">File item<"), html);
        assertTrue(html.contains("aria-label=\"File\""), html);
        if ("uia".equals(backend)) {
            assertTrue(html.contains("id=\"SaveBtn\""), html);
            assertTrue(html.contains("data-uia-automation-id=\"SaveBtn\""), html);
            assertTrue(html.contains("data-uia-automation-id=\"9no\""), html);
            assertFalse(html.contains(" id=\"9no\""), html);
            assertTrue(html.contains("class=\"Button Control\""), html);
            assertTrue(html.contains("data-uia-class=\"Button Control\""), html);
            assertTrue(html.contains("title=\"Saves\""), html);
            assertTrue(html.contains(">Go<"), html);
        } else {
            assertTrue(!html.contains("data-uia-automation-id"), html);
            assertTrue(!html.contains("data-uia-class"), html);
            assertTrue(!html.contains("id=\"SaveBtn\""), html);
            assertTrue(html.contains("title=\"Saves\""), html);
            assertTrue(html.contains(">Go<"), html);
        }
        JsonNode go = null;
        for (JsonNode node : json.path("nodes")) {
            if ("Go".equals(node.path("name").asText())) go = node;
        }
        assertTrue(go != null, json.path("nodes").toString());
        assertEquals(10, go.path("boxes").path("screen_px").path("width").asDouble(), 0);
        assertTrue(go.path("boxes").path("document_css_px").isNull());
        assertTrue(go.path("xpath").asText().contains("["));
    }

    @Test
    void textboxChildStaysInsideTheControlAndAStrayCloseReturns() throws Exception {
        Path fixture = Files.createTempFile("desktop-void", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":100,"height":40,"children":[
                  {"role":"textbox","name":"Name","value":"Ada","x":1,"y":1,"width":40,"height":12,"children":[
                    {"role":"button","name":"Inner","x":2,"y":2,"width":8,"height":8}
                  ]},
                  {"role":"button","name":"After","x":50,"y":1,"width":8,"height":8}
                ]}
                """);
        DesktopTree.Session attached = DesktopTree.attach("syn-void", fixture);
        DesktopTree.Session named = new DesktopTree.Session(
                attached.id(), attached.backend(), attached.root(), false, "windows", "", attached.sequence());
        Path png = Files.createTempFile("syn-void-shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        JsonNode json = DesktopSnapshotProducer.produce(named, java.util.Map.of("screenshot", png.toString()));
        String html = frameHtml(json);
        assertTrue(html.contains("</input>"), html);
        assertTrue(html.contains(">After<"), html);
        JsonNode inner = null;
        JsonNode after = null;
        for (JsonNode node : json.path("nodes")) {
            if ("Inner".equals(node.path("name").asText())) inner = node;
            if ("After".equals(node.path("name").asText())) after = node;
        }
        assertNotNull(inner, json.path("nodes").toString());
        assertNotNull(after, json.path("nodes").toString());
        assertTrue(inner.path("xpath").asText().contains("input"), inner.path("xpath").asText());
        assertFalse(after.path("xpath").asText().contains("input"), after.path("xpath").asText());

        DomNode parsed = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> HtmlTree.parse(
                "<html><body></div><p id=\"kept\">ok</p>"
                        + "<input><button id=\"in\">In</button></input>"
                        + "<button id=\"sib\">Sib</button></body></html>"));
        assertNotNull(elementById(parsed, "kept"));
        DomNode in = elementById(parsed, "in");
        DomNode sib = elementById(parsed, "sib");
        assertEquals("input", in.parent.localName);
        assertFalse("input".equals(sib.parent.localName));
        DomNode bare = HtmlTree.parse("<html><body><input type=\"text\"><button id=\"next\">N</button></body></html>");
        assertEquals("body", elementById(bare, "next").parent.localName);
    }

    private static DomNode elementById(DomNode node, String id) {
        if (node.nodeType == 1 && id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = elementById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static void assertDesktopRootIsNotEmitted() throws Exception {
        Path fixture = Files.createTempFile("desktop-root", ".json");
        Files.writeString(fixture, """
                {"role":"desktop","name":"Desk","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Go","x":1,"y":1,"width":10,"height":10}
                ]}
                """);
        DesktopTree.Session attached = DesktopTree.attach("syn-root", fixture);
        DesktopTree.Session named = new DesktopTree.Session(
                attached.id(), attached.backend(), attached.root(), false, "windows", "", attached.sequence());
        Path png = Files.createTempFile("syn-root-shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        JsonNode json = DesktopSnapshotProducer.produce(named, java.util.Map.of("screenshot", png.toString()));
        String html = frameHtml(json);
        assertTrue(html.contains("<body><button"), html);
        assertTrue(!html.contains("role=\"desktop\""), html);
        assertTrue(html.contains(">Go<"), html);
    }

    private static String frameHtml(JsonNode json) {
        return json.path("canonical").path("frames").path(0).path("html").asText();
    }
}
