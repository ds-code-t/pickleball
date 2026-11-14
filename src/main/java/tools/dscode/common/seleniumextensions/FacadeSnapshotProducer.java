package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.premap.DocVersion;
import dev.domengine.snapshot.CompositeSnapshotBuilder;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.tree.DomNode;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.BidiAdapter;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.FrameNode;
import tools.dscode.browser.SeleniumAdapter;
import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Page snapshot through {@link BrowserFacade}. One walk per browsing context,
 * one screenshot, and {@link CompositeSnapshotBuilder} in this process.
 */
public final class FacadeSnapshotProducer {
    private static final String WALK = walkScript();

    private FacadeSnapshotProducer() {
    }

    public static JsonNode selenium(WebDriver driver, SnapshotOptions options) {
        return capture(new SeleniumAdapter(driver), options, platformOf(driver, "selenium"), "selenium-fallback");
    }

    public static JsonNode capture(BrowserFacade facade, SnapshotOptions options, String platform, String transport) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        installCounter(facade);
        FrameNode tree = facade.frameTree();
        List<FrameNode> frames = new ArrayList<>();
        flatten(tree, frames);
        if (frames.isEmpty()) frames.add(FrameNode.leaf("main", null, ""));
        List<JsonNode> walks = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            FrameNode frame = frames.get(i);
            Object value = evaluate(facade, frame, i == 0, WALK);
            walks.add(asWalk(value));
        }
        byte[] png = facade.screenshot(Map.of("format", "png"));
        String version = docVersion(facade, frames.get(0).id());
        DomNode root = stitch(walks, frames);
        JsonNode top = walks.get(0);
        String html = sources(walks, frames);
        SnapshotContext context = SnapshotContext.captured(
                used,
                platform,
                transport,
                version,
                html,
                png,
                top.path("innerWidth").asDouble(0),
                top.path("innerHeight").asDouble(0),
                top.path("devicePixelRatio").asDouble(1),
                top.path("scrollX").asDouble(0),
                top.path("scrollY").asDouble(0),
                top.path("visual").path("offsetX").asDouble(0),
                top.path("visual").path("offsetY").asDouble(0),
                top.path("visual").path("scale").asDouble(1)
        );
        ObjectNode json = CompositeSnapshotBuilder.build(root, context).toJson();
        replaceFrames(json, frames, root);
        writeOut(used, png, json);
        CompositeLog.info("snapshot", "snapshot " + transport + " platform=" + platform + " doc=" + version);
        return json;
    }

    static String platformOf(WebDriver driver, String protocol) {
        if ("bidi".equals(protocol)) return "firefox";
        String name = browserName(driver);
        if (name.contains("edge")) return "edge";
        if (name.contains("chromium")) return "chromium";
        if (name.contains("firefox")) return "firefox";
        return "chrome";
    }

    private static void installCounter(BrowserFacade facade) {
        try {
            String script = "bidi".equals(facade.protocol()) ? DocVersion.INSTALL : DocVersion.SCRIPT;
            facade.addPreload(script);
        } catch (RuntimeException failure) {
            CompositeLog.info("snapshot", "doc-version preload " + failure.getMessage());
        }
    }

    private static String docVersion(BrowserFacade facade, String frameId) {
        try {
            String script = "bidi".equals(facade.protocol()) ? DocVersion.INSTALL : DocVersion.SCRIPT;
            Object value = evaluate(facade, FrameNode.leaf(frameId, null, ""), true, script);
            return DocVersion.of(frameId, count(value));
        } catch (RuntimeException failure) {
            CompositeLog.info("snapshot", "doc-version " + failure.getMessage());
            return DocVersion.of(frameId, 0);
        }
    }

    private static long count(Object value) {
        if (value instanceof Number number) return number.longValue();
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) text = text.substring(1, text.length() - 1);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static Object evaluate(BrowserFacade facade, FrameNode frame, boolean root, String script) {
        String protocol = facade.protocol() == null ? "" : facade.protocol();
        String wrapped = wrap(protocol, script);
        if (root || "selenium".equals(protocol)) {
            return facade.evaluateIsolated(DocVersion.WORLD, wrapped);
        }
        if ("bidi".equals(protocol)) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("context", frame.id());
            target.put("sandbox", DocVersion.WORLD);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("functionDeclaration", script.trim().startsWith("function") ? script.trim() : wrapped);
            params.put("awaitPromise", false);
            params.put("target", target);
            params.put("arguments", List.of());
            return unwrap(facade.send("script.callFunction", params));
        }
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("frameId", frame.id());
        world.put("worldName", DocVersion.WORLD);
        world.put("grantUniveralAccess", true);
        Object created = facade.send("Page.createIsolatedWorld", world);
        int context = Jsons.MAPPER.valueToTree(created).path("executionContextId").asInt(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", wrap("cdp", script));
        params.put("returnByValue", true);
        if (context > 0) params.put("contextId", context);
        return unwrap(facade.send("Runtime.evaluate", params));
    }

    private static String wrap(String protocol, String script) {
        String body = script == null ? "" : script.trim();
        if ("bidi".equals(protocol)) return body;
        if (body.startsWith("(")) {
            return "selenium".equals(protocol) ? "return " + body : body;
        }
        if (!body.startsWith("function")) body = "function(){\n" + body + "\n}";
        if ("selenium".equals(protocol)) return "return (" + body + ")();";
        return "(" + body + ")()";
    }

    private static Object unwrap(Object value) {
        if (value == null) return null;
        return BidiAdapter.remoteValue(Jsons.MAPPER.valueToTree(value));
    }

    private static DomNode stitch(List<JsonNode> walks, List<FrameNode> frames) {
        DomNode root = document(walks.isEmpty() ? null : walks.get(0), 1);
        List<DomNode> iframes = new ArrayList<>();
        collectIframes(root, iframes);
        int next = 1;
        long id = maxId(root) + 1;
        for (DomNode iframe : iframes) {
            if (next >= walks.size()) break;
            iframe.iframe = true;
            DomNode child = document(walks.get(next), id);
            id = maxId(child) + 1;
            iframe.children.add(child);
            next++;
        }
        place(root, 0, 0, walks.isEmpty() ? null : walks.get(0));
        return root;
    }

    private static DomNode document(JsonNode walk, long firstId) {
        DomNode doc = new DomNode();
        doc.nodeType = 9;
        doc.nodeName = "#document";
        doc.backendNodeId = firstId;
        if (walk != null && walk.path("root").isObject()) {
            long[] id = {firstId + 1};
            doc.children.add(element(walk.path("root"), id, walk));
        }
        return doc;
    }

    private static DomNode element(JsonNode raw, long[] id, JsonNode walk) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = raw.path("tag").asText("");
        node.nodeName = node.localName.toUpperCase(Locale.ROOT);
        node.backendNodeId = id[0]++;
        node.iframe = "iframe".equals(node.localName);
        JsonNode attributes = raw.path("attributes");
        if (attributes.isObject()) {
            attributes.fields().forEachRemaining(entry -> node.attributes.put(entry.getKey(), entry.getValue().asText("")));
        }
        node.role = raw.path("role").asText("");
        node.accessibleName = raw.path("name").asText("");
        if (raw.path("states").isArray()) {
            for (JsonNode state : raw.path("states")) node.states.add(state.asText(""));
        }
        JsonNode style = raw.path("style");
        if (style.isObject()) {
            style.fields().forEachRemaining(entry -> node.computed.put(entry.getKey(), entry.getValue().asText("")));
            String display = node.computed.getOrDefault("display", "");
            String visibility = node.computed.getOrDefault("visibility", "");
            String opacity = node.computed.getOrDefault("opacity", "");
            if ("none".equals(display) || "hidden".equals(visibility) || "collapse".equals(visibility) || "0".equals(opacity)) {
                node.displayed = false;
            }
        }
        double scrollX = walk.path("scrollX").asDouble(0);
        double scrollY = walk.path("scrollY").asDouble(0);
        JsonNode rect = raw.path("rect");
        if (rect.isObject()) {
            node.hasFrameDocument = true;
            node.frameDocumentX = rect.path("x").asDouble(0) + scrollX;
            node.frameDocumentY = rect.path("y").asDouble(0) + scrollY;
            node.frameDocumentWidth = rect.path("width").asDouble(0);
            node.frameDocumentHeight = rect.path("height").asDouble(0);
        }
        if (raw.path("children").isArray()) {
            for (JsonNode child : raw.path("children")) node.children.add(element(child, id, walk));
        }
        return node;
    }

    private static void place(DomNode node, double originX, double originY, JsonNode top) {
        double scrollX = top == null ? 0 : top.path("scrollX").asDouble(0);
        double scrollY = top == null ? 0 : top.path("scrollY").asDouble(0);
        double offsetX = top == null ? 0 : top.path("visual").path("offsetX").asDouble(0);
        double offsetY = top == null ? 0 : top.path("visual").path("offsetY").asDouble(0);
        double scale = top == null ? 1 : top.path("visual").path("scale").asDouble(1);
        if (!(scale > 0)) scale = 1;
        if (node != null && node.hasFrameDocument) {
            double x = originX + node.frameDocumentX;
            double y = originY + node.frameDocumentY;
            node.x = x;
            node.y = y;
            node.width = node.frameDocumentWidth;
            node.height = node.frameDocumentHeight;
            node.boxRead = true;
            node.hasBox = true;
            node.hasLayoutViewport = true;
            node.layoutViewportX = x - scrollX;
            node.layoutViewportY = y - scrollY;
            node.layoutViewportWidth = node.width;
            node.layoutViewportHeight = node.height;
            node.hasVisual = true;
            node.visualX = (node.layoutViewportX - offsetX) * scale;
            node.visualY = (node.layoutViewportY - offsetY) * scale;
            node.visualWidth = node.width * scale;
            node.visualHeight = node.height * scale;
        }
        if (node == null) return;
        for (DomNode child : node.children) {
            double nextX = originX;
            double nextY = originY;
            if (node.iframe && node.hasFrameDocument && child.nodeType == 9) {
                nextX = node.x;
                nextY = node.y;
            }
            place(child, nextX, nextY, top);
        }
    }

    /** BiDi returns object graphs as remote handles. A JSON string comes back by value. */
    private static JsonNode asWalk(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return Jsons.MAPPER.readTree(trimmed);
                } catch (java.io.IOException ignored) {
                    return Jsons.MAPPER.valueToTree(value);
                }
            }
        }
        JsonNode node = Jsons.MAPPER.valueToTree(value);
        if (node.isTextual()) {
            String trimmed = node.asText().trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return Jsons.MAPPER.readTree(trimmed);
                } catch (java.io.IOException ignored) {
                    return node;
                }
            }
        }
        return node;
    }

    private static String sources(List<JsonNode> walks, List<FrameNode> frames) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < walks.size(); i++) {
            if (html.length() > 0) html.append('\n');
            String id = i < frames.size() ? frames.get(i).id() : "";
            html.append("<!-- frame:").append(id).append(" -->\n");
            html.append(walks.get(i).path("outerHTML").asText(""));
        }
        return html.toString();
    }

    private static void replaceFrames(ObjectNode json, List<FrameNode> frames, DomNode root) {
        List<DomNode> iframes = new ArrayList<>();
        collectIframes(root, iframes);
        var array = json.putArray("frames");
        int nested = 0;
        for (FrameNode frame : frames) {
            ObjectNode one = array.addObject();
            one.put("id", frame.id());
            one.put("parent_id", frame.parentId() == null ? "" : frame.parentId());
            one.put("url", frame.url() == null ? "" : frame.url());
            String path = "";
            double x = 0;
            double y = 0;
            if (frame.parentId() != null && !frame.parentId().isBlank() && nested < iframes.size()) {
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
        if (node.iframe && node.nodeType == 1) into.add(node);
        for (DomNode child : node.children) collectIframes(child, into);
    }

    private static void flatten(FrameNode node, List<FrameNode> into) {
        if (node == null) return;
        into.add(node);
        for (FrameNode child : node.children()) flatten(child, into);
    }

    private static long maxId(DomNode node) {
        if (node == null) return 0;
        long id = node.backendNodeId;
        for (DomNode child : node.children) id = Math.max(id, maxId(child));
        return id;
    }

    private static void writeOut(SnapshotOptions options, byte[] png, ObjectNode json) {
        if (options.outDir() == null || options.outDir().isBlank()) return;
        try {
            Path dir = Path.of(options.outDir());
            Files.createDirectories(dir);
            if (png != null) Files.write(dir.resolve("screenshot.png"), png);
            Files.writeString(dir.resolve("snapshot.json"), Jsons.pretty(json));
        } catch (IOException failure) {
            throw new IllegalStateException("snapshot out-dir: " + failure.getMessage());
        }
    }

    private static String browserName(WebDriver driver) {
        if (!(driver instanceof HasCapabilities has) || has.getCapabilities() == null) return "";
        Object name = has.getCapabilities().getBrowserName();
        return name == null ? "" : String.valueOf(name).toLowerCase(Locale.ROOT);
    }

    private static String walkScript() {
        try (InputStream in = FacadeSnapshotProducer.class.getResourceAsStream("/dev/domengine/snapshot/page-walk.js")) {
            if (in == null) throw new IllegalStateException("page-walk.js is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("page-walk.js: " + failure.getMessage());
        }
    }
}
