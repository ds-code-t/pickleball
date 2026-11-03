package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds a composite hit for snapshot tests. Not a live dom-composite session. */
public final class CompositeElements {
    private CompositeElements() {
    }

    public static CompositeWebElement of(String id, String tag, String cachedText, int x, int y, int width, int height) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", "main:42");
        node.put("node_name", tag);
        node.put("text", cachedText);
        ObjectNode attributes = node.putObject("attributes");
        attributes.put("id", id);
        ObjectNode bounds = node.putObject("bounds");
        bounds.put("x", x);
        bounds.put("y", y);
        bounds.put("width", width);
        bounds.put("height", height);
        return new CompositeWebElement(new CompositeSelector(null, "#" + id, null, "web", null, "#" + id), node, 0);
    }
}
