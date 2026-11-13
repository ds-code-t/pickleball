package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.premap.DocVersion;
import dev.domengine.session.BrowserSession;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chrome, Chromium, and Edge composite snapshot. Structure is one pierced document,
 * one {@code DOMSnapshot.captureSnapshot}, and one accessibility tree per frame.
 */
public final class CdpSnapshotProducer {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private CdpSnapshotProducer() {
    }

    public static ObjectNode produce(BrowserSession session, Map<String, String> flags) {
        if (session == null) {
            throw new IllegalStateException(
                    "error: browser snapshot needs a page session.\n"
                            + "fix: Attach with --session or --cdp before snapshot.");
        }
        return produce(session.connection(), session.id, flags);
    }

    public static ObjectNode produce(CdpConnection connection, String sessionId, Map<String, String> flags) {
        if (connection == null) {
            throw new IllegalStateException(
                    "error: browser snapshot needs a page session.\n"
                            + "fix: Attach with --session or --cdp before snapshot.");
        }
        long started = System.nanoTime();
        Map<String, String> used = flags == null ? Map.of() : flags;
        String product = product(connection);
        String platform = platformOf(product);
        LiveTree tree = LiveTree.read(connection);
        LiveTree.Layout layout = tree.readLayout(connection);
        applyAccessibility(connection, tree);
        byte[] png = screenshot(connection, used);
        String html = sources(connection, tree);
        String frameId = tree.frames.isEmpty() ? "" : tree.frames.get(0).id();
        String version = DocVersion.read(connection, frameId, tree.loaderId);
        PngImage image = png.length == 0 ? null : PngImage.decode(png);
        SnapshotOptions options = SnapshotOptions.fromFlags(used);
        SnapshotContext context = SnapshotContext.browser(options, platform, sessionId, version, html, image, layout);
        if (used.containsKey("out-dir") && image != null) writePng(used.get("out-dir"), image.bytes());
        ObjectNode json = CompositeSnapshotBuilder.build(tree.root, context, started).toJson();
        json.put("ok", true);
        if (tree.root != null) json.set("document", tree.root.toJson());
        replaceFrames(json, tree);
        if (used.containsKey("out-dir")) writeJson(used.get("out-dir"), json);
        EngineLog.info("snapshot", "snapshot cdp platform=" + platform + " doc=" + version
                + " nodes=" + json.path("nodes").size());
        return json;
    }

    /** {@code Edg/} is Edge. {@code Chromium/} is Chromium. Anything else, including Chrome, is chrome. */
    public static String platformOf(String product) {
        String text = product == null ? "" : product;
        if (text.contains("Edg/")) return "edge";
        if (text.contains("Chromium/")) return "chromium";
        return "chrome";
    }

    private static String product(CdpConnection connection) {
        JsonNode version = connection.call("Browser.getVersion", Jsons.object(), TIMEOUT);
        String product = version.path("product").asText("");
        if (!product.isBlank()) return product;
        return version.path("userAgent").asText("");
    }

    private static void applyAccessibility(CdpConnection connection, LiveTree tree) {
        List<LiveTree.Frame> frames = tree.frames.isEmpty()
                ? List.of(new LiveTree.Frame("main", "", ""))
                : tree.frames;
        for (LiveTree.Frame frame : frames) {
            ObjectNode params = Jsons.object();
            if (frame.id() != null && !frame.id().isBlank()) params.put("frameId", frame.id());
            JsonNode treeJson;
            try {
                treeJson = connection.call("Accessibility.getFullAXTree", params, TIMEOUT);
            } catch (RuntimeException failure) {
                SnapshotWarnings.once("ax-tree " + frame.id());
                continue;
            }
            JsonNode nodes = treeJson.path("nodes");
            if (!nodes.isArray()) continue;
            Map<Long, DomNode> byId = index(tree.root);
            for (JsonNode ax : nodes) {
                long id = ax.path("backendDOMNodeId").asLong(0);
                if (id == 0) id = ax.path("backendNodeId").asLong(0);
                DomNode node = byId.get(id);
                if (node == null) continue;
                String role = ax.path("role").path("value").asText("");
                String name = ax.path("name").path("value").asText("");
                if (!role.isBlank()) node.role = role;
                if (!name.isBlank()) node.accessibleName = name;
                JsonNode properties = ax.path("properties");
                if (!properties.isArray()) continue;
                for (JsonNode property : properties) {
                    String key = property.path("name").asText("");
                    JsonNode raw = property.path("value").path("value");
                    boolean on = raw.isBoolean() ? raw.asBoolean() : "true".equalsIgnoreCase(raw.asText(""));
                    if (!on) continue;
                    switch (key) {
                        case "disabled" -> node.states.add("disabled");
                        case "checked" -> node.states.add("checked");
                        case "selected" -> node.states.add("selected");
                        case "expanded" -> node.states.add("expanded");
                        case "focused" -> node.states.add("focused");
                        case "readonly" -> node.states.add("readonly");
                        case "required" -> node.states.add("required");
                        default -> {
                        }
                    }
                }
            }
        }
    }

