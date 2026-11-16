package tools.dscode.common.agentsurface;

import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.common.agentcontrol.SurfaceBox;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.control.protocol.surface.NativeHighlightBox;

import java.util.LinkedHashMap;
import java.util.Map;

/** Native overlay box. Same resolved bounds as the page path. The callout stays one style. */
public final class NativeOverlay {
    private NativeOverlay() {
    }

    public static Map<String, Object> show(java.nio.file.Path project, String runId, Map<String, String> args) throws Exception {
        SurfaceBox.View box = box(args);
        if (box == null) throw new IllegalArgumentException("overlay needs a target or a visual-viewport box");
        String id = text(args.get("id"), args.get("handle"), "native-overlay");
        NativeHighlightBox.Drawn drawn = box.sized()
                ? NativeHighlightBox.show(id, box.x(), box.y(), box.width(), box.height())
                : new NativeHighlightBox.Drawn(false, "no-size", id);
        String raw = args.get("text") == null ? "" : args.get("text");
        if (!raw.isBlank()) {
            String labeled = PopupSurfaceBridge.label(PopupSurfaceBridge.neutralize(raw), "overlay");
            PopupLedger.append(project, runId, "append", "instructions", id, "", labeled, "", PopupSurfaceBridge.sentence("overlay"));
        }
        CompositeLog.info("overlay", "native id=" + id + " boundsSource=" + box.source()
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
        body.put("kind", "overlay");
        body.put("placement", "caller".equals(box.source()) ? "caller" : "visual-viewport-relative");
        body.put("sentence", PopupSurfaceBridge.sentence("overlay"));
        body.put("popup", false);
        return body;
    }

    private static SurfaceBox.View box(Map<String, String> args) throws Exception {
        if (present(args.get("query"))) {
            int[] parsed = NativeHighlight.fromJson(NativeHighlight.queries.apply(args.get("query")));
            return new SurfaceBox.View(parsed[0], parsed[1], parsed[2], parsed[3], "resolved");
        }
        return SurfaceBox.choose(
                args.get("handle"),
                args.get("geometry"),
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                integerOrNull(args.get("width")),
                integerOrNull(args.get("height"))
        );
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
