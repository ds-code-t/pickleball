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

    /** Same hit plus a five-space geometry object whose document box matches the AABB. */
    public static CompositeWebElement withGeometry(String id, String tag, String cachedText, int x, int y, int width, int height) {
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
        ObjectNode geometry = node.putObject("geometry");
        geometry.put("closed_shadow", false);
        geometry.put("closed_shadow_limit", "Page script hit-testing retargets to the host. Corners are CDP or stored quads, not page-JS elementFromPoint.");
        space(geometry, "document_css_px", x, y, width, height);
        space(geometry, "visual_viewport_relative", x - 2, y - 3, width, height);
        space(geometry, "layout_viewport_relative", x - 4, y - 1, width, height);
        space(geometry, "nearest_scrollport_relative", 1, 2, width, height);
        space(geometry, "frame_document_relative", x, y, width, height);
        ObjectNode port = geometry.putObject("nearest_scrollport");
        port.put("kind", "viewport");
        port.put("id", "main");
        port.put("origin", "frame-scroll");
        return new CompositeWebElement(new CompositeSelector(null, "#" + id, null, "web", null, "#" + id), node, 0);
    }

    private static void space(ObjectNode geometry, String name, int x, int y, int width, int height) {
        ObjectNode space = geometry.putObject(name);
        space.put("kind", "quad");
        var corners = space.putArray("corners");
        corner(corners, x, y);
        corner(corners, x + width, y);
        corner(corners, x + width, y + height);
        corner(corners, x, y + height);
        space.put("x", x);
        space.put("y", y);
        space.put("width", width);
        space.put("height", height);
    }

    private static void corner(com.fasterxml.jackson.databind.node.ArrayNode corners, int x, int y) {
        var corner = corners.addArray();
        corner.add(x);
        corner.add(y);
    }
}
