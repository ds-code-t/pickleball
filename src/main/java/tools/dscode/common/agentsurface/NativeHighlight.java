package tools.dscode.common.agentsurface;

import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.control.protocol.surface.NativeHighlightBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Native highlight from explicit bounds or a desktop query JSON document. */
public final class NativeHighlight {
    static Function<String, String> queries = NativeHighlight::emptyQuery;

    private NativeHighlight() {
    }

    public static Map<String, Object> show(java.nio.file.Path project, String runId, Map<String, String> args) throws Exception {
        int[] box = bounds(args);
        String id = text(args.get("id"), "native");
        NativeHighlightBox.Drawn drawn = NativeHighlightBox.show(id, box[0], box[1], box[2], box[3]);
        String caption = text(args.get("text"), args.get("caption"));
        if (!caption.isBlank()) {
            String labeled = PopupSurfaceBridge.label(PopupSurfaceBridge.neutralize(caption), "html");
            PopupLedger.append(project, runId, "append", "instructions", id, "", labeled, "", PopupSurfaceBridge.sentence("html"));
        }
        PopupLog.log(project, runId, "highlight", id, "drawn=" + drawn.drawn() + " reason=" + drawn.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawn", drawn.drawn());
        body.put("reason", drawn.reason());
        body.put("id", id);
        body.put("x", box[0]);
        body.put("y", box[1]);
        body.put("width", box[2]);
        body.put("height", box[3]);
        body.put("popup", false);
        return body;
    }

    public static Map<String, Object> hide(String id) {
        NativeHighlightBox.Drawn drawn = NativeHighlightBox.hide(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drawn", drawn.drawn());
        body.put("reason", drawn.reason());
        body.put("id", id);
        body.put("reversed", true);
        return body;
    }

    private static int[] bounds(Map<String, String> args) throws Exception {
        if (present(args.get("query"))) {
            String json = queries.apply(args.get("query"));
            return fromJson(json == null ? "" : json);
        }
        if (!present(args.get("width")) || !present(args.get("height"))) {
            throw new IllegalArgumentException("highlight native requires --x --y --width --height or --query");
        }
        return new int[] {integer(args.get("x")), integer(args.get("y")), integer(args.get("width")), integer(args.get("height"))};
    }

    static int[] fromJson(String json) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json == null || json.isBlank() ? "{}" : json);
        com.fasterxml.jackson.databind.JsonNode box = node.has("width") ? node : node.path("bounds");
        if (!box.has("width")) box = node.path("node");
        if (!box.has("width")) throw new IllegalArgumentException("desktop query JSON has no width");
        return new int[] {box.path("x").asInt(0), box.path("y").asInt(0), box.path("width").asInt(0), box.path("height").asInt(0)};
    }

    private static String emptyQuery(String query) {
        return "{}";
    }

    private static int integer(String value) {
        if (value == null || value.isBlank()) return 0;
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
