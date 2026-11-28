package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28: cheap checks behind the live fixes for items 10, 16 and 20. */
class HcrRoundFourTest {
    @Test
    void frameUsesTheLiveBrowserLikeTheOtherPageVerbs() {
        assertTrue(TargetVerb.needsLiveTarget("frame"), "frame with no --target must default to browser:auto (HCR-16)");
        assertTrue(TargetVerb.needsLiveTarget("snap"));
        assertFalse(TargetVerb.needsLiveTarget("attach"));
    }

    @Test
    void aSurfaceEventDuringTheSettleCountsAsApplied() {
        AtomicLong events = new AtomicLong(4);
        long started = System.nanoTime();
        assertTrue(TargetVerb.surfaceMoved(4, () -> System.nanoTime() - started > 60_000_000L ? events.incrementAndGet() : events.get(), 300),
                "a chat send or guide step pushed after Enter is an applied key (HCR-20)");
        assertFalse(TargetVerb.surfaceMoved(4, () -> 4, 50), "no surface event is not applied");
    }

    @Test
    void aCssSelectorIsAcceptedAsAnActRef() {
        assertTrue(TargetVerb.cssRef("#files"), "upload-request names its input #files (HCR-18)");
        assertTrue(TargetVerb.cssRef("[name=upload]"));
        assertTrue(TargetVerb.cssRef("css=input[type=file]"));
        assertFalse(TargetVerb.cssRef("s1.h4"), "a short id is not CSS");
        assertFalse(TargetVerb.cssRef("ref:3"));
        assertFalse(TargetVerb.cssRef("#"));
    }

    @Test
    void theBlockProbeLooksOnlyForPickleballBlockOverlays() {
        assertTrue(TargetVerb.BLOCK_PROBE.contains("data-pkb-block"), TargetVerb.BLOCK_PROBE);
        assertTrue(TargetVerb.BLOCK_PROBE.contains("shadowRoot"), "the layer is a shadow root (HCR-10)");
    }

    @Test
    void aLiveBoxFillsTheActReplyBounds() {
        DomNode node = new DomNode();
        assertTrue(TargetVerb.applyBox(node, "12.5,40,80,24"), "act bounds were 0,0,0,0 (HCR-26)");
        assertTrue(node.hasBox);
        assertEquals(80.0, node.width);
        assertEquals(40.0, node.y);
        assertFalse(TargetVerb.applyBox(new DomNode(), ""), "no live box keeps the node unchanged");
        assertFalse(TargetVerb.applyBox(new DomNode(), "a,b,c,d"));
        assertTrue(TargetVerb.BOX_PROBE.contains("getBoundingClientRect"));
    }

    @Test
    void theMagnifierCopyIsNotASecondTarget() {
        DomNode body = element("body");
        DomNode real = child(body, "button");
        real.attributes.put("data-pkb-anchor", "m1");
        DomNode host = child(body, "div");
        host.attributes.put("data-pkb-ui", "");
        DomNode copy = child(host, "button");
        copy.attributes.put("data-pkb-copy", "live");
        assertTrue(TargetVerb.inertCopy(copy));
        assertFalse(TargetVerb.inertCopy(real));
        assertEquals(List.of(real), TargetVerb.preferPage(List.of(real, copy)), "H act --query=Go was ambiguous (HCR-22)");
        assertEquals(List.of(copy), TargetVerb.preferPage(List.of(copy)), "a copy alone is still found");
    }

    private static DomNode element(String tag) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = tag;
        node.nodeName = tag.toUpperCase();
        node.displayed = true;
        return node;
    }

    private static DomNode child(DomNode parent, String tag) {
        DomNode node = element(tag);
        node.parent = parent;
        return node;
    }
}
