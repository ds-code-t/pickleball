package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pipeline query over one context tree. The CLI, HTTP, and MCP only forward the stage string. */
public final class Query {
    public static final List<String> ENGINES = List.of(
            "css", "xpath", "text", "role", "frame", "shadow", "near", "below", "desc", "a11y", "image"
    );
    public static final List<String> QUERY_FLAGS = List.of(
            "within", "nesting", "nth", "every", "count-only", "geometry", "pierce", "point", "viewport"
    );

    private Query() {
    }

    public record Stage(String engine, String expr, String why, int count, long ms) {
    }

    public static ObjectNode run(DomNode root, Map<String, String> flags) {
        if (root == null) throw new CdpException("query has no document");
        String viewport = flags.get("viewport");
        boolean armed = viewport != null && !viewport.isBlank();
        if (armed) Engines.useViewport(TemplateMatch.read(Path.of(viewport)));
        try {
            return runArmed(root, flags);
        } finally {
            if (armed) Engines.useViewport(null);
        }
    }

    private static ObjectNode runArmed(DomNode root, Map<String, String> flags) {
        String pipeline = pipelineOf(flags);
        if (pipeline == null) throw new CdpException("query requires --css, --xpath, --text, --pipeline, or --selector");
        List<String> parts = splitStages(pipeline);
        if (parts.isEmpty()) throw new CdpException("pipeline is empty");
        String pierce = flags.getOrDefault("pierce", "");
        List<DomNode> current = List.of(root);
        List<Stage> trace = new ArrayList<>();
        ObjectNode image = null;
        for (String part : parts) {
            String piece = part.trim();
            if (piece.isEmpty()) throw new CdpException("invalid pipeline stage: " + pipeline);
            long start = System.nanoTime();
            Expressions.Expr expr = Expressions.parse(piece);
            Hit hit = Expressions.eval(expr, current, root, pierce);
            current = hit.nodes;
            if (hit.image != null) image = hit.image;
            long ms = (System.nanoTime() - start) / 1_000_000L;
            String engine = expr instanceof Expressions.Stage stage ? stage.engine() : "expr";
            String shown = expr instanceof Expressions.Stage stage ? stage.expr() : piece;
            trace.add(new Stage(engine, shown, hit.why, current.size(), ms));
            EngineLog.info("query", engine + " " + hit.why);
        }
        requireWithin(root, flags.get("within"));
        current = within(current, flags.get("within"));
        current = nesting(current, flags.get("nesting"));
        current = nth(current, flags.get("nth"), flags.get("every"));
        if (flags.get("point") != null && !flags.get("point").isBlank()) current = hitAt(root, flags.get("point"));
        boolean geometry = "true".equals(flags.get("geometry"));
        boolean explain = "true".equals(flags.get("explain"));
        boolean countOnly = "true".equals(flags.get("count-only"));
        boolean strict = "true".equals(flags.get("strict"));
        if (strict && current.isEmpty()) throw new CdpException("not found");
        if (strict && current.size() > 1) throw new CdpException("ambiguous");
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("source", pipeline);
        if (countOnly) {
            result.put("count", current.size());
            result.put("count_only", true);
            result.putArray("nodes");
            result.putArray("composite");
        } else {
            ArrayNode nodes = result.putArray("nodes");
            ArrayNode composite = result.putArray("composite");
            for (DomNode node : current) {
                nodes.add(nodeJson(node, geometry));
                composite.add(compositeJson(node, geometry));
            }
        }
        if (explain) {
            ArrayNode explained = result.putArray("explain");
            for (Stage stage : trace) {
                ObjectNode one = explained.addObject();
                one.put("engine", stage.engine);
                one.put("expr", stage.expr);
                one.put("count", stage.count);
                one.put("why", stage.why);
                one.put("ms", stage.ms);
            }
        }
        if (image != null) result.set("image", image);
        if (!pierce.isBlank()) {
            result.put("pierce", pierce);
            EngineLog.info("query", "pierce " + pierce);
        }
        return result;
    }

    private static void requireWithin(DomNode root, String id) {
        if (id == null || id.isBlank()) return;
        if (!exists(root, id)) throw new CdpException("not found");
    }

    private static boolean exists(DomNode node, String id) {
        if (node == null) return false;
        if (id.equals(node.id()) || id.equals(Long.toString(node.backendNodeId))) return true;
        for (DomNode child : node.children) if (exists(child, id)) return true;
        for (DomNode shadow : node.shadowRoots) if (exists(shadow, id)) return true;
        return false;
    }

    private static List<DomNode> hitAt(DomNode root, String point) {
        String[] parts = point.split(",");
        if (parts.length != 2) throw new CdpException("invalid point");
        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        List<DomNode> hits = new ArrayList<>();
        for (DomNode node : dev.domengine.tree.ContextWalker.list(root, dev.domengine.tree.ContextWalker.Order.DOCUMENT)) {
            if (node.nodeType != 1 || !node.hasBox || !node.displayed) continue;
            if (x >= node.x && y >= node.y && x < node.x + node.width && y < node.y + node.height) hits.add(node);
        }
        if (hits.isEmpty()) return List.of();
        return List.of(hits.get(hits.size() - 1));
    }

