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
import java.util.Locale;
import java.util.Map;

/**
 * Thin CDP adapter. {@code DOM.getDocument} with pierce, plus {@code Page.getFrameTree}.
 * Styles and border boxes are read only for nodes a caller names.
 */
public final class LiveTree {
    public final DomNode root;
    public final List<Frame> frames;
    /** Loader id of the top frame, when {@code Page.getFrameTree} reported one. */
    public String loaderId = "";

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
        graftOutOfProcess(connection, root, timeout);
        String top = frames.isEmpty() ? "main" : frames.get(0).id;
        List<String> children = new ArrayList<>();
        for (int i = 1; i < frames.size(); i++) children.add(frames.get(i).id);
        root.assignFrames(top, children);
        EngineLog.info("walk", "document nodes=" + root.count() + " frames=" + frames.size());
        LiveTree tree = new LiveTree(root, List.copyOf(frames));
        JsonNode topFrame = frameTree.path("frameTree").path("frame");
        tree.loaderId = topFrame.path("loaderId").asText("");
        return tree;
    }

    /**
     * One {@code DOMSnapshot.captureSnapshot} plus {@code Page.getLayoutMetrics}.
     * Boxes are joined by {@code backendNodeId}. {@code document_css_px}, {@code layout_viewport},
     * {@code visual_viewport} and {@code frame_document} are computed separately.
     */
    public Layout readLayout(CdpConnection connection) {
        ObjectNode params = Jsons.object();
        ArrayNode styles = params.putArray("computedStyles");
        for (String name : List.of(
                "display", "visibility", "opacity", "color", "background-color",
                "font-size", "font-weight", "cursor", "pointer-events")) {
            styles.add(name);
        }
        params.put("includeDOMRects", true);
        params.put("includePaintOrder", true);
        JsonNode snapshot = connection.call("DOMSnapshot.captureSnapshot", params, Duration.ofSeconds(15));
        JsonNode metrics = connection.call("Page.getLayoutMetrics", Jsons.object(), Duration.ofSeconds(8));
        Layout layout = layoutOf(metrics);
        applySnapshot(root, snapshot);
        place(root, 0, 0, layout);
        EngineLog.info("snapshot", "layout nodes=" + (root == null ? 0 : root.count()));
        return layout;
    }

    public record Layout(
            double cssWidth,
            double cssHeight,
            double scrollX,
            double scrollY,
            double offsetX,
            double offsetY,
            double scale,
            double devicePixelRatio
    ) {
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
                        String value = entry.path("value").asText("");
                        if (!name.isEmpty()) node.computed.put(name.toLowerCase(Locale.ROOT), value);
                        if ("display".equals(name) && "none".equals(value)) node.displayed = false;
                        if ("visibility".equals(name) && ("hidden".equals(value) || "collapse".equals(value))) node.displayed = false;
                        if ("opacity".equals(name) && ("0".equals(value) || value.startsWith("0.0"))) node.displayed = false;
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
                        node.visualX = quad[0];
                        node.visualY = quad[1];
                        node.hasVisual = true;
                        node.x = quad[0] + scrollX;
                        node.y = quad[1] + scrollY;
                        node.width = quad[2];
                        node.height = quad[3];
                        node.boxRead = true;
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

    /**
     * Same-origin frames arrive as {@code contentDocument}. A cross-origin iframe has no document
     * on this target, so the child target is attached flattened and its document is grafted on.
     */
    private static void graftOutOfProcess(CdpConnection connection, DomNode root, Duration timeout) {
        List<DomNode> empty = new ArrayList<>();
        collectEmptyFrames(root, empty);
        if (empty.isEmpty()) return;
        JsonNode listed;
        try {
            ObjectNode auto = Jsons.object();
            auto.put("autoAttach", true);
            auto.put("waitForDebuggerOnStart", false);
            auto.put("flatten", true);
            connection.call("Target.setAutoAttach", auto, timeout);
            listed = connection.call("Target.getTargets", Jsons.object(), timeout);
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "targets " + failure.getMessage());
            return;
        }
        JsonNode infos = listed.path("targetInfos");
        if (!infos.isArray()) return;
        boolean[] used = new boolean[empty.size()];
        for (JsonNode info : infos) {
            if (!"iframe".equals(info.path("type").asText(""))) continue;
            String targetId = info.path("targetId").asText("");
            String url = info.path("url").asText("");
            int slot = matchFrame(empty, used, url);
            if (slot < 0 || targetId.isBlank()) continue;
            try {
                ObjectNode attach = Jsons.object();
                attach.put("targetId", targetId);
                attach.put("flatten", true);
                JsonNode session = connection.call("Target.attachToTarget", attach, timeout);
                String sessionId = session.path("sessionId").asText("");
                if (sessionId.isBlank()) continue;
                ObjectNode params = Jsons.object();
                params.put("pierce", true);
                params.put("depth", -1);
                JsonNode child = connection.call("DOM.getDocument", params, sessionId, timeout);
                DomNode document = DomNode.fromCdp(child.get("root"));
                empty.get(slot).children.add(document);
                used[slot] = true;
                EngineLog.info("frame", "oopif " + targetId);
            } catch (RuntimeException failure) {
                EngineLog.info("frame", "oopif " + failure.getMessage());
            }
        }
    }

    private static void collectEmptyFrames(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.iframe && !hasDocument(node)) into.add(node);
        for (DomNode child : node.children) collectEmptyFrames(child, into);
    }

    private static boolean hasDocument(DomNode frame) {
        for (DomNode child : frame.children) if (child.nodeType == 9) return true;
        return false;
    }

    private static int matchFrame(List<DomNode> frames, boolean[] used, String url) {
        int fallback = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (used[i]) continue;
            String src = frames.get(i).attributes.getOrDefault("src", "");
            if (!src.isBlank() && url != null && (url.equals(src) || url.endsWith(src) || url.contains(src))) return i;
            if (fallback < 0) fallback = i;
        }
        return fallback;
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

    private static Layout layoutOf(JsonNode metrics) {
        JsonNode css = first(metrics, "cssLayoutViewport", "layoutViewport");
        JsonNode visual = first(metrics, "cssVisualViewport", "visualViewport");
        double width = css.path("clientWidth").asDouble(0);
        double height = css.path("clientHeight").asDouble(0);
        if (width <= 0) width = 1280;
        if (height <= 0) height = 800;
        double scale = visual.path("scale").asDouble(1);
        if (!(scale > 0)) scale = 1;
        double dpr = metrics.path("devicePixelRatio").asDouble(1);
        if (!(dpr > 0)) dpr = 1;
        return new Layout(
                width,
                height,
                css.path("pageX").asDouble(0),
                css.path("pageY").asDouble(0),
                visual.path("offsetX").asDouble(0),
                visual.path("offsetY").asDouble(0),
                scale,
                dpr
        );
    }

    private static JsonNode first(JsonNode node, String... names) {
        if (node == null) return Jsons.object();
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.isMissingNode()) return value;
        }
        return Jsons.object();
    }

    private static void applySnapshot(DomNode root, JsonNode snapshot) {
        JsonNode strings = snapshot.path("strings");
        JsonNode documents = snapshot.path("documents");
        if (!documents.isArray()) return;
        Map<Long, DomNode> byId = new java.util.HashMap<>();
        indexIds(root, byId);
        for (JsonNode document : documents) {
            JsonNode nodes = document.path("nodes");
            JsonNode ids = nodes.path("backendNodeId");
            JsonNode layout = document.path("layout");
            JsonNode indexes = layout.path("nodeIndex");
            JsonNode bounds = layout.path("bounds");
            JsonNode styleRows = layout.path("styles");
            if (!indexes.isArray() || !bounds.isArray()) continue;
            int rows = Math.min(indexes.size(), bounds.size());
            for (int i = 0; i < rows; i++) {
                int nodeIndex = indexes.get(i).asInt(-1);
                if (nodeIndex < 0 || nodeIndex >= ids.size()) continue;
                DomNode node = byId.get(ids.get(nodeIndex).asLong());
                if (node == null) continue;
                double[] box = rectangle(bounds.get(i));
                if (box == null) continue;
                node.hasFrameDocument = true;
                node.frameDocumentX = box[0];
                node.frameDocumentY = box[1];
                node.frameDocumentWidth = box[2];
                node.frameDocumentHeight = box[3];
                if (styleRows.isArray() && i < styleRows.size()) applyStyles(node, styleRows.get(i), strings);
            }
        }
    }

    private static void applyStyles(DomNode node, JsonNode row, JsonNode strings) {
        if (row == null || !row.isArray()) return;
        String[] names = {
                "display", "visibility", "opacity", "color", "background-color",
                "font-size", "font-weight", "cursor", "pointer-events"
        };
        for (int i = 0; i < row.size() && i < names.length; i++) {
            String value = stringAt(strings, row.get(i).asInt(-1));
            if (value.isEmpty()) continue;
            node.computed.put(names[i], value);
            if ("display".equals(names[i]) && "none".equals(value)) node.displayed = false;
            if ("visibility".equals(names[i]) && ("hidden".equals(value) || "collapse".equals(value))) node.displayed = false;
            if ("opacity".equals(names[i]) && ("0".equals(value) || value.startsWith("0.0"))) node.displayed = false;
        }
    }

    private static String stringAt(JsonNode strings, int index) {
        if (strings == null || index < 0 || index >= strings.size()) return "";
        return strings.get(index).asText("");
    }

    private static double[] rectangle(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;
        if (raw.isArray() && raw.size() >= 4 && raw.get(0).isNumber()) {
            return new double[] {raw.get(0).asDouble(), raw.get(1).asDouble(), raw.get(2).asDouble(), raw.get(3).asDouble()};
        }
        if (raw.isArray() && raw.size() >= 1 && raw.get(0).isArray()) return rectangle(raw.get(0));
        if (raw.isObject()) {
            return new double[] {
                    raw.path("x").asDouble(), raw.path("y").asDouble(),
                    raw.path("width").asDouble(), raw.path("height").asDouble()
            };
        }
        return null;
    }

    private static void place(DomNode node, double originX, double originY, Layout layout) {
        if (node == null) return;
        if (node.hasFrameDocument) {
            double x = originX + node.frameDocumentX;
            double y = originY + node.frameDocumentY;
            node.x = x;
            node.y = y;
            node.width = node.frameDocumentWidth;
            node.height = node.frameDocumentHeight;
            node.boxRead = true;
            node.hasBox = node.width > 0 && node.height > 0;
            node.hasLayoutViewport = true;
            node.layoutViewportX = x - layout.scrollX;
            node.layoutViewportY = y - layout.scrollY;
            node.layoutViewportWidth = node.width;
            node.layoutViewportHeight = node.height;
            double scale = layout.scale <= 0 ? 1 : layout.scale;
            node.hasVisual = true;
            node.visualX = (node.layoutViewportX - layout.offsetX) * scale;
            node.visualY = (node.layoutViewportY - layout.offsetY) * scale;
            node.visualWidth = node.width * scale;
            node.visualHeight = node.height * scale;
        }
        double nextX = originX;
        double nextY = originY;
        if (node.iframe && node.hasFrameDocument) {
            nextX = node.x;
            nextY = node.y;
        }
        for (DomNode child : node.children) place(child, nextX, nextY, layout);
        for (DomNode shadow : node.shadowRoots) place(shadow, originX, originY, layout);
    }

    private static void indexIds(DomNode node, Map<Long, DomNode> into) {
        if (node == null) return;
        if (node.backendNodeId > 0) into.put(node.backendNodeId, node);
        for (DomNode child : node.children) indexIds(child, into);
        for (DomNode shadow : node.shadowRoots) indexIds(shadow, into);
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
