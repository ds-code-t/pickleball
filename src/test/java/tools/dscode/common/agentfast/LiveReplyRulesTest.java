package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveReplyRulesTest {
    @Test
    void changedSnapDoesNotKeepNoVisibleChange() {
        Map<String, Object> body = quiet(true);
        TargetVerb.reconcileApplied(body);
        assertEquals(Boolean.TRUE, body.get("applied"));
        assertEquals(Boolean.TRUE, body.get("visible"));
        assertFalse(body.containsKey("reason"));
    }

    @Test
    void unchangedSnapStaysUnapplied() {
        Map<String, Object> body = quiet(false);
        TargetVerb.reconcileApplied(body);
        assertEquals(Boolean.FALSE, body.get("applied"));
        assertEquals("no visible change", body.get("reason"));
    }

    @Test
    void refusedClickIsNotPromoted() {
        Map<String, Object> body = quiet(true);
        body.put("dispatched", false);
        TargetVerb.reconcileApplied(body);
        assertEquals(Boolean.FALSE, body.get("applied"));
    }

    @Test
    void snapLinesStayShort() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 25; i++) lines.add("x".repeat(200));
        assertEquals(5, TargetVerb.capSnapLines(lines));
        assertEquals(20, lines.size());
        assertTrue(lines.get(0).length() <= 160);
        assertTrue(lines.get(0).endsWith("..."));
    }

    @Test
    void selectedStateReadsAriaAndCurrent() {
        DomNode tab = new DomNode();
        assertFalse(TargetVerb.selectedNode(tab));
        tab.attributes.put("aria-selected", "true");
        assertTrue(TargetVerb.selectedNode(tab));
        DomNode page = new DomNode();
        page.attributes.put("aria-current", "page");
        assertTrue(TargetVerb.selectedNode(page));
        DomNode option = new DomNode();
        option.attributes.put("selected", "selected");
        assertTrue(TargetVerb.selectedNode(option));
    }

    @Test
    void iconPrefixStillMatchesTheVisibleLabel() {
        assertTrue(TargetVerb.labelMatches("account_circle Dan", "Dan"));
        assertTrue(TargetVerb.labelMatches("Dan", "dan"));
        assertFalse(TargetVerb.labelMatches("Danville", "Dan"));
        assertFalse(TargetVerb.labelMatches("Jordan", "Dan"));
        assertFalse(TargetVerb.labelMatches("", "Dan"));
    }

    private static Map<String, Object> quiet(boolean changed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applied", false);
        body.put("dispatched", true);
        body.put("visible", false);
        body.put("reason", "no visible change");
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("changed", changed);
        body.put("snap", snap);
        return body;
    }
}
