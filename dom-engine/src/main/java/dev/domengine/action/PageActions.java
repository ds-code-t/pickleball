package dev.domengine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.Query;
import dev.domengine.session.BrowserSession;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Browser actions over CDP. One match is required. */
public final class PageActions {
    public static final List<String> VERBS = List.of(
            "click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag", "eval"
    );

    private PageActions() {
    }

    public static ObjectNode act(BrowserSession session, String verb, Map<String, String> flags) {
        LiveTree tree = session.tree();
        tree.root.linkParents();
        if ("drag".equals(verb)) return drag(session, tree, flags);
        DomNode node = one(tree, flags);
        if (!node.hasBox) tree.enrich(session.connection(), List.of(node), 0, 0);
        double x = node.hasBox ? node.x + node.width / 2 : 1;
        double y = node.hasBox ? node.y + node.height / 2 : 1;
        CdpConnection cdp = session.connection();
        ObjectNode result = switch (verb) {
            case "click" -> mouseClick(cdp, x, y, "left", 1);
            case "dblclick" -> mouseClick(cdp, x, y, "left", 2);
            case "contextclick" -> mouseClick(cdp, x, y, "right", 1);
            case "type" -> type(cdp, x, y, flags.getOrDefault("text", ""), "true".equals(flags.get("append")), "true".equals(flags.get("submit")));
            case "scroll" -> scroll(cdp, x, y, number(flags.get("dx")), number(flags.get("dy")));
            case "key" -> key(cdp, flags.get("key"));
            case "submit" -> submit(cdp, node);
            case "eval" -> eval(cdp, node, flags.get("source"));
            default -> throw new CdpException("invalid action: " + verb);
        };
        result.put("ok", true);
        result.put("session", session.id);
        result.put("node", node.id());
        EngineLog.info("action", verb + " " + node.id());
        return result;
    }

    private static ObjectNode drag(BrowserSession session, LiveTree tree, Map<String, String> flags) {
        DomNode source = one(tree, Map.of("css", selector(flags.get("source"))));
        DomNode target = one(tree, Map.of("css", selector(flags.get("target"))));
        if (!source.hasBox) tree.enrich(session.connection(), List.of(source), 0, 0);
        if (!target.hasBox) tree.enrich(session.connection(), List.of(target), 0, 0);
        double x1 = source.hasBox ? source.x + source.width / 2 : 1;
        double y1 = source.hasBox ? source.y + source.height / 2 : 1;
        double x2 = target.hasBox ? target.x + target.width / 2 : 20;
        double y2 = target.hasBox ? target.y + target.height / 2 : 20;
        CdpConnection cdp = session.connection();
        mouse(cdp, "mousePressed", x1, y1, "left", 1);
        mouse(cdp, "mouseMoved", x2, y2, "left", 1);
        mouse(cdp, "mouseReleased", x2, y2, "left", 1);
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("session", session.id);
        result.putObject("source").put("id", source.id());
        result.putObject("target").put("id", target.id());
        EngineLog.info("action", "drag " + source.id() + " " + target.id());
        return result;
    }

