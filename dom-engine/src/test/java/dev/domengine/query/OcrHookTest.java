package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OCR hook, shared hash cache, and per-view gating through the query command. */
class OcrHookTest {
    private static final String PGM = "P2\n2 2\n255\n10 20\n30 40\n";
    private static final String PGM_OTHER = "P2\n2 2\n255\n11 20\n30 40\n";
    private static final String SEEN = """
            <html data-viewport-w="2" data-viewport-h="2"><body>
            <img id="seen" alt="seen" data-x="0" data-y="0" data-w="2" data-h="2">
            </body></html>
            """;
    private static final String COVERED = """
            <html data-viewport-w="2" data-viewport-h="2"><body>
            <img id="under" alt="under" data-x="0" data-y="0" data-w="2" data-h="2" data-paint-order="1">
            <div id="cover" data-x="0" data-y="0" data-w="2" data-h="2" data-paint-order="2" data-opaque="true"></div>
            </body></html>
            """;

    private Path dir;

    @BeforeEach
    void reset() throws Exception {
        System.clearProperty(OcrHook.PROPERTY);
        OcrHook.resetForTests();
        ViewMask.resetForTests();
        dir = Files.createTempDirectory("ocr-hook");
    }

    @AfterEach
    void clean() throws Exception {
        System.clearProperty(OcrHook.PROPERTY);
        OcrHook.resetForTests();
        ViewMask.resetForTests();
        if (dir != null) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // The temp directory is disposable.
                    }
                });
            }
        }
    }

    @Test
    void missingOrUnusableRuntimeFailsAndOtherTagsDoNotCallIt() throws Exception {
        Path viewport = write("view.pgm", PGM);
        Path page = write("seen.html", SEEN);
        Path plain = write("plain.html", """
                <html><body><div id="mark" data-x="0" data-y="0" data-w="2" data-h="2">mark</div></body></html>
                """);
        if (System.getenv("PKB_OCR_RUNTIME") == null || System.getenv("PKB_OCR_RUNTIME").isBlank()) {
            CliResult unset = query(page, viewport, "image=ocr:mark", null);
            assertNotInstalled(unset);
            assertEquals(0, OcrHook.runtimeReads());
        }
        System.setProperty(OcrHook.PROPERTY, dir.resolve("missing-runtime").toString());
        CliResult missing = query(page, viewport, "image=ocr:mark", null);
        assertNotInstalled(missing);
        assertEquals(0, OcrHook.runtimeReads());

        CliResult div = query(plain, viewport, "image=ocr:mark", "full");
        assertEquals(0, div.exit(), div.stderr());
        assertEquals(0, Jsons.MAPPER.readTree(div.stdout()).path("nodes").size());
        assertEquals(0, OcrHook.runtimeReads());

        Path binary = write("runtime.bin", "ONNX-not-a-text-runtime");
        System.setProperty(OcrHook.PROPERTY, binary.toString());
        CliResult unsupported = query(page, viewport, "image=ocr:mark", "full-access");
        assertEquals(1, unsupported.exit(), unsupported.stderr());
        JsonNode error = Jsons.MAPPER.readTree(unsupported.stderr());
        assertEquals("OCR runtime cannot be invoked", error.path("error").asText());
        assertEquals("ocr_runtime_unsupported", error.path("code").asText());
        assertEquals(1, OcrHook.runtimeReads());
    }

    @Test
    void stubIsCachedAcrossViewsAndAMaskedImageDoesNotReadTheRuntime() throws Exception {
        Path viewport = write("view.pgm", PGM);
        Path otherViewport = write("other.pgm", PGM_OTHER);
        String hash = OcrHook.cacheKey(TemplateMatch.parse(PGM));
        Path stub = write("runtime.txt", "pkb-ocr 1\n" + hash + " mark\n");
        System.setProperty(OcrHook.PROPERTY, stub.toString());
        Path seen = write("seen.html", SEEN);
        Path covered = write("covered.html", COVERED);

        CliResult first = query(seen, viewport, "image=ocr:mark", "full");
        assertEquals(0, first.exit(), first.stderr());
        JsonNode hit = Jsons.MAPPER.readTree(first.stdout());
        assertEquals("seen", hit.path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("mark", hit.path("image").path("text").asText());
        assertEquals(hash, hit.path("image").path("hash").asText());
        assertFalse(hit.path("image").path("cached").asBoolean());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult again = query(seen, viewport, "image=ocr:mark", "full-access");
        assertEquals(0, again.exit(), again.stderr());
        assertTrue(Jsons.MAPPER.readTree(again.stdout()).path("image").path("cached").asBoolean());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult human = query(seen, viewport, "image=ocr:mark", "human");
        assertEquals(0, human.exit(), human.stderr());
        assertEquals("seen", Jsons.MAPPER.readTree(human.stdout()).path("nodes").get(0).path("attributes").path("id").asText());
        assertTrue(Jsons.MAPPER.readTree(human.stdout()).path("image").path("cached").asBoolean());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult hidden = query(covered, viewport, "image=ocr:mark", "human");
        assertEquals(0, hidden.exit(), hidden.stderr());
        assertEquals(0, Jsons.MAPPER.readTree(hidden.stdout()).path("nodes").size());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult revealed = query(covered, viewport, "image=ocr:mark", "full");
        assertEquals(0, revealed.exit(), revealed.stderr());
        assertEquals("under", Jsons.MAPPER.readTree(revealed.stdout()).path("nodes").get(0).path("attributes").path("id").asText());
        assertTrue(Jsons.MAPPER.readTree(revealed.stdout()).path("image").path("cached").asBoolean());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult uncached = query(seen, otherViewport, "image=ocr:mark", "full");
        assertEquals(0, uncached.exit(), uncached.stderr());
        JsonNode miss = Jsons.MAPPER.readTree(uncached.stdout());
        assertEquals(0, miss.path("nodes").size());
        assertEquals("", miss.path("image").path("text").asText());
        assertFalse(miss.path("image").path("cached").asBoolean());
        assertEquals(2, OcrHook.runtimeReads());

        CliResult stored = query(seen, otherViewport, "image=ocr:mark", "human");
        assertEquals(0, stored.exit(), stored.stderr());
        assertTrue(Jsons.MAPPER.readTree(stored.stdout()).path("image").path("cached").asBoolean());
        assertEquals(2, OcrHook.runtimeReads());
    }

    @Test
    void svgAndImageRolesAreRecognized() throws Exception {
        Path viewport = write("view.pgm", PGM);
        String hash = OcrHook.cacheKey(TemplateMatch.parse(PGM));
        Path stub = write("runtime.txt", "pkb-ocr 1\n" + hash + " mark\n");
        System.setProperty(OcrHook.PROPERTY, stub.toString());
        Path page = write("icons.html", """
                <html data-viewport-w="2" data-viewport-h="2"><body>
                <img id="mark" data-x="0" data-y="0" data-w="2" data-h="2">
                <svg id="graphic" data-x="0" data-y="0" data-w="2" data-h="2"></svg>
                <span id="icon" role="img" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <div id="picture" role="image" data-x="0" data-y="0" data-w="2" data-h="2"></div>
                <button id="save" data-x="0" data-y="0" data-w="2" data-h="2">Save</button>
                <div id="plain" data-x="0" data-y="0" data-w="2" data-h="2">plain</div>
                </body></html>
                """);
        CliResult result = query(page, viewport, "image=ocr:mark", "full");
        assertEquals(0, result.exit(), result.stderr());
        JsonNode nodes = Jsons.MAPPER.readTree(result.stdout()).path("nodes");
        assertEquals(List.of("mark", "graphic", "icon", "picture"), ids(nodes));
        assertEquals(1, OcrHook.runtimeReads());
    }

    @Test
    void iconFontsSvgUseAndDesktopIconRolesAreRecognized() throws Exception {
        Path viewport = write("view.pgm", PGM);
        String hash = OcrHook.cacheKey(TemplateMatch.parse(PGM));
        Path stub = write("runtime.txt", "pkb-ocr 1\n" + hash + " mark\n");
        System.setProperty(OcrHook.PROPERTY, stub.toString());
        Path page = write("icon-fonts.html", """
                <html data-viewport-w="2" data-viewport-h="2"><body>
                <i id="fa" class="fas fa-save" data-x="0" data-y="0" data-w="2" data-h="2"></i>
                <span id="bi" class="bi bi-alarm" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <span id="material" class="material-icons" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <span id="symbols" class="material-symbols-outlined" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <i id="glyph" class="glyphicon glyphicon-search" data-x="0" data-y="0" data-w="2" data-h="2"></i>
                <span id="named" class="icon" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <i id="home" class="icon-home" data-x="0" data-y="0" data-w="2" data-h="2"></i>
                <span id="app" class="app-icon" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <span id="symbol" role="graphics-symbol" data-x="0" data-y="0" data-w="2" data-h="2"></span>
                <div id="gdoc" role="graphics-document" data-x="0" data-y="0" data-w="2" data-h="2"></div>
                <svg id="sprite" data-x="0" data-y="0" data-w="2" data-h="2"><use id="glyph-use" href="#g" data-x="0" data-y="0" data-w="2" data-h="2"></use></svg>
                <div id="atspi" role="icon" data-x="0" data-y="0" data-w="2" data-h="2"></div>
                <div id="ax" data-platform-role="AXImage" data-x="0" data-y="0" data-w="2" data-h="2"></div>
                <div id="uia" data-platform-role="Image" data-x="0" data-y="0" data-w="2" data-h="2"></div>
                <span id="plain" data-x="0" data-y="0" data-w="2" data-h="2">plain</span>
                <div id="font-on-div" class="fa-save" data-x="0" data-y="0" data-w="2" data-h="2">no</div>
                <span id="bio" class="biography" data-x="0" data-y="0" data-w="2" data-h="2">bio</span>
                <div id="text" data-x="0" data-y="0" data-w="2" data-h="2">words</div>
                </body></html>
                """);
        CliResult result = query(page, viewport, "image=ocr:mark", "full");
        assertEquals(0, result.exit(), result.stderr());
        assertEquals(List.of(
                "fa", "bi", "material", "symbols", "glyph", "named", "home", "app",
                "symbol", "gdoc", "sprite", "atspi", "ax", "uia"
        ), ids(Jsons.MAPPER.readTree(result.stdout()).path("nodes")));
        assertEquals(1, OcrHook.runtimeReads());

        DomNode axImage = desktop("AXImage", "AXImage");
        DomNode uiaImage = desktop("Image", "Image");
        DomNode atspiIcon = desktop("icon", "icon");
        DomNode mapped = desktop("img", "AXImage");
        assertTrue(OcrHook.target(axImage));
        assertTrue(OcrHook.target(uiaImage));
        assertTrue(OcrHook.target(atspiIcon));
        assertTrue(OcrHook.target(mapped));
        DomNode plain = new DomNode();
        plain.nodeType = 1;
        plain.localName = "span";
        plain.nodeValue = "plain";
        assertFalse(OcrHook.target(plain));
    }

    private static DomNode desktop(String role, String platformRole) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = "desktop";
        node.role = role;
        node.attributes.put("platform-role", platformRole);
        return node;
    }

    @Test
    void ocrViewsSkipsTheInactiveViewAndKeepsTheCache() throws Exception {
        Path viewport = write("view.pgm", PGM);
        String hash = OcrHook.cacheKey(TemplateMatch.parse(PGM));
        Path stub = write("runtime.txt", "pkb-ocr 1\n" + hash + " mark\n");
        System.setProperty(OcrHook.PROPERTY, stub.toString());
        Path page = write("seen.html", SEEN);

        CliResult full = query(page, viewport, "image=ocr:mark", "full", null);
        assertEquals(0, full.exit(), full.stderr());
        JsonNode first = Jsons.MAPPER.readTree(full.stdout());
        assertEquals(1, first.path("nodes").size());
        assertFalse(first.path("image").path("cached").asBoolean());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult off = query(page, viewport, "image=ocr:mark", "human", "full");
        assertEquals(0, off.exit(), off.stderr());
        JsonNode skipped = Jsons.MAPPER.readTree(off.stdout());
        assertEquals(0, skipped.path("nodes").size());
        assertEquals("ocr-views", skipped.path("image").path("skipped").asText(), skipped.toString());
        assertEquals("human", skipped.path("image").path("view").asText());
        assertEquals(1, OcrHook.runtimeReads());

        CliResult again = query(page, viewport, "image=ocr:mark", "human", "human");
        assertEquals(0, again.exit(), again.stderr());
        JsonNode hit = Jsons.MAPPER.readTree(again.stdout());
        assertEquals(1, hit.path("nodes").size());
        assertTrue(hit.path("image").path("cached").asBoolean(), hit.toString());
        assertEquals(1, OcrHook.runtimeReads());
    }

    @Test
    void onnxSlotReportsNotInstalledAndLoadsNothing() throws Exception {
        System.setProperty(OcrHook.PROPERTY, "onnx");
        Path viewport = write("view.pgm", PGM);
        Path page = write("seen.html", SEEN);
        CliResult result = query(page, viewport, "image=ocr:mark", "full");
        assertEquals(1, result.exit(), result.stderr());
        JsonNode error = Jsons.MAPPER.readTree(result.stderr());
        assertEquals("ocr_runtime_missing", error.path("code").asText(), error.toString());
        String message = error.path("error").asText();
        assertTrue(message.startsWith("OCR runtime not installed"), message);
        assertTrue(message.contains("Microsoft-signed ONNX Runtime"), message);
        assertTrue(message.contains("not bundled"), message);
        assertEquals(0, OcrHook.runtimeReads());
        assertTrue(OcrHook.select("onnx", "ignored") instanceof OnnxOcrRuntime);
        assertFalse(OcrHook.select("onnx", "ignored").opensRuntime());
        OcrHook.Result direct = new OnnxOcrRuntime().recognize(TemplateMatch.parse(PGM), "abc");
        assertFalse(direct.ok());
        assertEquals("ocr_runtime_missing", direct.code());
        assertEquals(OnnxOcrRuntime.MESSAGE, direct.error());
        assertTrue(OcrHook.select(null, null) == null);
        Path stub = write("stub.txt", "pkb-ocr 1\n");
        assertTrue(OcrHook.select(stub.toString(), "onnx") instanceof StubOcrRuntime);
    }

    @Test
    void runtimePathUsesThePropertyBeforeTheEnvironment() throws Exception {
        Path stub = write("stub.txt", "pkb-ocr 1\n");
        Path other = write("other.txt", "pkb-ocr 1\n");
        Path directory = Files.createTempDirectory(dir, "runtime-dir");
        assertNull(OcrHook.runtime("  ", " "));
        assertNull(OcrHook.runtime(dir.resolve("absent").toString(), stub.toString()));
        assertEquals(stub, OcrHook.runtime(stub.toString(), other.toString()));
        assertEquals(other, OcrHook.runtime("", other.toString()));
        assertNull(OcrHook.runtime(directory.toString(), stub.toString()));
    }

    private static void assertNotInstalled(CliResult result) throws Exception {
        assertEquals(1, result.exit(), result.stderr());
        JsonNode error = Jsons.MAPPER.readTree(result.stderr());
        assertEquals("OCR runtime not installed", error.path("error").asText());
        assertEquals("ocr_runtime_missing", error.path("code").asText());
    }

    private static List<String> ids(JsonNode nodes) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : nodes) ids.add(node.path("attributes").path("id").asText());
        return ids;
    }

    private CliResult query(Path html, Path viewport, String pipeline, String exposure) {
        return query(html, viewport, pipeline, exposure, null);
    }

    private CliResult query(Path html, Path viewport, String pipeline, String exposure, String ocrViews) {
        List<String> args = new ArrayList<>();
        args.add("query");
        args.add("--file");
        args.add(html.toString());
        args.add("--pipeline");
        args.add(pipeline);
        args.add("--viewport");
        args.add(viewport.toString());
        if (exposure != null) {
            args.add("--exposure");
            args.add(exposure);
        }
        if (ocrViews != null) {
            args.add("--ocr-views");
            args.add(ocrViews);
        }
        return DomEngineMain.dispatch(args);
    }

    private Path write(String name, String text) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
