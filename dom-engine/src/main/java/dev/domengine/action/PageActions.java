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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Browser actions over CDP. One match is required. */
public final class PageActions {
    public static final List<String> VERBS = List.of(
            "click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag", "eval", "hover"
    );

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> FORCED = new java.util.concurrent.ConcurrentHashMap<>();

    private PageActions() {
    }

    public static void releaseHover(BrowserSession session) {
        if (session == null) return;
        Long nodeId = FORCED.remove(session.id);
        if (nodeId == null || session.connection() == null) return;
        clearPseudo(session.connection(), nodeId);
    }

    public static ObjectNode act(BrowserSession session, String verb, Map<String, String> flags) {
        LiveTree tree = session.tree();
        tree.root.linkParents();
        if ("drag".equals(verb)) return drag(session, tree, flags);
        if ("eval".equals(verb) && flags.get("node-ids") != null && !flags.get("node-ids").isBlank()) {
            return evalMany(session, flags);
        }
        DomNode node = one(tree, flags, session.connection());
        if (!node.hasBox) tree.enrich(session.connection(), List.of(node), 0, 0);
        double x = node.hasBox ? node.x + node.width / 2 : 1;
        double y = node.hasBox ? node.y + node.height / 2 : 1;
        CdpConnection cdp = session.connection();
        ObjectNode result = switch (verb) {
            case "click" -> "option".equals(node.tag()) ? selectOption(cdp, node) : mouseClick(cdp, x, y, "left", 1);
            case "dblclick" -> mouseClick(cdp, x, y, "left", 2);
            case "contextclick" -> mouseClick(cdp, x, y, "right", 1);
            case "type" -> type(cdp, x, y, flags.getOrDefault("text", ""), "true".equals(flags.get("append")), "true".equals(flags.get("submit")));
            case "scroll" -> scroll(cdp, node, x, y, number(flags.get("dx")), number(flags.get("dy")));
            case "key" -> key(cdp, flags.get("key"));
            case "submit" -> submit(cdp, node);
            case "eval" -> eval(cdp, node, flags.get("source"));
            case "hover" -> hover(session, tree, node, flags);
            default -> throw new CdpException("invalid action: " + verb);
        };
        result.put("ok", true);
        result.put("session", session.id);
        result.put("node", node.id());
        EngineLog.info("action", verb + " " + node.id());
        return result;
    }

