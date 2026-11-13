package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.affinity.AffinityScorer;
import dev.domengine.json.Jsons;
import dev.domengine.premap.Box;
import dev.domengine.premap.PreMap;
import dev.domengine.premap.PreMapBuilder;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Assembles one composite snapshot. Every build runs {@link PreMapBuilder}. */
public final class CompositeSnapshotBuilder {
    private static final Set<String> STATE_NAMES = Set.of(
            "hidden", "disabled", "checked", "selected", "readonly", "required", "expanded", "focused"
    );

    private CompositeSnapshotBuilder() {
    }

    public static CompositeSnapshot build(DomNode root, SnapshotContext context) {
        return build(root, context, System.nanoTime());
    }

    static CompositeSnapshot build(DomNode root, SnapshotContext context, long startedNanos) {
        SnapshotContext ctx = context == null ? SnapshotContext.empty() : context;
        if (root != null) root.linkParents();
        XPathIndex.Result index = XPathIndex.assign(root);
        PreMap premap = PreMapBuilder.build(root, ctx);
        List<ObjectNode> nodes = new ArrayList<>();
        collect(root, index, ctx, nodes);
        long duration = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        PngImage image = ctx.screenshot();
        String id = ctx.id().isBlank() ? "snap-" + UUID.randomUUID() : ctx.id();
        String path = "";
        String base64 = "";
        if (image != null) {
            if (!ctx.flags().getOrDefault("out-dir", "").isBlank()) {
                path = java.nio.file.Path.of(ctx.flags().get("out-dir"), "screenshot.png").toString();
            } else if (!ctx.flags().getOrDefault("screenshot", "").isBlank()) {
                path = ctx.flags().get("screenshot");
                base64 = image.base64();
            }
        }
        dev.domengine.log.EngineLog.info("snapshot", "snapshot offline id=" + id + " nodes=" + nodes.size() + " ms=" + duration);
        return new CompositeSnapshot(
                id,
                ctx.sessionId(),
                ctx.docVersion(),
                ctx.snapshotPlatform(),
                ctx.transport(),
                OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                duration,
                ctx.viewportWidth(),
                ctx.viewportHeight(),
                ctx.devicePixelRatio(),
                ctx.scrollX(),
                ctx.scrollY(),
                ctx.visualOffsetX(),
                ctx.visualOffsetY(),
                ctx.visualScale(),
                screenX(root, ctx),
                screenY(root, ctx),
                screenW(root, ctx),
                screenH(root, ctx),
                image == null ? 0 : image.width(),
                image == null ? 0 : image.height(),
                image == null ? 1 : ctx.imageScale(),
                image == null ? "" : image.sha256(),
                path,
                base64,
                ctx.sourceKind(),
                ctx.sourceText(),
                index.frames(),
                nodes,
                premap,
                List.copyOf(ctx.warnings())
        );
    }

