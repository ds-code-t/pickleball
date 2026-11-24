package dev.domengine.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.image.TemplateMatch;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D5: perception escalates cheapest first, exits early, caches by region, and never fences outside human mode. */
class PerceptionTest {
    private static final double[] VIEW = {0, 0, 100, 100};

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(OcrHook.PROPERTY);
        OcrHook.resetForTests();
        ViewMask.resetForTests();
        Perception.resetForTests();
    }

    @Test
    void stagesRunInOrderAndStopAtTheFirstThatDecides() {
        DomNode root = HtmlTree.parse("""
                <html data-viewport-w="100" data-viewport-h="100"><body>
                <button id="plain" data-x="10" data-y="10" data-w="40" data-h="20" data-paint-order="1">Plain</button>
                <button id="away" data-x="300" data-y="10" data-w="40" data-h="20" data-paint-order="2">Away</button>
                </body></html>""");
        List<DomNode> elements = elements(root);
        Perception.Decision away = Perception.decide(find(root, "away"), elements, VIEW, null);
        assertFalse(away.perceived());
        assertEquals(Perception.VIEWPORT, away.stage());
        assertEquals("viewport", away.path(), "nothing past the viewport ran");
        Perception.Decision plain = Perception.decide(find(root, "plain"), elements, VIEW, null);
        assertTrue(plain.perceived());
        assertEquals(Perception.HIT_TEST, plain.stage());
        assertEquals("viewport>hit-test", plain.path(), "a clear hit test exits before clip, contrast, and OCR");
    }

    @Test
    void multiPointHitTestSeesPartialCoverAndUnionCover() {
        DomNode root = HtmlTree.parse("""
                <html data-viewport-w="100" data-viewport-h="100"><body>
                <button id="half" data-x="10" data-y="10" data-w="40" data-h="20" data-paint-order="1">Half</button>
                <div id="left" data-x="10" data-y="10" data-w="20" data-h="20" data-paint-order="5" data-opaque="true"></div>
                <button id="split" data-x="10" data-y="50" data-w="40" data-h="20" data-paint-order="2">Split</button>
                <div id="a" data-x="10" data-y="50" data-w="20" data-h="20" data-paint-order="6" data-opaque="true"></div>
                <div id="b" data-x="30" data-y="50" data-w="20" data-h="20" data-paint-order="7" data-opaque="true"></div>
                </body></html>""");
        List<DomNode> elements = elements(root);
        Perception.Decision half = Perception.decide(find(root, "half"), elements, VIEW, null);
        assertTrue(half.perceived(), "a partly covered element is still seen");
        assertEquals(Perception.CLIP, half.stage(), "partial cover escalates to the clip/size check");
        Perception.Decision split = Perception.decide(find(root, "split"), elements, VIEW, null);
        assertFalse(split.perceived(), "two covers that together hide every probe point hide the element");
        assertEquals(Perception.HIT_TEST, split.stage());
    }

    @Test
    void lowContrastUsesOcrOnlyWhenNeededAndTheRepeatIsACacheHit() throws Exception {
        DomNode root = HtmlTree.parse("""
                <html data-viewport-w="100" data-viewport-h="100"><body style="background-color:#ffffff">
                <img id="icon" alt="" data-x="10" data-y="10" data-w="2" data-h="2" data-paint-order="1" style="color:#fefefe">
                <span id="ghost" data-x="10" data-y="40" data-w="40" data-h="20" data-paint-order="2" style="color:#ffffff">ghost</span>
                </body></html>""");
        List<DomNode> elements = elements(root);
        Perception.Decision ghost = Perception.decide(find(root, "ghost"), elements, VIEW, null);
        assertFalse(ghost.perceived(), "white on white with no crop is not perceived");
        assertEquals(Perception.CONTRAST, ghost.stage());

        TemplateMatch.Gray crop = new TemplateMatch.Gray(2, 2, new int[]{10, 20, 30, 40});
        Path runtime = Files.createTempFile("pkb-ocr", ".txt");
        Files.writeString(runtime, "pkb-ocr 1\n" + OcrHook.cacheKey(crop) + " Save\n");
        System.setProperty(OcrHook.PROPERTY, runtime.toString());
        Perception.Crops crops = node -> crop;
        Perception.Decision icon = Perception.decide(find(root, "icon"), elements, VIEW, crops);
        assertTrue(icon.perceived(), "OCR read text on the crop");
        assertEquals(Perception.OCR, icon.stage());
        assertEquals("viewport>hit-test>clip>contrast>ocr", icon.path());
        int reads = OcrHook.runtimeReads();
        int evaluations = Perception.evaluations();
        Perception.Decision again = Perception.decide(find(root, "icon"), elements, VIEW, crops);
        assertTrue(again.cached());
        assertEquals(Perception.OCR, again.stage());
        assertEquals(reads, OcrHook.runtimeReads(), "the repeat does not read OCR again");
        assertEquals(evaluations, Perception.evaluations(), "the repeat is a region cache hit");
        assertEquals(1, Perception.cacheHits());
        Files.deleteIfExists(runtime);
    }

    @Test
    void humanMaskReportsTheStageAndNothingIsFencedOutsideHumanMode() {
        DomNode root = HtmlTree.parse("""
                <html data-viewport-w="100" data-viewport-h="100"><body>
                <button id="split" data-x="10" data-y="50" data-w="40" data-h="20" data-paint-order="2">Split</button>
                <div id="a" data-x="10" data-y="50" data-w="20" data-h="20" data-paint-order="6" data-opaque="true"></div>
                <div id="b" data-x="30" data-y="50" data-w="20" data-h="20" data-paint-order="7" data-opaque="true"></div>
                <button id="open" data-x="10" data-y="10" data-w="40" data-h="20" data-paint-order="1">Open</button>
                </body></html>""");
        assertEquals(List.of("split", "open"), ids(query(root, null)), "no exposure: every node, no fence");
        assertEquals(List.of("split", "open"), ids(query(root, "full")));
        ObjectNode human = query(root, "human");
        assertEquals(List.of("open"), ids(human));
        assertEquals("hit-test", human.path("perceived").path(0).path("perceived").asText(), human.toString());
    }

    private static ObjectNode query(DomNode root, String exposure) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("css", "button");
        flags.put("explain", "true");
        if (exposure != null) flags.put("exposure", exposure);
        return Query.run(root, flags);
    }

    private static List<String> ids(ObjectNode json) {
        List<String> ids = new ArrayList<>();
        json.path("nodes").forEach(node -> ids.add(node.path("attributes").path("id").asText()));
        return ids;
    }

    private static List<DomNode> elements(DomNode root) {
        List<DomNode> all = new ArrayList<>();
        walk(root, all);
        return all;
    }

    private static void walk(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1) into.add(node);
        for (DomNode child : node.children) walk(child, into);
    }

    private static DomNode find(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }
}
