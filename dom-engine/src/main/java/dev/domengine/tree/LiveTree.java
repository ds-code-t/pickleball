package dev.domengine.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin CDP adapter. {@code DOM.getDocument} with pierce, plus {@code Page.getFrameTree}.
 * Styles and border boxes are read only for nodes a caller names.
 */
public final class LiveTree {
    public final DomNode root;
    public final List<Frame> frames;

    public LiveTree(DomNode root, List<Frame> frames) {
        this.root = root;
        this.frames = frames;
    }

    public static LiveTree read(CdpConnection connection) {
        return read(connection, Duration.ofSeconds(10));
    }

    public static LiveTree read(CdpConnection connection, Duration timeout) {
        ObjectNode documentParams = Jsons.object();
        documentParams.put("pierce", true);
        documentParams.put("depth", -1);
        JsonNode document = connection.call("DOM.getDocument", documentParams, timeout);
        JsonNode frameTree = connection.call("Page.getFrameTree", Jsons.object(), timeout);
        List<Frame> frames = new ArrayList<>();
        collectFrames(frameTree.get("frameTree"), frames);
        DomNode root = DomNode.fromCdp(document.get("root"));
        String top = frames.isEmpty() ? "main" : frames.get(0).id;
        List<String> children = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) children.add(frames.get(i).id);
        root.assignFrames(top, children);
        EngineLog.info("walk", "document nodes=" + root.count() + " frames=" + frames.size());
        return new LiveTree(root, List.copyOf(frames));
    }

    /**
     * Computed style, a border box shifted into top-document CSS pixels, and the accessible name
     * for the nodes the caller names.
     */
    public void enrich(CdpConnection connection, List<DomNode> nodes, double scrollX, double scrollY) {
        if (nodes == null) return;
        for (DomNode node : nodes) {
            if (node.nodeId <= 0) continue;
            try {
                ObjectNode styleParams = Jsons.object();
                styleParams.put("nodeId", node.nodeId);
                JsonNode computed = connection.call("CSS.getComputedStyleForNode", styleParams, Duration.ofSeconds(5));
                JsonNode styles = computed.get("computedStyle");
                if (styles != null && styles.isArray()) {
                    for (JsonNode entry : styles) {
                        String name = entry.path("name").asText("");
                        if ("display".equals(name) && "none".equals(entry.path("value").asText(""))) node.displayed = false;
                        if ("visibility".equals(name) && "hidden".equals(entry.path("value").asText(""))) node.displayed = false;
                    }
                }
            } catch (RuntimeException failure) {
                EngineLog.debug("cdp", "style " + node.id() + " " + failure.getMessage());
            }
            try {
                ObjectNode boxParams = Jsons.object();
                boxParams.put("nodeId", node.nodeId);
                JsonNode box = connection.call("DOM.getBoxModel", boxParams, Duration.ofSeconds(5));
                JsonNode model = box.get("model");
                if (model != null && model.has("border")) {
                    double[] quad = quad(model.get("border"));
                    if (quad != null) {
                        node.x = quad[0] + scrollX;
                        node.y = quad[1] + scrollY;
                        node.width = quad[2];
                        node.height = quad[3];
                        node.hasBox = node.width > 0 && node.height > 0;
                    }
                }
            } catch (RuntimeException failure) {
                EngineLog.debug("cdp", "box " + node.id() + " " + failure.getMessage());
            }
            try {
                ObjectNode ax = Jsons.object();
                ax.put("nodeId", node.nodeId);
                JsonNode described = connection.call("Accessibility.getPartialAXTree", axParams(node.nodeId), Duration.ofSeconds(5));
                JsonNode nodesJson = described.get("nodes");
                if (nodesJson != null && nodesJson.isArray() && !nodesJson.isEmpty()) {
                    JsonNode axNode = nodesJson.get(nodesJson.size() - 1);
                    node.role = axNode.path("role").path("value").asText("");
                    node.accessibleName = axNode.path("name").path("value").asText("");
                }
            } catch (RuntimeException failure) {
                EngineLog.debug("cdp", "name " + node.id() + " " + failure.getMessage());
            }
        }
    }

    private static ObjectNode axParams(long nodeId) {
        ObjectNode params = Jsons.object();
        params.put("nodeId", nodeId);
        return params;
    }

    /** Border quad {@code [x1,y1,x2,y2,...]} becomes x, y, width, height in viewport CSS pixels. */
    static double[] quad(JsonNode border) {
        if (border == null || !border.isArray() || border.size() < 8) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 1 < border.size(); i += 2) {
            double x = border.get(i).asDouble();
            double y = border.get(i + 1).asDouble();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new double[] {minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)};
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("nodes", root == null ? 0 : root.count());
        ArrayNode framesJson = node.putArray("frames");
        for (Frame frame : frames) {
            ObjectNode one = framesJson.addObject();
            one.put("id", frame.id);
            one.put("parent_id", frame.parentId);
            one.put("url", frame.url);
        }
        if (root != null) node.set("document", root.toJson());
        return node;
    }

    private static void collectFrames(JsonNode tree, List<Frame> into) {
        if (tree == null || tree.isNull()) return;
        JsonNode frame = tree.get("frame");
        if (frame != null && !frame.isNull()) {
            into.add(new Frame(
                    frame.path("id").asText("main"),
                    frame.path("parentId").asText(""),
                    frame.path("url").asText("")
            ));
        }
        JsonNode children = tree.get("childFrames");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) collectFrames(child, into);
        }
    }

    public record Frame(String id, String parentId, String url) {
    }
}
