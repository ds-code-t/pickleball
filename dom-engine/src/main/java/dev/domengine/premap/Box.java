package dev.domengine.premap;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;

/** A rectangle in one coordinate space. */
public record Box(double x, double y, double width, double height) {
    public static Box of(DomNode node) {
        if (node == null || !node.hasBox) return new Box(0, 0, 0, 0);
        double x = node.hasVisual ? node.visualX : node.x;
        double y = node.hasVisual ? node.visualY : node.y;
        return new Box(x, y, node.width, node.height);
    }

    public boolean present() {
        return width > 0 && height > 0;
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("x", x);
        node.put("y", y);
        node.put("width", width);
        node.put("height", height);
        return node;
    }
}
