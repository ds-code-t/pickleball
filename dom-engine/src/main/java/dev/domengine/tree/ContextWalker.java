package dev.domengine.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One walker for the context tree. The top document, each iframe document, and each shadow root
 * are contexts. Locators use this walk, so a nested case is not a second implementation.
 */
public final class ContextWalker {
    public enum Order {
        /** Node, then light children, then shadow roots. */
        DOCUMENT,
        /** Light descendants first, then each shadow. A parent host's shadow is listed before a nested host's shadow. */
        PIERCE
    }

    private ContextWalker() {
    }

    public static void walk(DomNode root, Order order, Consumer<DomNode> visitor) {
        if (root == null || visitor == null) return;
        if (order == Order.PIERCE) pierce(root, visitor);
        else document(root, visitor);
    }

    public static List<DomNode> list(DomNode root, Order order) {
        List<DomNode> nodes = new ArrayList<>();
        walk(root, order, nodes::add);
        return nodes;
    }

    /** Shift iframe contents into top-document CSS pixels. Frame-local boxes stay on {@code frameX}/{@code frameY}. */
    public static void place(DomNode root) {
        place(root, 0, 0, "main");
    }

    private static void document(DomNode node, Consumer<DomNode> visitor) {
        visitor.accept(node);
        if (node.iframe) {
            for (DomNode child : node.children) document(child, visitor);
            return;
        }
        for (DomNode child : node.children) document(child, visitor);
        for (DomNode shadow : node.shadowRoots) document(shadow, visitor);
    }

    private static void pierce(DomNode node, Consumer<DomNode> visitor) {
        visitor.accept(node);
        List<DomNode> shadows = new ArrayList<>();
        collectLightThenShadows(node, visitor, shadows);
        for (DomNode shadow : shadows) pierce(shadow, visitor);
    }

    private static void collectLightThenShadows(DomNode node, Consumer<DomNode> visitor, List<DomNode> shadows) {
        shadows.addAll(node.shadowRoots);
        for (DomNode child : node.children) {
            if (child.shadowRoot) continue;
            visitor.accept(child);
            if (!child.iframe) collectLightThenShadows(child, visitor, shadows);
            else {
                for (DomNode framed : child.children) pierce(framed, visitor);
            }
        }
    }

    private static void place(DomNode node, double originX, double originY, String frameId) {
        node.frameId = frameId == null || frameId.isBlank() ? "main" : frameId;
        if (!node.shadowRoot && node.nodeType != 3 && node.nodeType != 9 && node.nodeType != 11) {
            node.frameX = number(node, "data-x", node.frameX);
            node.frameY = number(node, "data-y", node.frameY);
            double width = number(node, "data-w", node.width);
            double height = number(node, "data-h", node.height);
            if (node.attributes.containsKey("data-w")) node.width = width;
            if (node.attributes.containsKey("data-h")) node.height = height;
            if (node.width > 0 && node.height > 0) node.hasBox = true;
            node.x = node.frameX + originX;
            node.y = node.frameY + originY;
        }
        if (node.iframe) {
            String childFrame = node.attributes.getOrDefault("data-frame",
                    node.attributes.getOrDefault("name", node.attributes.getOrDefault("id", "frame")));
            for (DomNode child : node.children) place(child, node.x, node.y, childFrame);
            return;
        }
        for (DomNode child : node.children) place(child, originX, originY, node.frameId);
        for (DomNode shadow : node.shadowRoots) place(shadow, originX, originY, node.frameId);
    }

    private static double number(DomNode node, String attr, double fallback) {
        String raw = node.attributes.get(attr);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
