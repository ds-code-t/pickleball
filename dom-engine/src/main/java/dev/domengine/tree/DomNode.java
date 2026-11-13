package dev.domengine.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One node in a pierced document tree. Shadow roots and iframe documents are children of the walker, not a second model. */
public final class DomNode {
    public long nodeId;
    public long backendNodeId;
    public int nodeType;
    public String nodeName = "";
    public String localName = "";
    public String nodeValue = "";
    public String frameId = "main";
    public boolean iframe;
    public boolean shadowRoot;
    public String shadowMode = "";
    public final Map<String, String> attributes = new LinkedHashMap<>();
    public final List<DomNode> children = new ArrayList<>();
    public final List<DomNode> shadowRoots = new ArrayList<>();
    public double x;
    public double y;
    public double width;
    public double height;
    public boolean hasBox;
    public String role = "";
    public String accessibleName = "";
    public boolean displayed = true;
    public DomNode parent;
    public double frameX;
    public double frameY;
    public boolean hasVisual;
    public double visualX;
    public double visualY;
    /** Computed longhands from the browser. Empty means the style attribute is the offline source. */
    public final Map<String, String> computed = new LinkedHashMap<>();
    /** Absolute index-qualified XPath inside the frame document, or the shadow-root-relative path. */
    public String xpath = "";
    /** Host XPaths from the document down to this shadow tree, separated by ` >> `. Empty in light DOM. */
    public String shadowPath = "";
    /** Root-to-innermost section ids, separated by `/`. */
    public String sectionPath = "";
    public boolean capped;
    public String capReason = "";
    /** D10 row-state names: hidden, disabled, checked, selected, readonly, required, expanded, focused. */
    public final List<String> states = new ArrayList<>();
    public Paint paint;
    public boolean hasLayoutViewport;
    public double layoutViewportX;
    public double layoutViewportY;
    public double layoutViewportWidth;
    public double layoutViewportHeight;

    /** Colour and image sample. Null until a producer or the pre-map fills it. */
    public static final class Paint {
        public String color = "";
        public String background = "";
        public double contrast = Double.NaN;
        public String meanRgb = "";
        public long dhash;
        public boolean hasDhash;
    }

    public String tag() {
        if (localName != null && !localName.isBlank()) return localName.toLowerCase();
        return nodeName == null ? "" : nodeName.toLowerCase();
    }

    public String id() {
        return frameId + ":" + backendNodeId;
    }

    public static DomNode fromCdp(JsonNode raw) {
        DomNode node = new DomNode();
        if (raw == null || raw.isNull()) return node;
        node.nodeId = raw.path("nodeId").asLong();
        node.backendNodeId = raw.path("backendNodeId").asLong();
        node.nodeType = raw.path("nodeType").asInt();
        node.nodeName = raw.path("nodeName").asText("");
        node.localName = raw.path("localName").asText("");
        node.nodeValue = raw.path("nodeValue").asText("");
        JsonNode attrs = raw.get("attributes");
        if (attrs != null && attrs.isArray()) {
            for (int i = 0; i + 1 < attrs.size(); i += 2) {
                node.attributes.put(attrs.get(i).asText(""), attrs.get(i + 1).asText(""));
            }
        }
        if ("IFRAME".equalsIgnoreCase(node.nodeName) || "FRAME".equalsIgnoreCase(node.nodeName)) {
            node.iframe = true;
        }
        if (raw.has("shadowRootType")) {
            node.shadowRoot = true;
            node.shadowMode = raw.path("shadowRootType").asText("open");
        }
        JsonNode children = raw.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) node.children.add(fromCdp(child));
        }
        JsonNode content = raw.get("contentDocument");
        if (content != null && !content.isNull() && !content.isMissingNode()) {
            boolean already = false;
            for (DomNode child : node.children) {
                if (child.nodeType == 9) already = true;
            }
            if (!already) node.children.add(fromCdp(content));
        }
        JsonNode shadows = raw.get("shadowRoots");
        if (shadows != null && shadows.isArray()) {
            for (JsonNode shadow : shadows) {
                DomNode root = fromCdp(shadow);
                root.shadowRoot = true;
                if (root.shadowMode.isBlank()) root.shadowMode = shadow.path("shadowRootType").asText("open");
                node.shadowRoots.add(root);
            }
        }
        return node;
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("id", id());
        node.put("frame_id", frameId);
        node.put("backend_node_id", backendNodeId);
        node.put("node_id", nodeId);
        node.put("node_type", nodeType);
        node.put("node_name", nodeName);
        node.put("local_name", localName);
        if (iframe) node.put("iframe", true);
        if (shadowRoot) {
            node.put("shadow_root", true);
            node.put("shadow_mode", shadowMode);
        }
        if (!attributes.isEmpty()) {
            ObjectNode attrs = node.putObject("attributes");
            attributes.forEach(attrs::put);
        }
        if (hasBox) {
            ObjectNode bounds = node.putObject("bounds");
            bounds.put("x", x);
            bounds.put("y", y);
            bounds.put("width", width);
            bounds.put("height", height);
        }
        if (!role.isBlank()) node.put("role", role);
        if (!accessibleName.isBlank()) node.put("name", accessibleName);
        ArrayNode kids = node.putArray("children");
        for (DomNode child : children) kids.add(child.toJson());
        if (!shadowRoots.isEmpty()) {
            ArrayNode shadows = node.putArray("shadow_roots");
            for (DomNode shadow : shadowRoots) shadows.add(shadow.toJson());
        }
        return node;
    }

    public void linkParents() {
        for (DomNode child : children) {
            child.parent = this;
            child.linkParents();
        }
        for (DomNode shadow : shadowRoots) {
            shadow.parent = this;
            shadow.linkParents();
        }
    }

    public boolean containsNode(DomNode other) {
        DomNode cursor = other == null ? null : other.parent;
        while (cursor != null) {
            if (cursor == this) return true;
            cursor = cursor.parent;
        }
        return false;
    }

    public static void renumber(DomNode root) {
        long[] next = {1};
        stamp(root, next);
    }

    private static void stamp(DomNode node, long[] next) {
        if (node == null) return;
        node.backendNodeId = next[0];
        node.nodeId = next[0];
        next[0]++;
        for (DomNode child : node.children) stamp(child, next);
        for (DomNode shadow : node.shadowRoots) stamp(shadow, next);
    }

    public int count() {
        int total = 1;
        for (DomNode child : children) total += child.count();
        for (DomNode shadow : shadowRoots) total += shadow.count();
        return total;
    }

    public void assignFrames(String frameId, List<String> childFrames) {
        this.frameId = frameId == null || frameId.isBlank() ? "main" : frameId;
        if (iframe) {
            String child = childFrames.isEmpty() ? this.frameId : childFrames.remove(0);
            for (DomNode childNode : children) childNode.assignFrames(child, childFrames);
            return;
        }
        for (DomNode child : children) child.assignFrames(this.frameId, childFrames);
        for (DomNode shadow : shadowRoots) shadow.assignFrames(this.frameId, childFrames);
    }
}
