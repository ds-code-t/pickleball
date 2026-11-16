package tools.dscode.common.agentsurface;

import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.common.agentcontrol.SurfaceBox;
import tools.dscode.common.agentcontrol.VisualKinds;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.control.protocol.surface.NativeHighlightBox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native rectangle for a visual kind. The page path draws the real callout, arrow,
 * magnifier, or dim. Native draws the same x, y, width, and height and stores the caption.
 * A headless runtime returns drawn=false reason=headless. An unsized box is not drawn.
 */
public final class NativeVisual {
    private NativeVisual() {
    }

    public static Map<String, Object> show(Path project, String runId, String kind, Map<String, String> args) throws Exception {
        SurfaceBox.View box = box(kind, args);
        if (box == null) throw new IllegalArgumentException(kind + " needs a target or a visual-viewport box");
        String id = text(args.get("id"), args.get("handle"), "native-" + kind);
        NativeHighlightBox.Drawn drawn = box.sized()
                ? NativeHighlightBox.show(id, box.x(), box.y(), box.width(), box.height())
                : new NativeHighlightBox.Drawn(false, "no-size", id);
        String raw = args.get("text") == null ? "" : args.get("text");
        if (!raw.isBlank()) {
            String labeled = PopupSurfaceBridge.label(PopupSurfaceBridge.neutralize(raw), kind);
            PopupLedger.append(project, runId, "append", "instructions", id, "", labeled, "", PopupSurfaceBridge.sentence(kind));
        }
        CompositeLog.info(kind, "native id=" + id + " boundsSource=" + box.source()
                + " width=" + box.width() + " height=" + box.height());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawn", drawn.drawn());
        body.put("reason", drawn.reason());
        body.put("id", id);
        body.put("x", box.x());
        body.put("y", box.y());
        if (box.sized()) {
            body.put("width", box.width());
            body.put("height", box.height());
        }
        body.put("boundsSource", box.source());
        body.put("kind", kind);
        body.put("placement", "caller".equals(box.source()) ? "caller" : "visual-viewport-relative");
        body.put("sentence", PopupSurfaceBridge.sentence(kind));
        body.put("popup", false);
        body.put("native", "rectangle");
        return body;
    }

    private static SurfaceBox.View box(String kind, Map<String, String> args) throws Exception {
        if ("arrow".equals(kind)) {
            VisualKinds.Arrow arrow = VisualKinds.arrow(args);
            return new SurfaceBox.View(arrow.x(), arrow.y(), arrow.width(), arrow.height(), arrow.resolved() ? "resolved" : "stored");
        }
        if (present(args.get("query"))) {
            int[] parsed = NativeHighlight.fromJson(NativeHighlight.queries.apply(args.get("query")));
            return new SurfaceBox.View(parsed[0], parsed[1], parsed[2], parsed[3], "resolved");
        }
        SurfaceBox.View chosen = SurfaceBox.choose(
                args.get("handle"),
                args.get("geometry"),
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                integerOrNull(args.get("width")),
                integerOrNull(args.get("height"))
        );
        if (chosen == null) return null;
        int[] painted = VisualKinds.paintedSize(kind, chosen);
        if (painted != null && !chosen.sized()) {
            return new SurfaceBox.View(chosen.x(), chosen.y(), painted[0], painted[1], chosen.source());
        }
        return chosen;
    }

    private static Integer integerOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return Integer.parseInt(value.trim());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (present(value)) return value;
        }
        return "";
    }
}
