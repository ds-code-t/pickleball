package dev.domengine.premap;

import dev.domengine.affinity.AffinityScorer;
import dev.domengine.affinity.AffinityWeights;
import dev.domengine.log.EngineLog;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fills the pre-map. Phase 2 writes sections, caps, and labels. Later phases add indexes. */
public final class PreMapBuilder {
    private PreMapBuilder() {
    }

    public static PreMap build(DomNode root, SnapshotContext context) {
        long started = System.nanoTime();
        SnapshotContext ctx = context == null ? SnapshotContext.empty() : context;
        if (root != null) root.linkParents();
        List<Section> sections = SectionDetector.detect(root, ctx.platform());
        int rejected = applyCaps(root, ctx);
        AffinityScorer.Bindings bindings = root == null
                ? new AffinityScorer.Bindings(List.of(), List.of())
                : AffinityScorer.bind(root, weights(ctx), ctx.platform());
        List<PreMap.LabelRef> labels = new ArrayList<>();
        for (AffinityScorer.Binding binding : bindings.labels()) {
            labels.add(new PreMap.LabelRef(
                    binding.label().id(),
                    binding.target().id(),
                    binding.score().total(),
                    binding.score().parts(),
                    binding.score().reasons()
            ));
        }
        List<String> unlabeled = new ArrayList<>();
        for (DomNode node : bindings.unlabeled()) unlabeled.add(node.id());
        Map<String, String> boxes = new LinkedHashMap<>();
        BoxCaps caps = ctx.caps();
        boxes.put("max_area", cap(caps.maxAreaPx(), caps.maxAreaRatio()));
        boxes.put("max_width", cap(caps.maxWidthPx(), caps.maxWidthRatio()));
        boxes.put("max_height", cap(caps.maxHeightPx(), caps.maxHeightRatio()));
        boxes.put("rejected", Integer.toString(rejected));
        int nodes = count(root);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        EngineLog.info("premap", "premap build nodes=" + nodes + " ms=" + ms);
        return PreMap.builder(ctx.sessionId(), ctx.docVersion())
                .sections(sections)
                .labels(labels)
                .unlabeled(unlabeled)
                .boxes(boxes)
                .build();
    }

    private static AffinityWeights weights(SnapshotContext ctx) {
        String spec = ctx.flags().get("affinity-weights");
        if (spec == null || spec.isBlank()) return AffinityWeights.fromEnv();
        return AffinityWeights.parse(spec);
    }

    private static int applyCaps(DomNode node, SnapshotContext ctx) {
        if (node == null) return 0;
        int rejected = 0;
        if (node.nodeType == 1 && !BoxCaps.alwaysExcluded(node)) {
            BoxCaps.Decision decision = ctx.caps().assess(node, ctx.viewportWidth(), ctx.viewportHeight(), false);
            node.capped = decision.capped();
            node.capReason = decision.reason();
            if (decision.capped()) rejected++;
        }
        for (DomNode child : node.children) rejected += applyCaps(child, ctx);
        for (DomNode shadow : node.shadowRoots) rejected += applyCaps(shadow, ctx);
        return rejected;
    }

    private static int count(DomNode node) {
        if (node == null) return 0;
        int total = node.nodeType == 1 ? 1 : 0;
        for (DomNode child : node.children) total += count(child);
        for (DomNode shadow : node.shadowRoots) total += count(shadow);
        return total;
    }

    private static String cap(double pixels, double ratio) {
        if (pixels > 0) return String.format(Locale.ROOT, "%.0fpx", pixels);
        return String.format(Locale.ROOT, "%.2f", ratio);
    }
}
