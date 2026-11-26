package dev.domengine;

import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P3 query: hidden inputs are not textboxes, and --text is a text stage. */
class ConsumerFixesP3QueryTest {
    @Test
    void hiddenInputIsNotATextbox() {
        DomNode input = new DomNode();
        input.nodeType = 1;
        input.localName = "input";
        input.attributes.put("type", "hidden");
        assertEquals("", Query.roleOf(input));
        DomNode text = new DomNode();
        text.nodeType = 1;
        text.localName = "input";
        text.attributes.put("type", "text");
        assertEquals("textbox", Query.roleOf(text));
    }

    @Test
    void textFlagIsATextStageAfterCss() {
        assertEquals("text=\"Save\"", Query.pipelineOf(Map.of("text", "Save")));
        assertEquals("css=#save", Query.pipelineOf(Map.of("css", "#save", "text", "typed")));
        assertNull(Query.pipelineOf(Map.of()));
    }
}