    private static byte[] screenshot(CdpConnection connection, Map<String, String> flags) {
        ObjectNode params = Jsons.object();
        params.put("format", "png");
        params.put("fromSurface", true);
        if ("true".equals(flags.get("full-page"))) params.put("captureBeyondViewport", true);
        JsonNode shot = connection.call("Page.captureScreenshot", params, TIMEOUT);
        String data = shot.path("data").asText("");
        if (data.isBlank()) {
            throw new CdpException("error: browser snapshot has no screenshot\nfix: Page.captureScreenshot must return a PNG.");
        }
        return Base64.getDecoder().decode(data);
    }

    private static String sources(CdpConnection connection, LiveTree tree) {
        StringBuilder html = new StringBuilder();
        List<DomNode> documents = new ArrayList<>();
        collectDocuments(tree.root, documents);
        if (documents.isEmpty() && tree.root != null) documents.add(tree.root);
        int index = 0;
        for (DomNode document : documents) {
            DomNode target = htmlElement(document);
            ObjectNode params = Jsons.object();
            params.put("backendNodeId", target.backendNodeId);
            String frame = index < tree.frames.size() ? tree.frames.get(index).id() : document.frameId;
            JsonNode result;
            try {
                result = connection.call("DOM.getOuterHTML", params, TIMEOUT);
            } catch (RuntimeException failure) {
                SnapshotWarnings.once("outer-html " + frame);
                index++;
                continue;
            }
            if (html.length() > 0) html.append('\n');
            html.append("<!-- frame:").append(frame == null ? "" : frame).append(" -->\n");
            html.append(result.path("outerHTML").asText(""));
            index++;
        }
        return html.toString();
    }

    private static void collectDocuments(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 9) into.add(node);
        for (DomNode child : node.children) collectDocuments(child, into);
    }

    private static DomNode htmlElement(DomNode document) {
        for (DomNode child : document.children) {
            if (child.nodeType == 1) return child;
        }
        return document;
    }

    private static Map<Long, DomNode> index(DomNode node) {
        Map<Long, DomNode> into = new LinkedHashMap<>();
        walk(node, into);
        return into;
    }

    private static void walk(DomNode node, Map<Long, DomNode> into) {
        if (node == null) return;
        if (node.backendNodeId > 0) into.put(node.backendNodeId, node);
        for (DomNode child : node.children) walk(child, into);
        for (DomNode shadow : node.shadowRoots) walk(shadow, into);
    }

    private static void replaceFrames(ObjectNode json, LiveTree tree) {
        List<DomNode> iframes = new ArrayList<>();
        collectIframes(tree.root, iframes);
        ArrayNode frames = json.putArray("frames");
        int nested = 0;
        for (LiveTree.Frame frame : tree.frames) {
            ObjectNode one = frames.addObject();
            one.put("id", frame.id());
            one.put("parent_id", frame.parentId() == null ? "" : frame.parentId());
            one.put("url", frame.url() == null ? "" : frame.url());
            String path = "";
            double x = 0;
            double y = 0;
            boolean child = frame.parentId() != null && !frame.parentId().isBlank();
            if (child && nested < iframes.size()) {
                DomNode iframe = iframes.get(nested++);
                path = iframe.xpath == null ? "" : iframe.xpath;
                if (iframe.hasBox) {
                    x = iframe.x;
                    y = iframe.y;
                }
            }
            one.put("frame_path", path);
            ObjectNode offset = one.putObject("offset");
            offset.put("x", x);
            offset.put("y", y);
        }
    }

    private static void collectIframes(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.iframe) into.add(node);
        for (DomNode child : node.children) collectIframes(child, into);
        for (DomNode shadow : node.shadowRoots) collectIframes(shadow, into);
    }

    private static void writePng(String dir, byte[] png) {
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            Files.write(out.resolve("screenshot.png"), png);
        } catch (java.io.IOException failure) {
            throw new CdpException("snapshot out-dir: " + failure.getMessage());
        }
    }

    private static void writeJson(String dir, ObjectNode json) {
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            Files.writeString(out.resolve("snapshot.json"), Jsons.pretty(json));
        } catch (java.io.IOException failure) {
            throw new CdpException("snapshot out-dir: " + failure.getMessage());
        }
    }
}
