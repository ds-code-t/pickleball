package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat bubbles stay in an open log whose pane has no snapshot box. */
class CollabRowsTest {
    @Test
    void openChatPaneWithNoBoxStillYieldsTheMessage() {
        DomNode root = new DomNode();
        root.localName = "html";
        DomNode layer = new DomNode();
        layer.localName = "pkb-layer";
        DomNode pane = new DomNode();
        pane.localName = "div";
        pane.displayed = false;
        pane.computed.put("display", "flex");
        DomNode bubble = message("m1", "agent", "shown", "hello human");
        pane.children.add(bubble);
        layer.shadowRoots.add(pane);
        root.children.add(layer);

        List<Map<String, Object>> rows = CollabRows.messages(root, 5);
        assertEquals(1, rows.size());
        assertEquals("m1 agent shown", rows.get(0).get("msg"));
        assertEquals("hello human", rows.get(0).get("name"));
    }

    @Test
    void guideTabHidesTheLogAndTheMessageStays() {
        DomNode root = new DomNode();
        root.localName = "html";
        DomNode card = new DomNode();
        card.localName = "section";
        card.attributes.put("data-pkb-panel", "");
        DomNode pane = new DomNode();
        pane.localName = "div";
        pane.attributes.put("hidden", "");
        pane.attributes.put("data-pkb-purpose", "panel chat-log");
        pane.attributes.put("role", "log");
        pane.children.add(message("m5", "agent", "shown", "hello human"));
        card.children.add(pane);
        root.children.add(card);

        List<Map<String, Object>> rows = CollabRows.messages(root, 5);
        assertEquals(1, rows.size());
        assertEquals("m5 agent shown", rows.get(0).get("msg"));

        DomNode minimized = new DomNode();
        minimized.localName = "section";
        minimized.attributes.put("hidden", "");
        minimized.attributes.put("data-pkb-panel", "");
        minimized.children.add(pane);
        DomNode closed = new DomNode();
        closed.localName = "html";
        closed.children.add(minimized);
        assertTrue(CollabRows.messages(closed, 5).isEmpty());
    }

    @Test
    void explicitHideDropsTheMessage() {
        DomNode root = new DomNode();
        root.localName = "html";
        DomNode card = new DomNode();
        card.localName = "div";
        card.attributes.put("hidden", "");
        card.children.add(message("m2", "agent", "shown", "hidden"));
        DomNode collapsed = new DomNode();
        collapsed.localName = "div";
        collapsed.states.add("collapsed");
        collapsed.children.add(message("m3", "agent", "shown", "collapsed"));
        DomNode styled = new DomNode();
        styled.localName = "div";
        styled.attributes.put("style", "display: none");
        styled.children.add(message("m4", "agent", "shown", "styled"));
        root.children.add(card);
        root.children.add(collapsed);
        root.children.add(styled);

        assertTrue(CollabRows.messages(root, 5).isEmpty());
    }

    private static DomNode message(String id, String author, String status, String text) {
        DomNode bubble = new DomNode();
        bubble.localName = "div";
        bubble.attributes.put("data-pkb-msg-id", id);
        bubble.attributes.put("data-pkb-msg-author", author);
        bubble.attributes.put("data-pkb-msg-status", status);
        DomNode body = new DomNode();
        body.nodeType = 3;
        body.nodeValue = text;
        bubble.children.add(body);
        return bubble;
    }
}
