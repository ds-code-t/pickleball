package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
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
        return DomEngineMain.dispatch(args);
    }

    private Path write(String name, String text) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
