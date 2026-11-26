package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Collaboration state on snap and find rows: field values, {@code data-pkb-purpose}, badge counts,
 * highlight, guide step, card, and chat message attributes. Attributes come from the captured tree.
 * Values are one batched {@code Runtime.callFunctionOn} for the form controls in the main session.
 */
public final class CollabRows {
    /** Row keys this class may add, in print order. {@code --include} narrows them by group. */
    static final List<String> KEYS = List.of("value", "checked", "purpose", "badge", "highlight", "guide", "card", "msg");

    private static final Set<String> FIELDS = Set.of("input", "textarea", "select");

    private static final String READ = """
            function() {
              const out = [];
              for (let i = 0; i < arguments.length; i++) {
                const el = arguments[i];
                if (!el || el.nodeType !== 1) { out.push(null); continue; }
                const type = String(el.type || el.getAttribute('type') || '').toLowerCase();
                const auto = String(el.getAttribute('autocomplete') || '').toLowerCase();
                const secret = type === 'password' || auto.indexOf('password') >= 0 || auto.indexOf('cc-') >= 0
                  || auto.indexOf('one-time-code') >= 0 || el.getAttribute('data-sensitive') === 'true';
                let value = '';
                if (el.tagName === 'SELECT') {
                  value = Array.from(el.selectedOptions || []).map(function (o) { return o.label || o.value; }).join('|');
                } else if (type === 'file') {
                  value = Array.from(el.files || []).map(function (f) { return f.name; }).join('|');
                } else if ('value' in el) {
                  value = String(el.value == null ? '' : el.value);
                }
                if (secret && value) value = 'redacted';
                const box = type === 'checkbox' || type === 'radio';
                out.push({value: value.length > 200 ? value.slice(0, 200) : value, checked: box ? (el.checked === true) : null});
              }
              return out;
            }
            """;

    private CollabRows() {
    }

    /** Adds collaboration keys to {@code row}. {@code live} carries the batched value read, keyed by backend id. */
    static void put(Map<String, Object> row, DomNode node, Map<Long, JsonNode> live) {
        if (row == null || node == null) return;
        JsonNode read = live == null ? null : live.get(node.backendNodeId);
        if (read != null) {
            if (read.hasNonNull("checked")) row.put("checked", read.path("checked").asBoolean(false));
            else row.put("value", read.path("value").asText(""));
        }
        String purpose = attr(node, "data-pkb-purpose");
        if (!purpose.isBlank()) row.put("purpose", String.join(",", purpose.trim().split("\\s+")));
        String badge = badge(node);
        if (!badge.isBlank()) row.put("badge", badge);
        // unhighlight removes data-pkb-style; a leftover data-pkb-highlight alone is not a highlight.
        String highlight = "";
        if ("style_modified".equals(attr(node, "data-pkb-style"))) {
            highlight = attr(node, "data-pkb-highlight");
            if (highlight.isBlank()) highlight = presetOf(attr(node, "data-pkb-css"));
        }
        if (!highlight.isBlank()) row.put("highlight", highlight);
        String guide = attr(node, "data-pkb-guide-step");
        if (!guide.isBlank()) row.put("guide", guide);
        DomNode card = up(node, "data-pkb-card-id");
        if (card != null) {
            String title = attr(card, "data-pkb-card-title");
            String state = attr(card, "data-pkb-card-state");
            row.put("card", attr(card, "data-pkb-card-id") + (title.isBlank() ? "" : " \"" + title + "\"")
                    + (state.isBlank() ? "" : " " + state));
        }
        DomNode msg = up(node, "data-pkb-msg-id");
        if (msg != null) {
            row.put("msg", attr(msg, "data-pkb-msg-id") + " " + attr(msg, "data-pkb-msg-author")
                    + " " + attr(msg, "data-pkb-msg-status"));
        }
    }