    private static List<DomNode> within(List<DomNode> nodes, String id) {
        if (id == null || id.isBlank()) return nodes;
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : nodes) {
            if (id.equals(node.id()) || id.equals(Long.toString(node.backendNodeId))) {
                kept.add(node);
                continue;
            }
            DomNode cursor = node.parent;
            boolean inside = false;
            while (cursor != null) {
                if (id.equals(cursor.id()) || id.equals(Long.toString(cursor.backendNodeId))) {
                    inside = true;
                    break;
                }
                cursor = cursor.parent;
            }
            if (inside) kept.add(node);
        }
        if (kept.isEmpty() && nodes.isEmpty()) return kept;
        return kept;
    }

    private static List<DomNode> nesting(List<DomNode> nodes, String mode) {
        if (mode == null || mode.isBlank()) return nodes;
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : nodes) {
            boolean drop = false;
            for (DomNode other : nodes) {
                if (other == node) continue;
                if ("deepest".equals(mode) && node.containsNode(other)) drop = true;
                if ("outermost".equals(mode) && other.containsNode(node)) drop = true;
            }
            if (!drop) kept.add(node);
        }
        EngineLog.info("query", "nesting " + mode);
        return kept;
    }

    private static List<DomNode> nth(List<DomNode> nodes, String nth, String every) {
        if ((nth == null || nth.isBlank()) && (every == null || every.isBlank())) return nodes;
        int start = 0;
        if (nth != null && !nth.isBlank()) {
            start = Integer.parseInt(nth.trim());
            if (start == -1) start = nodes.size() - 1;
        }
        int step = 0;
        if (every != null && !every.isBlank()) {
            step = Integer.parseInt(every.trim());
            if (step < 1) throw new CdpException("every step below 1 is invalid");
        }
        if (start < 0 || start >= nodes.size()) return List.of();
        if (step == 0) return List.of(nodes.get(start));
        List<DomNode> kept = new ArrayList<>();
        for (int i = start; i < nodes.size(); i += step) kept.add(nodes.get(i));
        return kept;
    }

    public static String pipelineOf(Map<String, String> flags) {
        if (flags.containsKey("pipeline") && !flags.get("pipeline").isBlank()) return flags.get("pipeline").trim();
        if (flags.containsKey("expr") && !flags.get("expr").isBlank()) return flags.get("expr").trim();
        if (flags.containsKey("css")) return "css=" + flags.get("css");
        if (flags.containsKey("xpath")) return "xpath=" + flags.get("xpath");
        if (flags.containsKey("pw")) return "css=" + flags.get("pw");
        if (flags.containsKey("by")) return "css=" + flags.get("by");
        String selector = flags.get("selector");
        if (selector != null && !selector.isBlank()) {
            String trimmed = selector.trim();
            if (trimmed.startsWith("{")) return "desc=" + trimmed;
            if (looksLikePipeline(trimmed)) return trimmed;
            return "css=" + trimmed;
        }
        return null;
    }

    private static boolean looksLikePipeline(String text) {
        for (String engine : ENGINES) {
            if (text.startsWith(engine + "=") || text.contains(">>")) return true;
        }
        if (text.startsWith("(") && text.contains("=")) return true;
        return Expressions.hasOperator(text);
    }

    /** Engine name for a plain stage, or {@code expr} when the text is a boolean expression. */
    public static String leadingEngine(String pipeline) {
        if (pipeline == null || pipeline.isBlank()) return "css";
        String trimmed = pipeline.trim();
        if (trimmed.startsWith("(") || Expressions.hasOperator(trimmed)) return "expr";
        int eq = trimmed.indexOf('=');
        if (eq <= 0) return "css";
        return trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
    }

    public static List<Parsed> parsePipeline(String text) {
        List<String> parts = splitStages(text);
        List<Parsed> stages = new ArrayList<>();
        for (String part : parts) {
            String piece = part.trim();
            if (piece.isEmpty()) throw new CdpException("invalid pipeline stage: " + text);
            if (piece.startsWith("(") || Expressions.hasOperator(piece)) {
                Expressions.parse(piece);
                stages.add(new Parsed("expr", piece));
                continue;
            }
            int eq = piece.indexOf('=');
            if (eq <= 0) throw new CdpException("invalid pipeline stage: " + piece);
            String engine = piece.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String expr = piece.substring(eq + 1).trim();
            if (!ENGINES.contains(engine)) throw new CdpException("invalid engine: " + engine);
            stages.add(new Parsed(engine, expr));
        }
        if (stages.isEmpty()) throw new CdpException("pipeline is empty");
        return stages;
    }

    static List<String> splitStages(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quote = false;
        char quoteChar = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote) {
                current.append(c);
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                current.append(c);
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            if (depth == 0 && c == '>' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                parts.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    static ObjectNode nodeJson(DomNode node, boolean geometry) {
        ObjectNode json = Jsons.object();
        json.put("frame_id", node.frameId);
        json.put("backend_node_id", node.backendNodeId);
        json.put("node_id", node.nodeId);
        json.put("node_name", node.nodeName);
        json.put("handle", node.id());
        if (!node.attributes.isEmpty()) {
            ObjectNode attrs = json.putObject("attributes");
            node.attributes.forEach((key, value) -> {
                if (!key.startsWith("data-") || key.equals("data-testid")) attrs.put(key, value);
                else attrs.put(key, value);
            });
        }
        String text = HtmlTree.textOf(node);
        if (!text.isEmpty()) json.put("text", text.length() > 80 ? text.substring(0, 80) : text);
        if (node.hasBox) {
            json.set("bounds", box(node.x, node.y, node.width, node.height));
        }
        if (geometry && node.hasBox) json.set("geometry", geometry(node));
        String role = roleOf(node);
        if (!role.isEmpty()) json.put("role", role);
        return json;
    }

    static ObjectNode compositeJson(DomNode node, boolean geometry) {
        ObjectNode json = Jsons.object();
        json.put("id", node.id());
        if (node.hasBox) json.set("bounds", box(node.x, node.y, node.width, node.height));
        String role = roleOf(node);
        if (!role.isEmpty()) json.put("role", role);
        String text = HtmlTree.textOf(node);
        if (!text.isEmpty()) json.put("text", text.length() > 80 ? text.substring(0, 80) : text);
        if (!node.accessibleName.isBlank()) json.put("name", node.accessibleName);
        else if (!text.isEmpty()) json.put("name", text.length() > 80 ? text.substring(0, 80) : text);
        ObjectNode attrs = json.putObject("attributes");
        node.attributes.forEach(attrs::put);
        ArrayNode states = json.putArray("states");
        if (node.attributes.containsKey("disabled")) states.add("disabled");
        ArrayNode children = json.putArray("children");
        for (DomNode child : node.children) {
            if (child.nodeType != 1 || child.iframe) continue;
            ObjectNode one = children.addObject();
            one.put("id", child.id());
            one.put("node_name", child.nodeName);
        }
        json.put("source", "Dom");
        if (geometry && node.hasBox) json.set("geometry", geometry(node));
        return json;
    }

    public static ObjectNode geometry(DomNode node) {
        ObjectNode geo = Jsons.object();
        geo.set("document_css_px", box(node.x, node.y, node.width, node.height));
        geo.set("visual_viewport_relative", box(node.x, node.y, node.width, node.height));
        geo.set("layout_viewport_relative", box(node.x, node.y, node.width, node.height));
        geo.set("nearest_scrollport_relative", box(node.x, node.y, node.width, node.height));
        geo.set("frame_document_relative", box(node.frameX, node.frameY, node.width, node.height));
        return geo;
    }

    private static ObjectNode box(double x, double y, double width, double height) {
        ObjectNode box = Jsons.object();
        box.put("x", x);
        box.put("y", y);
        box.put("width", width);
        box.put("height", height);
        return box;
    }

    public static String roleOf(DomNode node) {
        if (node == null) return "";
        if (!node.role.isBlank()) return node.role;
        String explicit = node.attributes.get("role");
        if (explicit != null && !explicit.isBlank()) return explicit.toLowerCase(Locale.ROOT);
        return switch (node.localName == null ? "" : node.localName) {
            case "button" -> "button";
            case "dialog" -> "dialog";
            case "a" -> "link";
            case "textarea" -> "textbox";
            case "select" -> "combobox";
            case "img" -> "img";
            case "h1", "h2", "h3", "h4", "h5", "h6" -> "heading";
            case "input" -> inputRole(node.attributes.get("type"));
            default -> "";
        };
    }

    private static String inputRole(String type) {
        if (type == null || type.isBlank() || "text".equalsIgnoreCase(type) || "search".equalsIgnoreCase(type)) return "textbox";
        if ("checkbox".equalsIgnoreCase(type)) return "checkbox";
        if ("radio".equalsIgnoreCase(type)) return "radio";
        if ("button".equalsIgnoreCase(type) || "submit".equalsIgnoreCase(type)) return "button";
        return "textbox";
    }

    public static Map<String, String> flagMap(Map<String, String> flags) {
        return flags == null ? Map.of() : flags;
    }

    public record Parsed(String engine, String expr) {
    }

    public static final class Hit {
        public final List<DomNode> nodes;
        public final String why;
        public final ObjectNode image;

        public Hit(List<DomNode> nodes, String why) {
            this(nodes, why, null);
        }

        public Hit(List<DomNode> nodes, String why, ObjectNode image) {
            this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
            this.why = why == null ? "" : why;
            this.image = image;
        }
    }

    /** Keeps insertion order when a stage matches the same node twice. */
    public static List<DomNode> distinct(List<DomNode> nodes) {
        Map<String, DomNode> seen = new LinkedHashMap<>();
        for (DomNode node : nodes) seen.putIfAbsent(node.id(), node);
        return new ArrayList<>(seen.values());
    }

    public static JsonNode unused() {
        return null;
    }
}