    private static ObjectNode drag(BrowserSession session, LiveTree tree, Map<String, String> flags) {
        DomNode source = one(tree, Map.of("css", selector(flags.get("source"))), session.connection());
        DomNode target = one(tree, Map.of("css", selector(flags.get("target"))), session.connection());
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

    private static DomNode one(LiveTree tree, Map<String, String> flags, CdpConnection cdp) {
        DomNode pinned = byIdentity(tree, flags, cdp);
        if (pinned != null) return pinned;
        if ("true".equals(flags.get("refind")) || flags.get("node-id") == null || flags.get("node-id").isBlank()) {
            JsonNode found = Query.run(tree.root, flags);
            JsonNode nodes = found.get("nodes");
            if (nodes == null || nodes.isEmpty()) throw new CdpException("not found");
            if (nodes.size() > 1) throw new CdpException("ambiguous");
            String handle = nodes.get(0).path("handle").asText("");
            DomNode node = find(tree.root, handle);
            if (node == null) throw new CdpException("not found");
            return node;
        }
        throw new CdpException("stale");
    }

    /** {@code --node-id} is a backend node id, or {@code frame:backend}. A miss is {@code stale} unless {@code --refind}. */
    private static DomNode byIdentity(LiveTree tree, Map<String, String> flags, CdpConnection cdp) {
        String raw = flags.get("node-id");
        if (raw == null || raw.isBlank()) return null;
        long backend;
        try {
            backend = backendOf(raw);
        } catch (RuntimeException failure) {
            if ("true".equals(flags.get("refind"))) return null;
            throw new CdpException("stale");
        }
        try {
            ObjectNode params = Jsons.object();
            params.put("backendNodeId", backend);
            JsonNode resolved = cdp.call("DOM.resolveNode", params, java.time.Duration.ofSeconds(5));
            if (resolved.path("object").path("objectId").asText("").isBlank()) {
                if ("true".equals(flags.get("refind"))) return null;
                throw new CdpException("stale");
            }
        } catch (CdpException failure) {
            if ("true".equals(flags.get("refind"))) return null;
            throw new CdpException("stale");
        }
        DomNode node = findBackend(tree.root, backend);
        if (node == null) {
            if ("true".equals(flags.get("refind"))) return null;
            throw new CdpException("stale");
        }
        EngineLog.info("identity", "node " + backend);
        return node;
    }

    private static long backendOf(String raw) {
        String text = raw.trim();
        int colon = text.lastIndexOf(':');
        String number = colon >= 0 ? text.substring(colon + 1) : text;
        return Long.parseLong(number);
    }

    private static DomNode findBackend(DomNode node, long backend) {
        if (node.backendNodeId == backend && node.nodeType == 1) return node;
        for (DomNode child : node.children) {
            DomNode found = findBackend(child, backend);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findBackend(shadow, backend);
            if (found != null) return found;
        }
        return null;
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

    /** A native option has no hit target. Selecting it is the click. */
    private static ObjectNode selectOption(CdpConnection cdp, DomNode node) {
        String source = "function(){ if(!this || this.tagName!=='OPTION') return false; this.selected=true; var select=this.closest('select'); if(select){ select.dispatchEvent(new Event('input',{bubbles:true})); select.dispatchEvent(new Event('change',{bubbles:true})); } return true; }";
        callOn(cdp, node, source);
        ObjectNode result = Jsons.object();
        result.put("selected", true);
        return result;
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

    private static ObjectNode scroll(CdpConnection cdp, DomNode node, double x, double y, double dx, double dy) {
        boolean intoView = false;
        if (node != null && node.nodeId > 0 && dx == 0 && dy == 0) {
            ObjectNode into = Jsons.object();
            into.put("nodeId", node.nodeId);
            try {
                cdp.call("DOM.scrollIntoViewIfNeeded", into, java.time.Duration.ofSeconds(5));
                intoView = true;
            } catch (RuntimeException ignored) {
                // A node with no box still reports the zero wheel below.
            }
        }
        if (!intoView) {
            ObjectNode params = Jsons.object();
            params.put("type", "mouseWheel");
            params.put("x", x);
            params.put("y", y);
            params.put("deltaX", dx);
            params.put("deltaY", dy);
            cdp.call("Input.dispatchMouseEvent", params, java.time.Duration.ofSeconds(5));
        }
        ObjectNode result = Jsons.object();
        result.put("dx", dx);
        result.put("dy", dy);
        result.put("into_view", intoView);
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

    private static ObjectNode hover(BrowserSession session, LiveTree tree, DomNode node, Map<String, String> flags) {
        CdpConnection cdp = session.connection();
        if (node.nodeId > 0) {
            ObjectNode into = Jsons.object();
            into.put("nodeId", node.nodeId);
            try {
                cdp.call("DOM.scrollIntoViewIfNeeded", into, java.time.Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // A node with no box still gets the move at its last point.
            }
            tree.enrich(cdp, List.of(node), 0, 0);
        }
        double x = node.hasBox ? node.x + node.width / 2 : 1;
        double y = node.hasBox ? node.y + node.height / 2 : 1;
        mouse(cdp, "mouseMoved", x, y, "none", 0);
        if ("true".equals(flags.get("force-hover")) && node.nodeId > 0) {
            Long previous = FORCED.put(session.id, node.nodeId);
            if (previous != null && previous != node.nodeId) clearPseudo(cdp, previous);
            try {
                cdp.call("CSS.enable", Jsons.object(), java.time.Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // forcePseudoState enables the domain itself on current Chrome.
            }
            ObjectNode pseudo = Jsons.object();
            pseudo.put("nodeId", node.nodeId);
            pseudo.putArray("forcedPseudoClasses").add("hover");
            cdp.call("CSS.forcePseudoState", pseudo, java.time.Duration.ofSeconds(5));
        }
        EngineLog.info("hover", node.id());
        ObjectNode result = Jsons.object();
        result.put("x", x);
        result.put("y", y);
        return result;
    }

    private static void clearPseudo(CdpConnection cdp, long nodeId) {
        try {
            ObjectNode pseudo = Jsons.object();
            pseudo.put("nodeId", nodeId);
            pseudo.putArray("forcedPseudoClasses");
            cdp.call("CSS.forcePseudoState", pseudo, java.time.Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // The previous node may already be gone.
        }
    }

    /** One {@code Runtime.callFunctionOn} for every id. A missing id is {@code {"stale":true}} in its slot. */
    private static ObjectNode evalMany(BrowserSession session, Map<String, String> flags) {
        String source = flags.get("source");
        if (source == null || source.isBlank()) throw new CdpException("eval source is empty");
        String[] parts = flags.get("node-ids").split(",");
        CdpConnection cdp = session.connection();
        List<String> objectIds = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        boolean[] stale = new boolean[parts.length];
        String[] ids = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i].trim();
            ids[i] = raw;
            if (raw.isEmpty()) {
                stale[i] = true;
                continue;
            }
            try {
                ObjectNode resolve = Jsons.object();
                resolve.put("backendNodeId", backendOf(raw));
                JsonNode resolved = cdp.call("DOM.resolveNode", resolve, java.time.Duration.ofSeconds(5));
                String objectId = resolved.path("object").path("objectId").asText("");
                if (objectId.isBlank()) {
                    stale[i] = true;
                    continue;
                }
                slots.add(i);
                objectIds.add(objectId);
            } catch (RuntimeException failure) {
                stale[i] = true;
            }
        }
        JsonNode values = Jsons.object().putArray("value");
        if (!objectIds.isEmpty()) {
            String wrapped = "function(){ const fn = " + source + "; const out = [];"
                    + " for (let i = 0; i < arguments.length; i++) {"
                    + " const el = arguments[i];"
                    + " if (!el) { out.push({stale:true}); continue; }"
                    + " try { out.push(fn.call(el)); } catch (e) { out.push({error:String(e)}); }"
                    + " } return out; }";
            ObjectNode call = Jsons.object();
            call.put("functionDeclaration", wrapped);
            call.put("objectId", objectIds.get(0));
            call.put("returnByValue", true);
            com.fasterxml.jackson.databind.node.ArrayNode args = call.putArray("arguments");
            for (String objectId : objectIds) args.addObject().put("objectId", objectId);
            JsonNode called = cdp.call("Runtime.callFunctionOn", call, java.time.Duration.ofSeconds(5));
            values = called.path("result").path("value");
            EngineLog.info("batch", "callFunctionOn=1 nodes=" + objectIds.size());
        }
        ObjectNode result = Jsons.object();
        result.put("kind", "eval");
        com.fasterxml.jackson.databind.node.ArrayNode rows = result.putArray("values");
        int cursor = 0;
        for (int i = 0; i < parts.length; i++) {
            ObjectNode row = rows.addObject();
            row.put("node_id", ids[i]);
            if (stale[i]) {
                row.put("stale", true);
                continue;
            }
            JsonNode value = values.isArray() && cursor < values.size() ? values.get(cursor) : Jsons.object();
            cursor++;
            if (value.isObject() && value.path("stale").asBoolean(false)) row.put("stale", true);
            else row.set("value", value);
        }
        result.put("ok", true);
        result.put("session", session.id);
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