    /**
     * Chat bubbles are not controls, so they are rows of role {@code message}: the newest {@code limit}
     * nodes carrying {@code data-pkb-msg-id}, with {@code msg=<id> <author> <status>} and the text as name.
     */
    static List<Map<String, Object>> messages(DomNode root, int limit) {
        List<DomNode> found = new ArrayList<>();
        collect(root, found, 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        int from = Math.max(0, found.size() - Math.max(1, limit));
        for (DomNode node : found.subList(from, found.size())) {
            Map<String, Object> row = new LinkedHashMap<>();
            String text = text(node, new StringBuilder()).toString().replaceAll("\\s+", " ").trim();
            row.put("role", "message");
            row.put("name", text.length() > 120 ? text.substring(0, 120) : text);
            put(row, node, null);
            rows.add(row);
        }
        return rows;
    }

    private static void collect(DomNode node, List<DomNode> out, int depth) {
        if (node == null || depth > 200) return;
        boolean message = !attr(node, "data-pkb-msg-id").isEmpty() || node.attributes.containsKey("data-pkb-msg-id");
        if (depth > 0 && hiddenContainer(node) && !message) return;
        if (message) {
            if (!hiddenContainer(node)) out.add(node);
            return;
        }
        for (DomNode shadow : node.shadowRoots) collect(shadow, out, depth + 1);
        for (DomNode child : node.children) collect(child, out, depth + 1);
    }

    /** A collapsed or hidden container. The document root is not one, so depth 0 still walks. */
    private static boolean hiddenContainer(DomNode node) {
        if (node.attributes.containsKey("hidden") || node.states.contains("hidden") || node.states.contains("collapsed")) return true;
        if ("false".equals(node.attributes.get("aria-expanded"))) return true;
        return !node.displayed && !node.computed.isEmpty();
    }

    private static StringBuilder text(DomNode node, StringBuilder out) {
        if (node == null || out.length() > 400) return out;
        if (node.nodeType == 3 && node.nodeValue != null) out.append(node.nodeValue).append(' ');
        for (DomNode child : node.children) text(child, out);
        return out;
    }

    /** The {@code data-pkb-card-trail} JSON on {@code <html>}: the last few cards and their state. */
    static List<Object> trail(DomNode root) {
        DomNode html = htmlOf(root, 0);
        String raw = attr(html, "data-pkb-card-trail");
        if (raw.isBlank()) return List.of();
        try {
            JsonNode parsed = Jsons.MAPPER.readTree(raw);
            List<Object> out = new ArrayList<>();
            if (parsed.isArray()) for (JsonNode row : parsed) out.add(Jsons.MAPPER.convertValue(row, Map.class));
            return out;
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static DomNode htmlOf(DomNode node, int depth) {
        if (node == null || depth > 4) return null;
        if ("html".equalsIgnoreCase(node.localName)) return node;
        for (DomNode child : node.children) {
            DomNode found = htmlOf(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Preset names for the CSS {@code highlight} writes. Anything else is {@code custom}. */
    static String presetOf(String css) {
        String text = css == null ? "" : css.toLowerCase(Locale.ROOT);
        if (text.contains("#d22")) return "outline-red";
        if (text.contains("#fff3a0")) return "yellow";
        return text.isBlank() ? "" : "custom";
    }

    /** Values and checked state for form controls in the main session, one CDP call per batch. */
    static Map<Long, JsonNode> values(CdpConnection cdp, List<DomNode> nodes) {
        Map<Long, JsonNode> out = new LinkedHashMap<>();
        if (cdp == null || nodes == null || nodes.isEmpty()) return out;
        List<DomNode> fields = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (DomNode node : nodes) {
            if (!isField(node) || node.backendNodeId <= 0) continue;
            if (ids.size() >= 80) break;
            if (node.cdpSession != null && !node.cdpSession.isBlank()) continue;
            try {
                ObjectNode params = Jsons.object();
                params.put("backendNodeId", node.backendNodeId);
                JsonNode resolved = cdp.call("DOM.resolveNode", params, Duration.ofSeconds(5));
                String objectId = resolved.path("object").path("objectId").asText("");
                if (objectId.isBlank()) continue;
                fields.add(node);
                ids.add(objectId);
            } catch (RuntimeException ignored) {
                // A node that no longer resolves has no value to show.
            }
        }
        if (ids.isEmpty()) return out;
        try {
            ObjectNode call = Jsons.object();
            call.put("functionDeclaration", READ);
            call.put("objectId", ids.get(0));
            call.put("returnByValue", true);
            ArrayNode args = call.putArray("arguments");
            for (String id : ids) args.addObject().put("objectId", id);
            JsonNode values = cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5)).path("result").path("value");
            for (int i = 0; i < fields.size() && values.isArray() && i < values.size(); i++) {
                if (values.get(i) != null && !values.get(i).isNull()) out.put(fields.get(i).backendNodeId, values.get(i));
            }
        } catch (RuntimeException ignored) {
            // Rows keep their attribute keys without a value.
        }
        return out;
    }

    /** {@code --include=values,highlights,cards,guide,placements,purpose,chat}. Blank keeps every key. */
    static void narrow(List<Map<String, Object>> rows, String include) {
        if (rows == null || include == null || include.isBlank()) return;
        Set<String> keep = new java.util.HashSet<>();
        for (String raw : include.split(",")) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "values", "value" -> { keep.add("value"); keep.add("checked"); }
                case "highlights", "highlight" -> keep.add("highlight");
                case "cards", "card" -> keep.add("card");
                case "guide", "guides" -> keep.add("guide");
                case "purpose", "purposes", "placements", "placement" -> { keep.add("purpose"); keep.add("badge"); }
                case "chat", "msg", "messages" -> keep.add("msg");
                default -> { }
            }
        }
        for (Map<String, Object> row : rows) {
            for (String key : KEYS) if (!keep.contains(key)) row.remove(key);
        }
    }

    static boolean isField(DomNode node) {
        if (node == null) return false;
        String tag = node.localName == null ? "" : node.localName.toLowerCase(Locale.ROOT);
        if (tag.isBlank() && node.nodeName != null) tag = node.nodeName.toLowerCase(Locale.ROOT);
        return FIELDS.contains(tag);
    }

    private static String badge(DomNode node) {
        String own = attr(node, "data-pkb-badge");
        if (!own.isBlank()) return own;
        for (DomNode child : node.children) {
            String found = attr(child, "data-pkb-badge");
            if (!found.isBlank()) return found;
        }
        return "";
    }

    private static DomNode up(DomNode node, String attribute) {
        int guard = 0;
        for (DomNode cursor = node; cursor != null && guard++ < 64; cursor = cursor.parent) {
            if (!attr(cursor, attribute).isBlank()) return cursor;
        }
        return null;
    }

    static String attr(DomNode node, String name) {
        if (node == null) return "";
        String value = node.attributes.get(name);
        return value == null ? "" : value;
    }
}
