package dev.domengine.premap;

import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxCapTest {
    @Test
    void fullPageDivIsCappedForAreaAndADialogQueryStillMatches() {
        DomNode div = element("div", 0, 0, 1200, 700);
        div.attributes.put("onclick", "yes");
        BoxCaps.Decision decision = BoxCaps.defaults().assess(div, 1280, 800, false);
        assertTrue(decision.capped(), decision.reason());
        assertTrue(decision.reason().startsWith("area"), decision.reason());

        DomNode dialog = element("dialog", 40, 40, 1200, 700);
        dialog.role = "dialog";
        Candidate capped = Candidate.of(div, null, List.of(), 1280, 800, BoxCaps.defaults());
        Candidate asked = Candidate.of(dialog, null, List.of(), 1280, 800, BoxCaps.defaults());
        OneMatch.Resolved resolved = OneMatch.resolve(
                List.of(capped, asked),
                new OneMatch.Policy(false, false, 0.15, true, Set.of("dialog"), false)
        );
        assertEquals(asked.handle(), resolved.chosen().handle());
        assertFalse(resolved.chosen().reason().startsWith("rejected"));
    }

    @Test
    void htmlBodyAndDocumentAreNeverTargets() {
        DomNode html = element("html", 0, 0, 100, 100);
        DomNode body = element("body", 0, 0, 100, 100);
        DomNode document = element("#document", 0, 0, 10, 10);
        document.nodeType = 9;
        document.localName = "";
        assertTrue(BoxCaps.alwaysExcluded(html));
        assertTrue(BoxCaps.alwaysExcluded(body));
        assertTrue(BoxCaps.alwaysExcluded(document));
        Candidate candidate = Candidate.of(html, null, List.of(), 1280, 800, BoxCaps.defaults());
        OneMatchException failure = assertThrows(OneMatchException.class,
                () -> OneMatch.resolve(List.of(candidate), OneMatch.Policy.resolve(0.15, Set.of())));
        assertEquals("not found", failure.getMessage());
        assertTrue(failure.candidates().get(0).reason().contains("not a target"), failure.candidates().get(0).reason());
    }

    @Test
    void pixelAndUnknownCaps() {
        BoxCaps caps = BoxCaps.fromEnv("area=900px,width=0.5");
        DomNode node = element("div", 0, 0, 40, 40);
        assertTrue(caps.assess(node, 100, 100, false).capped());
        assertThrows(CdpException.class, () -> BoxCaps.fromEnv("nope=1"));
    }

    private static DomNode element(String tag, double x, double y, double w, double h) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = tag;
        node.nodeName = tag.toUpperCase();
        node.backendNodeId = 7;
        node.x = x;
        node.y = y;
        node.width = w;
        node.height = h;
        node.hasBox = true;
        return node;
    }
}
