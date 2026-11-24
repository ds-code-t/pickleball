package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fallback locators recorded on a v2 node and tried in order on replay. */
public final class IdentityChain {
    private IdentityChain() {
    }

    public static void attach(DomNode root, List<ObjectNode> nodes, SnapshotContext ctx) {
        if (nodes == null || nodes.isEmpty()) return;
        List<DomNode> elements = new ArrayList<>();
        walk(root, elements);
        Map<String, Integer> ids = new LinkedHashMap<>();
        Map<String, Integer> testids = new LinkedHashMap<>();
        Map<String, Integer> automation = new LinkedHashMap<>();
        Map<String, Integer> roles = new LinkedHashMap<>();
        for (DomNode node : elements) {
            tally(ids, attr(node, "id"));
            tally(testids, attr(node, "data-testid"));
            String auto = attr(node, "data-uia-automation-id");
            if (!auto.isBlank() && !auto.equals(attr(node, "id"))) tally(automation, auto);
            tally(roles, roleKey(node));
        }
        Map<String, DomNode> byHandle = new LinkedHashMap<>();
        for (DomNode node : elements) byHandle.put(node.id(), node);
        for (ObjectNode json : nodes) {
            DomNode node = byHandle.get(json.path("handle").asText(""));
            if (node == null) continue;
            json.set("identity", identity(node, ids, testids, automation, roles, ctx, root));
        }
    }

    public static ObjectNode replay(DomNode root, JsonNode chain) {
        ObjectNode result = Jsons.object();
        result.put("matched", "none");
        result.putNull("handle");
        result.put("locator", "");
        result.put("index", -1);
        if (root == null || chain == null || !chain.isArray()) return result;
        List<DomNode> elements = new ArrayList<>();
        walk(root, elements);
        int index = 0;
        for (JsonNode step : chain) {
            DomNode hit = match(root, elements, step);
            if (hit != null) {
                result.put("matched", step.path("kind").asText(""));
                result.put("handle", hit.id());
                if (hit.backendNodeId > 0) result.put("backend_node_id", hit.backendNodeId);
                result.put("locator", step.path("locator").asText(""));
                result.put("index", index);
                return result;
            }
            index++;
        }
        return result;
    }

    private static ObjectNode identity(
            DomNode node,
            Map<String, Integer> ids,
            Map<String, Integer> testids,
            Map<String, Integer> automation,
            Map<String, Integer> roles,
            SnapshotContext ctx,
            DomNode root
    ) {
        ArrayNode chain = Jsons.MAPPER.createArrayNode();
        String id = attr(node, "id");
        if (unique(ids, id)) step(chain, "stable", "id=" + id, "id");
        else if (unique(testids, attr(node, "data-testid"))) {
            String testid = attr(node, "data-testid");
            step(chain, "stable", "data-testid=" + testid, "data-testid");
        } else {
            String auto = attr(node, "data-uia-automation-id");
            if (!auto.isBlank() && !auto.equals(id) && unique(automation, auto)) {
                step(chain, "stable", "data-uia-automation-id=" + auto, "data-uia-automation-id");
            } else if (unique(roles, roleKey(node))) {
                step(chain, "stable", roleLocator(node), "role-name-section");
            }
        }
        for (String key : List.of("uia-runtime-id", "ax-path", "atspi-path")) {
            String value = attr(node, key);
            if (value.isBlank() || digits(value)) continue;
            step(chain, "native-path", key + "=" + value, key);
            break;
        }
        String fingerprint = fingerprint(node, ctx, root);
        if (!fingerprint.isBlank()) step(chain, "fingerprint", fingerprint, "fingerprint");
        if (node.xpath != null && !node.xpath.isBlank()) {
            String shadow = node.shadowPath == null ? "" : node.shadowPath;
            String frame = node.framePath == null ? "" : node.framePath;
            step(chain, "xpath", "xpath=" + node.xpath + ";shadow=" + shadow + ";frame=" + frame, "xpath");
        }
        ObjectNode identity = Jsons.object();
        identity.set("chain", chain);
        return identity;
    }

    private static DomNode match(DomNode root, List<DomNode> elements, JsonNode step) {
        String kind = step.path("kind").asText("");
        String locator = step.path("locator").asText("");
        return switch (kind) {
            case "stable" -> stable(elements, locator);
            case "native-path" -> nativePath(elements, locator);
            case "fingerprint" -> fingerprint(root, elements, locator);
            case "xpath" -> xpath(root, locator);
            default -> null;
        };
    }

    private static DomNode stable(List<DomNode> elements, String locator) {
        if (locator.startsWith("id=")) return only(elements, "id", locator.substring(3));
        if (locator.startsWith("data-testid=")) return only(elements, "data-testid", locator.substring("data-testid=".length()));
        if (locator.startsWith("data-uia-automation-id=")) {
            return only(elements, "data-uia-automation-id", locator.substring("data-uia-automation-id=".length()));
        }
        if (locator.startsWith("role=")) return onlyRole(elements, locator);
        return null;
    }

