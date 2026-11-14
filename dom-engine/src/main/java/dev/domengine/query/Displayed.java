package dev.domengine.query;

import dev.domengine.cdp.CdpConnection;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** WebDriver-style displayed test shared by filter, wait, and the hidden row state. */
public final class Displayed {
    private Displayed() {
    }

    /** Reads computed style when a live connection is bound. No-op offline. */
    public static void prepareBound(List<DomNode> nodes) {
        prepare(LiveXPath.connection(), nodes);
    }

    /** Reads computed style and a border box for the nodes and their ancestors. No-op offline. */
    public static void prepare(CdpConnection connection, List<DomNode> nodes) {
        if (connection == null || nodes == null || nodes.isEmpty()) return;
        List<DomNode> named = new ArrayList<>();
        for (DomNode node : nodes) {
            for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
                if (cursor.nodeId <= 0 || named.contains(cursor)) continue;
                named.add(cursor);
            }
        }
        if (named.isEmpty()) return;
        try {
            connection.call("CSS.enable", dev.domengine.json.Jsons.object(), java.time.Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // Computed style still fails closed for that node.
        }
        new LiveTree(null, List.of()).enrich(connection, named, 0, 0);
    }

    public static boolean isDisplayed(DomNode node) {
        if (node == null || node.nodeType != 1) return false;
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.nodeType != 1 && cursor.nodeType != 9) continue;
            if (!allows(cursor)) return false;
        }
        if (!node.computed.isEmpty() || node.hasBox || node.boxRead) {
            return node.displayed && node.hasBox && node.width > 0 && node.height > 0;
        }
        return true;
    }

    private static boolean zeroBox(String style) {
        boolean width = style.contains("width:0;") || style.contains("width:0px") || style.endsWith("width:0");
        boolean height = style.contains("height:0;") || style.contains("height:0px") || style.endsWith("height:0");
        return width && height;
    }

    private static boolean allows(DomNode node) {
        if (node.attributes.containsKey("hidden")) return false;
        if (!node.displayed && !node.computed.isEmpty()) return false;
        String style = node.attributes.getOrDefault("style", "").toLowerCase(Locale.ROOT).replace(" ", "");
        if (style.contains("display:none")) return false;
        if (style.contains("visibility:hidden") || style.contains("visibility:collapse")) return false;
        if (style.contains("opacity:0")) return false;
        if (zeroBox(style)) return false;
        String display = node.computed.get("display");
        if ("none".equals(display)) return false;
        String visibility = node.computed.get("visibility");
        if ("hidden".equals(visibility) || "collapse".equals(visibility)) return false;
        String opacity = node.computed.get("opacity");
        if ("0".equals(opacity) || "0.0".equals(opacity)) return false;
        return true;
    }
}
