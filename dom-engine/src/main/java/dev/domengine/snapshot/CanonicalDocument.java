package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 canonical tree. Browser frames store raw HTML. Desktop stores one synthesized HTML-ish document
 * plus the original a11y-xml as side data.
 */
public final class CanonicalDocument {
    public static final String NATIVE_PATH_ATTR = "data-pkb-native-path";

    private final String kind;
    private final List<Frame> frames;
    private final Side side;

    public CanonicalDocument(String kind, List<Frame> frames, Side side) {
        if (!"html".equals(kind) && !"html-ish".equals(kind)) {
            throw new IllegalArgumentException("error: canonical kind must be html or html-ish");
        }
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("error: canonical frames are required");
        }
        for (Frame frame : frames) {
            if (frame == null || frame.id == null || frame.id.isBlank()) {
                throw new IllegalArgumentException("error: canonical frame id is blank");
            }
        }
        if ("html-ish".equals(kind) && side == null) {
            throw new IllegalArgumentException("error: html-ish requires a11y-xml side data");
        }
        if ("html".equals(kind) && side != null) {
            throw new IllegalArgumentException("error: html canonical omits side data");
        }
        this.kind = kind;
        this.frames = List.copyOf(frames);
        this.side = side;
    }

    public static CanonicalDocument html(Frame... frames) {
        return new CanonicalDocument("html", List.of(frames), null);
    }

    public static CanonicalDocument desktop(String html, String a11yXml) {
        String markup = html == null ? "" : html;
        String xml = a11yXml == null ? "" : a11yXml;
        return new CanonicalDocument(
                "html-ish",
                List.of(new Frame("desktop", "", "", "", 0, 0, markup, false)),
                new Side(xml)
        );
    }

    public String kind() {
        return kind;
    }

    public List<Frame> frames() {
        return frames;
    }

    public Side side() {
        return side;
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("kind", kind);
        ArrayNode frameNode = node.putArray("frames");
        for (Frame frame : frames) {
            ObjectNode one = frameNode.addObject();
            one.put("id", frame.id);
            one.put("parent_id", frame.parentId);
            one.put("url", frame.url);
            one.put("frame_path", frame.framePath);
            ObjectNode offset = one.putObject("offset");
            offset.put("x", frame.x);
            offset.put("y", frame.y);
            one.put("html", frame.html);
            if (frame.shadowPreserved) one.put("shadow", "preserved");
        }
        if (side != null) {
            ObjectNode sideNode = node.putObject("side");
            sideNode.put("a11y_xml", side.a11yXml);
            sideNode.put("native_path_attr", NATIVE_PATH_ATTR);
        }
        return node;
    }

    public static CanonicalDocument fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("error: canonical tree is missing");
        }
        List<Frame> frames = new ArrayList<>();
        node.path("frames").forEach(frame -> frames.add(new Frame(
                frame.path("id").asText(""),
                frame.path("parent_id").asText(""),
                frame.path("url").asText(""),
                frame.path("frame_path").asText(""),
                frame.path("offset").path("x").asDouble(),
                frame.path("offset").path("y").asDouble(),
                frame.path("html").asText(""),
                "preserved".equals(frame.path("shadow").asText(""))
        )));
        Side side = null;
        if (node.has("side") && !node.get("side").isNull()) {
            side = new Side(node.path("side").path("a11y_xml").asText(""));
        }
        return new CanonicalDocument(node.path("kind").asText(""), frames, side);
    }

    public record Frame(
            String id,
            String parentId,
            String url,
            String framePath,
            double x,
            double y,
            String html,
            boolean shadowPreserved
    ) {
        public Frame {
            id = id == null ? "" : id;
            parentId = parentId == null ? "" : parentId;
            url = url == null ? "" : url;
            framePath = framePath == null ? "" : framePath;
            html = html == null ? "" : html;
        }
    }

    public record Side(String a11yXml) {
        public Side {
            a11yXml = a11yXml == null ? "" : a11yXml;
        }
    }
}
