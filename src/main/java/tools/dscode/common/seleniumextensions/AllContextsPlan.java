package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Classifies one composite all-contexts query.
 * Closed shadows, cross-origin frames, depth past 5, and frames past the cap are dropped.
 * The current frame yields a live hit per displayed node. Each other in-cap frame
 * with a displayed node yields one foreign hit. A null result means the caller
 * should keep the Java walk.
 */
public final class AllContextsPlan {

    public record FrameStep(String id, String name, int index) {
    }

    public record Hit(boolean foreign, JsonNode node) {
    }

    public record Result(List<Hit> hits, boolean depthExceeded, boolean frameCapped) {
    }

    private AllContextsPlan() {
    }

    public static Result classify(JsonNode report, List<FrameStep> startPath) {
        if (report == null || !report.has("nodes") || !report.has("context")) {
            return null;
        }
        JsonNode context = report.get("context");
        if (context == null || !context.has("frames")) {
            return null;
        }
        List<Frame> frames = new ArrayList<>();
        for (JsonNode row : context.get("frames")) {
            frames.add(new Frame(
                    text(row, "id"),
                    text(row, "parent"),
                    text(row, "name"),
                    text(row, "element_id"),
                    row.path("depth").asInt(0),
                    row.path("cross_origin").asBoolean(false),
                    row.path("is_main").asBoolean(false),
                    row.has("order") && !row.get("order").isNull() ? row.get("order").asInt() : null,
                    row.path("index").asInt(0)
            ));
        }
        String main = null;
        for (Frame frame : frames) {
            if (frame.main) {
                main = frame.id;
                break;
            }
        }
        if (main == null && !frames.isEmpty()) {
            main = frames.get(0).id;
        }
        String current = main;
        if (startPath != null && !startPath.isEmpty() && main != null) {
            String at = main;
            for (FrameStep step : startPath) {
                String next = child(frames, at, step);
                if (next == null) {
                    break;
                }
                at = next;
            }
            current = at;
        }
        List<NodeHit> nodes = new ArrayList<>();
        int index = 0;
        for (JsonNode node : report.get("nodes")) {
            nodes.add(new NodeHit(node, index++));
        }
        List<Hit> hits = new ArrayList<>();
        List<NodeHit> live = new ArrayList<>();
        for (NodeHit node : nodes) {
            if (!keep(node, frames)) {
                continue;
            }
            if (same(node.frameId, current)) {
                live.add(node);
            }
        }
        live.sort(Comparator.comparingInt((NodeHit node) -> node.shadowDepth).thenComparingInt(node -> node.index));
        for (NodeHit node : live) {
            hits.add(new Hit(false, node.node));
        }
        boolean topForeign = startPath == null || !startPath.isEmpty();
        if (topForeign && main != null && !same(main, current) && anyDisplayed(nodes, frames, main)) {
            hits.add(new Hit(true, null));
        }
        List<Frame> others = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame.main || frame.cross || same(frame.id, current) || same(frame.id, main)) {
                continue;
            }
            if (frame.depth > AllContextsScan.MAX_DEPTH || frame.order == null || frame.order >= AllContextsScan.MAX_FRAMES) {
                continue;
            }
            if (anyDisplayed(nodes, frames, frame.id)) {
                others.add(frame);
            }
        }
        others.sort(Comparator.comparingInt(frame -> frame.order));
        for (int i = 0; i < others.size(); i++) {
            hits.add(new Hit(true, null));
        }
        return new Result(
                hits,
                context.path("depth_exceeded").asBoolean(false),
                context.path("frame_capped").asBoolean(false)
        );
    }

    private static boolean anyDisplayed(List<NodeHit> nodes, List<Frame> frames, String frameId) {
        for (NodeHit node : nodes) {
            if (same(node.frameId, frameId) && keep(node, frames)) {
                return true;
            }
        }
        return false;
    }

    private static boolean keep(NodeHit node, List<Frame> frames) {
        if (!node.displayed || node.closed) {
            return false;
        }
        if (node.cross || frameCross(frames, node.frameId)) {
            return false;
        }
        if (node.frameDepth + node.shadowDepth > AllContextsScan.MAX_DEPTH) {
            return false;
        }
        return true;
    }

    private static boolean frameCross(List<Frame> frames, String frameId) {
        for (Frame frame : frames) {
            if (same(frame.id, frameId)) {
                return frame.cross;
            }
        }
        return false;
    }

    private static String child(List<Frame> frames, String parent, FrameStep step) {
        List<Frame> kids = new ArrayList<>();
        for (Frame frame : frames) {
            if (same(frame.parent, parent)) {
                kids.add(frame);
            }
        }
        if (step.id != null) {
            for (Frame frame : kids) {
                if (step.id.equals(frame.elementId)) {
                    return frame.id;
                }
            }
        }
        if (step.name != null) {
            for (Frame frame : kids) {
                if (step.name.equals(frame.name)) {
                    return frame.id;
                }
            }
        }
        for (Frame frame : kids) {
            if (frame.index == step.index) {
                return frame.id;
            }
        }
        return null;
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private record Frame(
            String id,
            String parent,
            String name,
            String elementId,
            int depth,
            boolean cross,
            boolean main,
            Integer order,
            int index
    ) {
    }

    private static final class NodeHit {
        private final JsonNode node;
        private final int index;
        private final String frameId;
        private final int frameDepth;
        private final int shadowDepth;
        private final boolean closed;
        private final boolean cross;
        private final boolean displayed;

        private NodeHit(JsonNode node, int index) {
            this.node = node;
            this.index = index;
            this.frameId = text(node, "frame_id");
            this.frameDepth = node.path("frame_depth").asInt(0);
            this.shadowDepth = node.path("shadow_depth").asInt(0);
            this.closed = node.path("in_closed_shadow").asBoolean(false);
            this.cross = node.path("cross_origin").asBoolean(false);
            this.displayed = !node.has("displayed") || node.get("displayed").asBoolean(true);
        }
    }
}