    private static DomNode only(List<DomNode> elements, String attribute, String value) {
        DomNode found = null;
        for (DomNode node : elements) {
            if (!value.equals(attr(node, attribute))) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    private static DomNode onlyRole(List<DomNode> elements, String locator) {
        String role = locator.substring("role=".length());
        int nameAt = role.indexOf("[name=");
        if (nameAt < 0) return null;
        String roleName = role.substring(0, nameAt);
        String name = quoted(role, nameAt + "[name=".length());
        int sectionAt = role.indexOf("[section=");
        String section = sectionAt < 0 ? "" : quoted(role, sectionAt + "[section=".length());
        DomNode found = null;
        for (DomNode node : elements) {
            if (!roleName.equals(Query.roleOf(node))) continue;
            if (!name.equals(node.accessibleName == null ? "" : node.accessibleName.trim())) continue;
            if (!section.equals(node.sectionPath == null ? "" : node.sectionPath)) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    private static DomNode nativePath(List<DomNode> elements, String locator) {
        int eq = locator.indexOf('=');
        if (eq <= 0) return null;
        String key = locator.substring(0, eq);
        String value = locator.substring(eq + 1);
        if (digits(value)) return null;
        return only(elements, key, value);
    }

    private static DomNode fingerprint(DomNode root, List<DomNode> elements, String locator) {
        Map<String, String> parts = parts(locator);
        for (DomNode node : elements) {
            if (parts.containsKey("dhash")) {
                if (node.paint == null || !node.paint.hasDhash) continue;
                long recorded = Long.parseUnsignedLong(parts.get("dhash"));
                TemplateMatch.Features left = new TemplateMatch.Features(recorded, 0, 0);
                TemplateMatch.Features right = new TemplateMatch.Features(node.paint.dhash, 0, 0);
                if (!TemplateMatch.prefilter(left, right)) continue;
            }
            if (parts.containsKey("text") && !parts.get("text").trim().equals(text(node))) continue;
            if (parts.containsKey("rel") && !near(parts.get("rel"), relative(node, root))) continue;
            return node;
        }
        return null;
    }

    private static DomNode xpath(DomNode root, String locator) {
        Map<String, String> parts = parts(locator);
        String path = parts.get("xpath");
        if (path == null || path.isBlank()) return null;
        return XPathIndex.resolve(root, parts.getOrDefault("frame", ""), path, parts.getOrDefault("shadow", ""));
    }

    private static boolean near(String recorded, String live) {
        if (live.isBlank()) return false;
        String[] left = recorded.split(",");
        String[] right = live.split(",");
        if (left.length != 4 || right.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(Double.parseDouble(left[i]) - Double.parseDouble(right[i])) > 0.05) return false;
        }
        return true;
    }

    private static String fingerprint(DomNode node, SnapshotContext ctx, DomNode root) {
        List<String> parts = new ArrayList<>();
        if (node.paint != null && node.paint.hasDhash) parts.add("dhash=" + Long.toUnsignedString(node.paint.dhash));
        String body = text(node);
        if (!body.isBlank()) parts.add("text=" + body);
        String rel = relative(node, ctx, root);
        if (!rel.isBlank()) parts.add("rel=" + rel);
        return String.join(";", parts);
    }

    private static String relative(DomNode node, SnapshotContext ctx, DomNode root) {
        if (!node.hasBox) return "";
        boolean screen = ctx != null && "true".equals(ctx.flags().get("screen-space"));
        double width = screen && root != null && root.width > 0 ? root.width : ctx == null ? 0 : ctx.viewportWidth();
        double height = screen && root != null && root.height > 0 ? root.height : ctx == null ? 0 : ctx.viewportHeight();
        if (width <= 0 || height <= 0) return "";
        return format(node.x / width) + "," + format(node.y / height) + "," + format(node.width / width) + "," + format(node.height / height);
    }

    private static String relative(DomNode node, DomNode root) {
        if (!node.hasBox) return "";
        DomNode sized = sized(root);
        double width = sized == null ? 0 : sized.width;
        double height = sized == null ? 0 : sized.height;
        if (width <= 0 || height <= 0) return "";
        return format(node.x / width) + "," + format(node.y / height) + "," + format(node.width / width) + "," + format(node.height / height);
    }

    private static DomNode sized(DomNode node) {
        if (node == null) return null;
        if (node.nodeType == 1 && node.hasBox && ("html".equals(node.localName) || "body".equals(node.localName))) return node;
        for (DomNode child : node.children) {
            DomNode found = sized(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String text(DomNode node) {
        if (node.accessibleName != null && !node.accessibleName.isBlank()) return node.accessibleName.trim();
        return HtmlTree.textOf(node).trim();
    }

    private static String roleKey(DomNode node) {
        String role = Query.roleOf(node);
        String name = node.accessibleName == null ? "" : node.accessibleName.trim();
        if (role.isBlank() && name.isBlank()) return "";
        String section = node.sectionPath == null ? "" : node.sectionPath;
        return role + "\0" + name + "\0" + section;
    }

    private static String roleLocator(DomNode node) {
        String name = node.accessibleName == null ? "" : node.accessibleName.trim();
        String section = node.sectionPath == null ? "" : node.sectionPath;
        return "role=" + Query.roleOf(node) + "[name=" + quote(name) + "][section=" + quote(section) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoted(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '"') return "";
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                value.append(text.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') return value.toString();
            value.append(c);
        }
        return value.toString();
    }

    private static void step(ArrayNode chain, String kind, String locator, String source) {
        ObjectNode step = chain.addObject();
        step.put("kind", kind);
        step.put("locator", locator);
        step.put("source", source);
    }

    private static void tally(Map<String, Integer> counts, String value) {
        if (value == null || value.isBlank()) return;
        counts.merge(value, 1, Integer::sum);
    }

    private static boolean unique(Map<String, Integer> counts, String value) {
        return value != null && !value.isBlank() && counts.getOrDefault(value, 0) == 1;
    }

    private static boolean digits(String value) {
        if (value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    private static String attr(DomNode node, String name) {
        if (node == null) return "";
        String value = node.attributes.get(name);
        return value == null ? "" : value.trim();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Map<String, String> parts(String locator) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : locator.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            parts.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return parts;
    }

    private static void walk(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1 && !node.shadowRoot) into.add(node);
        for (DomNode child : node.children) walk(child, into);
        for (DomNode shadow : node.shadowRoots) walk(shadow, into);
    }
}