    private static String selector(String raw) {
        if (raw == null || raw.isBlank()) throw new CdpException("invalid drag locator");
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) return trimmed;
        return trimmed;
    }

    private static DomNode one(LiveTree tree, Map<String, String> flags) {
        JsonNode found = Query.run(tree.root, flags);
        JsonNode nodes = found.get("nodes");
        if (nodes == null || nodes.isEmpty()) throw new CdpException("not found");
        if (nodes.size() > 1) throw new CdpException("ambiguous");
        String handle = nodes.get(0).path("handle").asText("");
        DomNode node = find(tree.root, handle);
        if (node == null) throw new CdpException("not found");
        return node;
    }

    private static DomNode find(DomNode node, String handle) {
        if (handle.equals(node.id())) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, handle);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = find(shadow, handle);
            if (found != null) return found;
        }
        return null;
    }

    private static ObjectNode mouseClick(CdpConnection cdp, double x, double y, String button, int count) {
        mouse(cdp, "mousePressed", x, y, button, count);
        mouse(cdp, "mouseReleased", x, y, button, count);
        ObjectNode result = Jsons.object();
        result.put("x", x);
        result.put("y", y);
        result.put("button", button);
        result.put("clickCount", count);
        return result;
    }

    private static void mouse(CdpConnection cdp, String type, double x, double y, String button, int count) {
        ObjectNode params = Jsons.object();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("button", button);
        params.put("clickCount", count);
        cdp.call("Input.dispatchMouseEvent", params, java.time.Duration.ofSeconds(5));
    }

    private static ObjectNode type(CdpConnection cdp, double x, double y, String text, boolean append, boolean submit) {
        mouseClick(cdp, x, y, "left", 1);
        if (!append) key(cdp, "CONTROL+A");
        ObjectNode insert = Jsons.object();
        insert.put("text", text == null ? "" : text);
        cdp.call("Input.insertText", insert, java.time.Duration.ofSeconds(5));
        if (submit) key(cdp, "ENTER");
        ObjectNode result = Jsons.object();
        result.put("text", text == null ? "" : text);
        result.put("append", append);
        return result;
    }

    private static ObjectNode scroll(CdpConnection cdp, double x, double y, double dx, double dy) {
        ObjectNode params = Jsons.object();
        params.put("type", "mouseWheel");
        params.put("x", x);
        params.put("y", y);
        params.put("deltaX", dx);
        params.put("deltaY", dy);
        cdp.call("Input.dispatchMouseEvent", params, java.time.Duration.ofSeconds(5));
        ObjectNode result = Jsons.object();
        result.put("dx", dx);
        result.put("dy", dy);
        return result;
    }

    private static ObjectNode key(CdpConnection cdp, String key) {
        if (key == null || key.isBlank()) throw new CdpException("invalid key");
        if (key.contains(" ") && key.contains("+")) throw new CdpException("invalid key");
        String[] parts = key.split("\\+");
        for (String part : parts) {
            if (!KNOWN.contains(part.toUpperCase(Locale.ROOT)) && part.length() != 1) throw new CdpException("invalid key");
        }
        for (int i = 0; i < parts.length - 1; i++) press(cdp, parts[i], "keyDown");
        press(cdp, parts[parts.length - 1], "keyDown");
        press(cdp, parts[parts.length - 1], "keyUp");
        for (int i = parts.length - 2; i >= 0; i--) press(cdp, parts[i], "keyUp");
        ObjectNode result = Jsons.object();
        result.put("key", key);
        return result;
    }

    private static void press(CdpConnection cdp, String key, String type) {
        ObjectNode params = Jsons.object();
        params.put("type", type);
        params.put("key", key.toUpperCase(Locale.ROOT));
        cdp.call("Input.dispatchKeyEvent", params, java.time.Duration.ofSeconds(5));
    }

    private static ObjectNode submit(CdpConnection cdp, DomNode node) {
        String source = "function(){ var el=this; var form=el.tagName==='FORM'?el:(el.form||null); if(!form) return {submitted:false,reason:'no-form'}; var button=(el.tagName==='BUTTON'||el.type==='submit')?el:null; if(button) form.requestSubmit(button); else form.requestSubmit(); return {submitted:true}; }";
        ObjectNode value = callOn(cdp, node, source);
        ObjectNode result = Jsons.object();
        result.put("submitted", !value.toString().contains("no-form"));
        result.put("value", value.path("result").path("value").asText(""));
        return result;
    }

    private static ObjectNode eval(CdpConnection cdp, DomNode node, String source) {
        if (source == null || source.isBlank()) throw new CdpException("eval source is empty");
        ObjectNode called = callOn(cdp, node, source);
        ObjectNode result = Jsons.object();
        result.put("kind", "eval");
        result.put("backend_node_id", node.backendNodeId);
        result.set("value", called.path("result").path("value").isMissingNode() ? Jsons.object().put("value", "") : called.path("result").get("value"));
        return result;
    }

    private static ObjectNode callOn(CdpConnection cdp, DomNode node, String source) {
        ObjectNode resolve = Jsons.object();
        resolve.put("nodeId", node.nodeId);
        JsonNode resolved = cdp.call("DOM.resolveNode", resolve, java.time.Duration.ofSeconds(5));
        String objectId = resolved.path("object").path("objectId").asText("");
        ObjectNode call = Jsons.object();
        call.put("functionDeclaration", source);
        call.put("objectId", objectId);
        call.put("returnByValue", true);
        return (ObjectNode) cdp.call("Runtime.callFunctionOn", call, java.time.Duration.ofSeconds(5));
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        return Double.parseDouble(raw.trim());
    }

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            "ENTER", "TAB", "ESCAPE", "ARROWUP", "ARROWDOWN", "ARROWLEFT", "ARROWRIGHT",
            "BACKSPACE", "DELETE", "HOME", "END", "PAGEUP", "PAGEDOWN", "SPACE",
            "CONTROL", "SHIFT", "ALT", "META",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
    );
}
