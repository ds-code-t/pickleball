package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.premap.PreMap;

import java.util.ArrayList;
import java.util.List;

/** `pickleball.composite-snapshot` version 1. */
public final class CompositeSnapshot {
    public static final String SCHEMA = "pickleball.composite-snapshot";
    public static final int VERSION = 1;

    private final String id;
    private final String sessionId;
    private final String docVersion;
    private final String platform;
    private final String transport;
    private final String capturedAt;
    private final long durationMs;
    private final double cssWidth;
    private final double cssHeight;
    private final double devicePixelRatio;
    private final double scrollX;
    private final double scrollY;
    private final double visualOffsetX;
    private final double visualOffsetY;
    private final double visualScale;
    private final double screenX;
    private final double screenY;
    private final double screenWidth;
    private final double screenHeight;
    private final int imageWidth;
    private final int imageHeight;
    private final double imageScale;
    private final String sha256;
    private final String screenshotPath;
    private final String base64;
    private final String sourceKind;
    private final String sourceText;
    private final List<XPathIndex.Frame> frames;
    private final List<ObjectNode> nodes;
    private final PreMap premap;
    private final List<String> warnings;

    CompositeSnapshot(
            String id,
            String sessionId,
            String docVersion,
            String platform,
            String transport,
            String capturedAt,
            long durationMs,
            double cssWidth,
            double cssHeight,
            double devicePixelRatio,
            double scrollX,
            double scrollY,
            double visualOffsetX,
            double visualOffsetY,
            double visualScale,
            double screenX,
            double screenY,
            double screenWidth,
            double screenHeight,
            int imageWidth,
            int imageHeight,
            double imageScale,
            String sha256,
            String screenshotPath,
            String base64,
            String sourceKind,
            String sourceText,
            List<XPathIndex.Frame> frames,
            List<ObjectNode> nodes,
            PreMap premap,
            List<String> warnings
    ) {
        this.id = id == null ? "" : id;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.docVersion = docVersion == null ? "" : docVersion;
        this.platform = platform == null ? "" : platform;
        this.transport = transport == null ? "" : transport;
        this.capturedAt = capturedAt == null ? "" : capturedAt;
        this.durationMs = durationMs;
        this.cssWidth = cssWidth;
        this.cssHeight = cssHeight;
        this.devicePixelRatio = devicePixelRatio;
        this.scrollX = scrollX;
        this.scrollY = scrollY;
        this.visualOffsetX = visualOffsetX;
        this.visualOffsetY = visualOffsetY;
        this.visualScale = visualScale;
        this.screenX = screenX;
        this.screenY = screenY;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.imageScale = imageScale;
        this.sha256 = sha256 == null ? "" : sha256;
        this.screenshotPath = screenshotPath == null ? "" : screenshotPath;
        this.base64 = base64 == null ? "" : base64;
        this.sourceKind = sourceKind == null ? "html" : sourceKind;
        this.sourceText = sourceText == null ? "" : sourceText;
        this.frames = frames == null ? List.of() : List.copyOf(frames);
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.premap = premap == null ? PreMap.empty(this.sessionId, this.docVersion) : premap;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public PreMap premap() {
        return premap;
    }

    public ObjectNode toJson() {
        ObjectNode node = Jsons.object();
        node.put("schema", SCHEMA);
        node.put("version", VERSION);
        node.put("id", id);
        node.put("session_id", sessionId);
        node.put("doc_version", docVersion);
        node.put("platform", platform);
        node.put("transport", transport);
        node.put("captured_at", capturedAt);
        node.put("duration_ms", durationMs);
        ObjectNode viewport = node.putObject("viewport");
        viewport.put("css_width", cssWidth);
        viewport.put("css_height", cssHeight);
        viewport.put("device_pixel_ratio", devicePixelRatio);
        viewport.put("scroll_x", scrollX);
        viewport.put("scroll_y", scrollY);
        ObjectNode visual = viewport.putObject("visual");
        visual.put("offset_x", visualOffsetX);
        visual.put("offset_y", visualOffsetY);
        visual.put("scale", visualScale);
        ObjectNode screen = viewport.putObject("screen");
        screen.put("x", screenX);
        screen.put("y", screenY);
        screen.put("width", screenWidth);
        screen.put("height", screenHeight);
        ObjectNode screenshot = node.putObject("screenshot");
        screenshot.put("format", "png");
        screenshot.put("width_px", imageWidth);
        screenshot.put("height_px", imageHeight);
        screenshot.put("image_scale", imageScale);
        screenshot.putNull("clip");
        screenshot.put("sha256", sha256);
        if (!screenshotPath.isBlank()) screenshot.put("path", screenshotPath);
        if (!base64.isBlank()) screenshot.put("base64", base64);
        ObjectNode source = node.putObject("source");
        source.put("kind", sourceKind);
        source.put("text", sourceText);
        source.put("bytes", sourceText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        ArrayNode frameNode = node.putArray("frames");
        for (XPathIndex.Frame frame : frames) {
            ObjectNode one = frameNode.addObject();
            one.put("id", frame.id());
            one.put("parent_id", frame.parentId());
            one.put("url", frame.url());
            one.put("frame_path", frame.framePath());
            ObjectNode offset = one.putObject("offset");
            offset.put("x", frame.x());
            offset.put("y", frame.y());
        }
        ArrayNode nodeArray = node.putArray("nodes");
        for (ObjectNode one : nodes) nodeArray.add(one.deepCopy());
        node.set("premap", premap.toJson());
        ArrayNode warningNode = node.putArray("warnings");
        for (String warning : warnings) warningNode.add(warning);
        return node;
    }

    public static CompositeSnapshot fromJson(JsonNode raw) {
        JsonNode node = raw == null ? Jsons.object() : raw;
        JsonNode viewport = node.path("viewport");
        JsonNode visual = viewport.path("visual");
        JsonNode screen = viewport.path("screen");
        JsonNode screenshot = node.path("screenshot");
        JsonNode source = node.path("source");
        List<XPathIndex.Frame> frames = new ArrayList<>();
        node.path("frames").forEach(frame -> frames.add(new XPathIndex.Frame(
                frame.path("id").asText(""),
                frame.path("parent_id").asText(""),
                frame.path("url").asText(""),
                frame.path("frame_path").asText(""),
                frame.path("offset").path("x").asDouble(),
                frame.path("offset").path("y").asDouble()
        )));
        List<ObjectNode> nodes = new ArrayList<>();
        node.path("nodes").forEach(item -> {
            if (item instanceof ObjectNode object) nodes.add(object.deepCopy());
        });
        List<String> warnings = new ArrayList<>();
        node.path("warnings").forEach(warning -> warnings.add(warning.asText("")));
        String session = node.path("session_id").asText("");
        String version = node.path("doc_version").asText("");
        return new CompositeSnapshot(
                node.path("id").asText(""),
                session,
                version,
                node.path("platform").asText(""),
                node.path("transport").asText(""),
                node.path("captured_at").asText(""),
                node.path("duration_ms").asLong(),
                viewport.path("css_width").asDouble(),
                viewport.path("css_height").asDouble(),
                viewport.path("device_pixel_ratio").asDouble(),
                viewport.path("scroll_x").asDouble(),
                viewport.path("scroll_y").asDouble(),
                visual.path("offset_x").asDouble(),
                visual.path("offset_y").asDouble(),
                visual.path("scale").asDouble(1),
                screen.path("x").asDouble(),
                screen.path("y").asDouble(),
                screen.path("width").asDouble(),
                screen.path("height").asDouble(),
                screenshot.path("width_px").asInt(),
                screenshot.path("height_px").asInt(),
                screenshot.path("image_scale").asDouble(1),
                screenshot.path("sha256").asText(""),
                screenshot.path("path").asText(""),
                screenshot.path("base64").asText(""),
                source.path("kind").asText("html"),
                source.path("text").asText(""),
                frames,
                nodes,
                PreMap.fromJson(node.path("premap"), session, version),
                warnings
        );
    }
}
