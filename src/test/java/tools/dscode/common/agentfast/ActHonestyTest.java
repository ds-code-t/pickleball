package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;
import dev.domengine.tree.DomNode;
import tools.dscode.common.domoperations.MaskQuiet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Click honesty that does not need Chrome: a stuck mask fails, a signature change is applied. */
class ActHonestyTest {
    @Test
    void aStuckCoveringMaskIsNotAMiss() {
        assertTrue(TargetVerb.maskStillCovering(new MaskQuiet.Wait(800, false)));
        assertFalse(TargetVerb.maskStillCovering(new MaskQuiet.Wait(800, true)));
        assertFalse(TargetVerb.maskStillCovering(new MaskQuiet.Wait(0, false)));
        assertFalse(TargetVerb.maskStillCovering(null));
    }

    @Test
    void elementStateChangeIsAppliedAndABlankReadIsNot() {
        assertTrue(TargetVerb.signatureApplied("Count\n\n\n\n\n\n", "Count\ntrue\n\n\n\n\n"));
        assertFalse(TargetVerb.signatureApplied("Count\n\n\n\n\n\n", "Count\n\n\n\n\n\n"));
        assertFalse(TargetVerb.signatureApplied("", "Count\ntrue"));
        assertFalse(TargetVerb.signatureApplied("Count", ""));
        assertFalse(TargetVerb.signatureApplied(null, "Count"));
    }

    @Test
    void aDisplayedNodeIsHoveredWhenTheProbeIsBlankAndAHiddenNodeIsNot() {
        DomNode shown = new DomNode();
        assertTrue(TargetVerb.hoverReachable("1", null));
        assertTrue(TargetVerb.hoverReachable("1", shown));
        assertTrue(TargetVerb.hoverReachable(null, shown));
        assertTrue(TargetVerb.hoverReachable("0", shown));
        shown.displayed = false;
        assertFalse(TargetVerb.hoverReachable(null, shown));
        assertFalse(TargetVerb.hoverReachable("0", shown));
        DomNode hidden = new DomNode();
        hidden.states.add("hidden");
        assertFalse(TargetVerb.hoverReachable(null, hidden));
        assertTrue(TargetVerb.hoverReachable("1", hidden));
        assertFalse(TargetVerb.hoverReachable(null, null));
    }

    @Test
    void sameOriginIframeHoverStaysInThatDocument() {
        DomNode frame = new DomNode();
        frame.iframe = true;
        DomNode child = new DomNode();
        child.parent = frame;
        assertTrue(TargetVerb.hoverInPlaceFrame(child));
        child.cdpSession = "child-session";
        assertFalse(TargetVerb.hoverInPlaceFrame(child));
        DomNode top = new DomNode();
        assertFalse(TargetVerb.hoverInPlaceFrame(top));
        assertFalse(TargetVerb.hoverInPlaceFrame(null));
        frame.shadowRoot = true;
        frame.shadowMode = "closed";
        DomNode closed = new DomNode();
        closed.parent = frame;
        assertFalse(TargetVerb.hoverInPlaceFrame(closed));
    }

    @Test
    void aCardButtonThatRemovesItsCardIsApplied() {
        // connected|shown|surface mutations|focus
        assertTrue(TargetVerb.probeMoved("1|1|0|self", "0|0|3|"));
        assertTrue(TargetVerb.probeMoved("1|1|0|self", "1|0|0|self"));
        assertTrue(TargetVerb.probeMoved("1|1|0|self", "1|1|2|self"));
        assertTrue(TargetVerb.probeMoved("1|1|0|self", ""), "a node that no longer resolves is gone");
        assertTrue(TargetVerb.probeMoved("1|1|-|self", "1|1|-|INPUT#email."));
        assertFalse(TargetVerb.probeMoved("1|1|0|self", "1|1|0|self"), "nothing moved is a real false");
        assertFalse(TargetVerb.probeMoved("1|1|-|", "1|1|-|self"), "focus landing on the clicked node is not a change");
        assertFalse(TargetVerb.probeMoved("", "0|0|0|"), "no before read cannot prove anything");
        assertFalse(TargetVerb.probeMoved(null, null));
    }

    @Test
    void aKeyThatMovesFocusOrValueIsApplied() {
        assertTrue(TargetVerb.pressMoved("a\n\n\n\n\n\nx", "a\n\n\n\n\n\nx", "1|1|-|INPUT#second."));
        assertTrue(TargetVerb.pressMoved("a\n\n\n\n\n\nx", "a\n\n\n\n\n\nxy", "1|1|-|self"));
        assertFalse(TargetVerb.pressMoved("a\n\n\n\n\n\nx", "a\n\n\n\n\n\nx", "1|1|-|self"));
        assertFalse(TargetVerb.pressMoved("a", "a", ""));
    }
}