    private static String nativePath(DomNode node) {
        for (String key : new String[] {"uia-runtime-id", "ax-path", "atspi-path"}) {
            String value = node.attributes.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return Long.toString(node.backendNodeId);
    }

    private static double flagDouble(SnapshotContext ctx, String key) {
        String raw = ctx.flags().get(key);
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean screenSpace(DomNode root, SnapshotContext ctx) {
        return "true".equals(ctx.flags().get("screen-space")) && root != null && root.hasBox;
    }

    private static double screenX(DomNode root, SnapshotContext ctx) {
        return screenSpace(root, ctx) ? root.x : 0;
    }

    private static double screenY(DomNode root, SnapshotContext ctx) {
        return screenSpace(root, ctx) ? root.y : 0;
    }

    private static double screenW(DomNode root, SnapshotContext ctx) {
        return screenSpace(root, ctx) ? root.width : 0;
    }

    private static double screenH(DomNode root, SnapshotContext ctx) {
        return screenSpace(root, ctx) ? root.height : 0;
    }

    private static void collect(DomNode node, XPathIndex.Result index, SnapshotContext ctx, List<ObjectNode> nodes) {
        if (node == null) return;
        if (node.nodeType == 1) nodes.add(nodeJson(node, index, ctx));
        for (DomNode child : node.children) collect(child, index, ctx, nodes);
        for (DomNode shadow : node.shadowRoots) collect(shadow, index, ctx, nodes);
    }

    private static ObjectNode nodeJson(DomNode node, XPathIndex.Result index, SnapshotContext ctx) {
        ObjectNode json = Jsons.object();
        json.put("handle", node.id());
        json.put("parent", parentHandle(node));
        json.put("tag", node.tag());
        json.put("role", Query.roleOf(node));
        json.put("name", node.accessibleName == null ? "" : node.accessibleName);
        String text = HtmlTree.textOf(node);
        if (text.length() > 200) text = text.substring(0, 200);
        json.put("text", text);
        json.put("value", value(node));
        ObjectNode attributes = json.putObject("attributes");
        node.attributes.forEach(attributes::put);
        ArrayNode states = json.putArray("states");
        for (String state : states(node)) states.add(state);
        json.put("frame_id", index.frameId(node));
        json.put("frame_path", index.framePath(node));
        json.put("shadow_path", node.shadowPath == null ? "" : node.shadowPath);
        json.put("xpath", node.xpath == null ? "" : node.xpath);
        json.put("path", nativePath(node));
        json.set("boxes", boxes(node, ctx));
        json.put("in_viewport", inViewport(node, ctx));
        json.put("interactable", AffinityScorer.interactable(node));
        json.put("capped", node.capped);
        json.put("cap_reason", node.capReason == null ? "" : node.capReason);
        json.put("section_path", node.sectionPath == null ? "" : node.sectionPath);
        json.set("paint", paint(node));
        return json;
    }

    private static ObjectNode boxes(DomNode node, SnapshotContext ctx) {
        ObjectNode boxes = Jsons.object();
        boolean screen = "true".equals(ctx.flags().get("screen-space"));
        Box document = !screen && node.hasBox ? new Box(node.x, node.y, node.width, node.height) : null;
        putBox(boxes, "document_css_px", document);
        putBox(boxes, "layout_viewport", node.hasLayoutViewport
                ? new Box(node.layoutViewportX, node.layoutViewportY, node.layoutViewportWidth, node.layoutViewportHeight)
                : null);
        Box visual = node.hasVisual
                ? new Box(
                node.visualX,
                node.visualY,
                node.visualWidth > 0 ? node.visualWidth : node.width,
                node.visualHeight > 0 ? node.visualHeight : node.height)
                : null;
        putBox(boxes, "visual_viewport", visual);
        putBox(boxes, "frame_document", node.hasFrameDocument
                ? new Box(node.frameDocumentX, node.frameDocumentY, node.frameDocumentWidth, node.frameDocumentHeight)
                : null);
        Box screenBox = screen && node.hasBox ? new Box(node.x, node.y, node.width, node.height) : null;
        putBox(boxes, "screen_px", screenBox);
        Box image = null;
        if (ctx.screenshot() != null) {
            Box source = visual != null ? visual : (screen ? screenBox : document);
            if (source != null) {
                double scale = ctx.imageScale();
                double originX = screen ? flagDouble(ctx, "screen-origin-x") : 0;
                double originY = screen ? flagDouble(ctx, "screen-origin-y") : 0;
                image = new Box(
                        (source.x() - originX) * scale,
                        (source.y() - originY) * scale,
                        source.width() * scale,
                        source.height() * scale
                );
                if (visual == null && !screen) SnapshotWarnings.once("image_px used document_css_px");
            }
        }
        putBox(boxes, "image_px", image);
        return boxes;
    }

    private static void putBox(ObjectNode parent, String name, Box box) {
        if (box == null) parent.putNull(name);
        else parent.set(name, box.toJson());
    }

    private static ObjectNode paint(DomNode node) {
        ObjectNode paint = Jsons.object();
        DomNode.Paint source = node.paint;
        paint.put("color", source == null || source.color == null ? "" : source.color);
        paint.put("background", source == null || source.background == null ? "" : source.background);
        if (source == null || Double.isNaN(source.contrast)) paint.putNull("contrast");
        else paint.put("contrast", source.contrast);
        paint.put("mean_rgb", source == null || source.meanRgb == null ? "" : source.meanRgb);
        if (source != null && source.hasDhash) paint.put("dhash", source.dhash);
        else paint.putNull("dhash");
        return paint;
    }

    private static boolean inViewport(DomNode node, SnapshotContext ctx) {
        if (!node.hasBox || node.width <= 0 || node.height <= 0) return false;
        return node.x < ctx.viewportWidth() && node.y < ctx.viewportHeight()
                && node.x + node.width > 0 && node.y + node.height > 0;
    }

    private static String parentHandle(DomNode node) {
        DomNode cursor = node.parent;
        while (cursor != null && cursor.nodeType != 1) cursor = cursor.parent;
        return cursor == null ? "" : cursor.id();
    }

    private static String value(DomNode node) {
        String value = node.attributes.get("value");
        if (value != null) return value;
        return node.nodeValue == null ? "" : node.nodeValue;
    }

    private static List<String> states(DomNode node) {
        List<String> states = new ArrayList<>();
        if (!node.displayed || node.attributes.containsKey("hidden") || "true".equals(node.attributes.get("aria-hidden"))) {
            add(states, "hidden");
        }
        if (node.attributes.containsKey("disabled") || "true".equals(node.attributes.get("aria-disabled"))) add(states, "disabled");
        if (node.attributes.containsKey("checked") || "true".equals(node.attributes.get("aria-checked"))) add(states, "checked");
        if ("true".equals(node.attributes.get("aria-selected"))) add(states, "selected");
        if (node.attributes.containsKey("readonly") || "true".equals(node.attributes.get("aria-readonly"))) add(states, "readonly");
        if (node.attributes.containsKey("required") || "true".equals(node.attributes.get("aria-required"))) add(states, "required");
        if ("true".equals(node.attributes.get("aria-expanded"))) add(states, "expanded");
        for (String state : node.states) add(states, state);
        return states;
    }

    private static void add(List<String> states, String state) {
        if (state == null || !STATE_NAMES.contains(state) || states.contains(state)) return;
        states.add(state);
    }
}
